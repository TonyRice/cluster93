package automately.cluster.ignite;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.spi.cluster.ignite.IgniteClusterManager;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataPageEvictionMode;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.SqlConfiguration;
import org.apache.ignite.calcite.CalciteQueryEngineConfiguration;
import org.apache.ignite.logger.slf4j.Slf4jLogger;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The cluster is the database (D-030): one Ignite per node, used both as the Vert.x cluster manager and
 * as Automately's durable, replicated store. Durable data lives in the {@value #DATA_REGION} region, on
 * disk when a data directory is set; Vert.x's own maps and other ephemeral state live in the default
 * in-memory region, so nothing like event-bus subscriptions ever comes back from disk.
 */
public final class IgniteCluster {

    private static final Logger LOG = LoggerFactory.getLogger(IgniteCluster.class);

    public static final String DATA_REGION = "automately";
    /** Each table's partitions need their own pages; 256 per table (not Ignite's 1024) keeps that overhead small. */
    public static final int PARTITIONS = 256;
    /** Smallest durable region that holds every table's partition metadata with room for data (D-038). */
    public static final int MIN_DATA_MEMORY_MB = 128;
    static final int DISCOVERY_PORT_RANGE = 10;
    static final int COMMUNICATION_OFFSET = 100;

    private final Ignite ignite;
    private final IgniteSettings settings;

    private IgniteCluster(Ignite ignite, IgniteSettings settings) {
        this.ignite = ignite;
        this.settings = settings;
    }

    /** Starts this node's Ignite. Blocking; call before Vert.x is up. */
    public static IgniteCluster start(IgniteSettings settings) {
        if (settings.dataMemoryMb() < MIN_DATA_MEMORY_MB) {
            throw new IllegalArgumentException("AUTOMATELY_DATA_MEMORY_MB must be at least " + MIN_DATA_MEMORY_MB
                    + " (it was " + settings.dataMemoryMb() + "): smaller regions can't hold the tables' metadata");
        }
        System.setProperty("IGNITE_UPDATE_NOTIFIER", "false");
        System.setProperty("IGNITE_NO_ASCII", "true");
        // Decided before Ignite starts: a node that already has a database on disk is restarting, and must
        // never activate the cluster itself (that raced Ignite's own auto-activation and halted the JVM).
        boolean firstStart = !settings.persistent() || !Files.isDirectory(settings.dataDirectory().resolve("db"));
        Ignite ignite = Ignition.start(configuration(settings));
        awaitActive(ignite, settings, firstStart);
        if (settings.persistent()) {
            ignite.cluster().baselineAutoAdjustEnabled(true);
            ignite.cluster().baselineAutoAdjustTimeout(settings.baselineAdjust().toMillis());
        } else {
            LOG.warn("No data directory configured: jobs, services, kv and files are kept in memory only. They are lost if every "
                    + "node stops, and the least recently used are evicted when the {} MB data region fills up. "
                    + "Set AUTOMATELY_DATA_DIR for durable storage.", settings.dataMemoryMb());
        }
        return new IgniteCluster(ignite, settings);
    }

    /**
     * Waits until the cluster is active (D-031). On a node's very first start, with no baseline yet, it activates
     * the cluster. A restarting node never does: Ignite activates by itself once every baseline (data-holding)
     * node is back.
     * If some never come back, the cluster is re-baselined without them after {@code baselineAdjust}, but only
     * if no more are missing than there are backups, so no data can be lost; otherwise it keeps waiting.
     */
    static void awaitActive(Ignite ignite, IgniteSettings settings, boolean firstStart) {
        long waitingSince = System.currentTimeMillis();
        long lastReport = 0;
        while (ignite.cluster().state() != ClusterState.ACTIVE) {
            var baseline = ignite.cluster().currentBaselineTopology();
            if (baseline == null) {
                int servers = ignite.cluster().forServers().nodes().size();
                if (firstStart && (!settings.persistent() || servers >= settings.initialClusterSize())) {
                    ignite.cluster().state(ClusterState.ACTIVE);
                    break;
                }
                if (firstStart && System.currentTimeMillis() - lastReport > 30_000) {
                    LOG.info("New durable cluster: waiting for {} of {} nodes before activating", servers, settings.initialClusterSize());
                    lastReport = System.currentTimeMillis();
                }
                sleep(250);
                continue;
            }
            java.util.Set<Object> present = ignite.cluster().forServers().nodes().stream()
                    .map(org.apache.ignite.cluster.ClusterNode::consistentId).collect(java.util.stream.Collectors.toSet());
            List<Object> missing = baseline.stream().map(org.apache.ignite.cluster.BaselineNode::consistentId)
                    .filter(id -> !present.contains(id)).toList();
            long waited = System.currentTimeMillis() - waitingSince;
            if (!missing.isEmpty() && waited >= settings.baselineAdjust().toMillis()) {
                if (missing.size() <= settings.backups()) {
                    LOG.warn("Data nodes {} have not come back after {} ms; activating without them (backups={}, so no data is lost)",
                            missing, waited, settings.backups());
                    // Backups cover the missing nodes. Baseline auto-adjust (enabled since the first start, and
                    // persisted) then drops them after the same timeout; setting the baseline by hand is forbidden.
                    ignite.cluster().state(ClusterState.ACTIVE);
                    break;
                }
                if (System.currentTimeMillis() - lastReport > 30_000) {
                    LOG.error("Waiting for data nodes {}: more are missing than there are backups ({}), so activating now could lose data",
                            missing, settings.backups());
                    lastReport = System.currentTimeMillis();
                }
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the cluster to activate", e);
        }
    }

    static IgniteConfiguration configuration(IgniteSettings settings) {
        Path work = settings.persistent() ? settings.dataDirectory() : temporaryWorkDirectory(settings.nodeId());
        DataRegionConfiguration durable = new DataRegionConfiguration()
                .setName(DATA_REGION)
                .setPersistenceEnabled(settings.persistent())
                .setMetricsEnabled(true)
                .setInitialSize(Math.min(64L, settings.dataMemoryMb()) * 1024 * 1024)
                .setMaxSize(settings.dataMemoryMb() * 1024L * 1024);
        if (!settings.persistent()) {
            // In memory, a full region is an out-of-memory error that halts the JVM. Development mode evicts the
            // least recently used data instead (D-038). With persistence, pages spill to disk, so this doesn't apply.
            durable.setPageEvictionMode(DataPageEvictionMode.RANDOM_2_LRU).setEvictionThreshold(0.9);
        }
        DataStorageConfiguration storage = new DataStorageConfiguration()
                .setDefaultDataRegionConfiguration(new DataRegionConfiguration().setName("default")
                        .setInitialSize(32L * 1024 * 1024).setMaxSize(256L * 1024 * 1024))
                .setDataRegionConfigurations(durable);

        List<String> addresses = settings.members().stream()
                .map(m -> m.contains(":") ? m : m + ":" + settings.discoveryPort() + ".." + (settings.discoveryPort() + DISCOVERY_PORT_RANGE - 1))
                .toList();
        return new IgniteConfiguration()
                .setIgniteInstanceName("automately-" + settings.nodeId())
                .setConsistentId(settings.nodeId())
                .setWorkDirectory(work.toString())
                .setGridLogger(new Slf4jLogger())
                .setMetricsLogFrequency(0)
                .setDataStorageConfiguration(storage)
                .setCacheConfiguration(ephemeralTemplate("__vertx.*"), ephemeralTemplate("*"))
                .setSqlConfiguration(new SqlConfiguration().setQueryEnginesConfiguration(
                        new CalciteQueryEngineConfiguration().setDefault(true)))
                .setDiscoverySpi(new TcpDiscoverySpi()
                        .setLocalPort(settings.discoveryPort())
                        .setLocalPortRange(DISCOVERY_PORT_RANGE)
                        .setIpFinder(new TcpDiscoveryVmIpFinder().setAddresses(addresses)))
                .setCommunicationSpi(new TcpCommunicationSpi()
                        .setLocalPort(settings.discoveryPort() + COMMUNICATION_OFFSET)
                        .setLocalPortRange(20));
    }

    /**
     * Template for caches Vert.x creates by name (its subscriptions and node info, and maps such as the node
     * registry): replicated to every node, in memory, and never blocked by partition loss. Ignite's default
     * is no backups, so a crashed node took a registry partition with it and every later read failed with
     * "partition has been lost" (found with a SIGKILL in DockerClusterIT). This data is re-announced every
     * heartbeat, so availability matters more than any single entry. Durable tables set their own config.
     */
    static CacheConfiguration<Object, Object> ephemeralTemplate(String namePattern) {
        return new CacheConfiguration<>(namePattern)
                .setCacheMode(CacheMode.REPLICATED)
                .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC)
                .setPartitionLossPolicy(PartitionLossPolicy.IGNORE);
    }

    private static Path temporaryWorkDirectory(String nodeId) {
        try {
            return Files.createTempDirectory("automately-" + nodeId + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A clustered Vert.x that uses this Ignite as its cluster manager. */
    public Future<Vertx> vertx() {
        return Vertx.builder().withClusterManager(new IgniteClusterManager(ignite)).buildClustered();
    }

    public Ignite ignite() {
        return ignite;
    }

    public IgniteSettings settings() {
        return settings;
    }

    /** Stops this node's Ignite (after Vert.x has closed). Blocking. */
    public void stop() {
        Ignition.stop(ignite.name(), true);
    }
}
