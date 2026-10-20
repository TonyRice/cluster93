package cluster93.cluster.ignite;

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
import org.apache.ignite.internal.util.IgniteUtils;
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
 * as Cluster93's durable, replicated store. Durable data lives in the {@value #DATA_REGION} region, on
 * disk when a data directory is set; Vert.x's own maps and other ephemeral state live in the default
 * in-memory region, so nothing like event-bus subscriptions ever comes back from disk.
 */
public final class IgniteCluster {

    private static final Logger LOG = LoggerFactory.getLogger(IgniteCluster.class);

    public static final String DATA_REGION = "cluster93";
    /** Each table's partitions need their own pages; 256 per table (not Ignite's 1024) keeps that overhead small. */
    public static final int PARTITIONS = 256;
    /** Smallest durable region that holds every table's partition metadata with room for data (D-038). */
    public static final int MIN_DATA_MEMORY_MB = 128;
    /** How far a bare-host member may be probed (D-185): only the shorthand form, where peers search the range too. */
    static final int DISCOVERY_PORT_RANGE = 10;
    static final int COMMUNICATION_PORT_RANGE = 20;
    /** The ports above the discovery port: communication. */
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
            throw new IllegalArgumentException("C93_DATA_MEMORY_MB must be at least " + MIN_DATA_MEMORY_MB
                    + " (it was " + settings.dataMemoryMb() + "): smaller regions can't hold the tables' metadata");
        }
        // Statically addressed (every member names its port): bind exactly that port, or refuse to start (D-185).
        // Ignite's default is to try the next one, which puts the node where no peer looks — it bound 5702 while every
        // member joined 5701, and what the operator saw was opaque TLS errors rather than a bind failure. The shorthand
        // form (a bare host, probed upwards) keeps the range: there the peers search it too, so moving is not a lie.
        if (exactPorts(settings)) {
            requireFree(settings, settings.discoveryPort(), "discovery");
            requireFree(settings, settings.discoveryPort() + COMMUNICATION_OFFSET, "communication");
        }
        System.setProperty("IGNITE_UPDATE_NOTIFIER", "false");
        System.setProperty("IGNITE_NO_ASCII", "true");
        // Decided before Ignite starts: a node that already has its own database on disk is restarting, and must
        // never activate the cluster itself (that raced Ignite's own auto-activation and halted the JVM). A data
        // directory another identity has written to (development beside the permanent master, say) holds a db/
        // folder too, so the check is for *this* node's store, not merely that the shared db/ exists.
        boolean firstStart = !settings.persistent() || !nodeDatabasePresent(settings);
        Ignite ignite = Ignition.start(configuration(settings));
        awaitActive(ignite, settings, firstStart);
        BinaryWarmup.vertxTypes(ignite);
        if (settings.persistent()) {
            ignite.cluster().baselineAutoAdjustEnabled(true);
            ignite.cluster().baselineAutoAdjustTimeout(settings.baselineAdjust().toMillis());
        } else {
            LOG.warn("No data directory configured: jobs, services, kv and files are kept in memory only. They are lost if every "
                    + "node stops, and the least recently used are evicted when the {} MB data region fills up. "
                    + "Set C93_DATA_DIR for durable storage.", settings.dataMemoryMb());
        }
        return new IgniteCluster(ignite, settings);
    }

    /**
     * Whether the cluster is statically addressed: members name their ports (so peers expect exactly those ports), and
     * none names a range. A bare host — or a {@code host:port..port} range — instead says "probe this host upwards",
     * and is the one form where moving to the next free port is not a mismatch (D-185).
     */
    static boolean exactPorts(IgniteSettings settings) {
        return settings.members().stream().noneMatch(m -> m.contains(".."))
                && settings.members().stream().anyMatch(m -> m.contains(":"));
    }

    /**
     * Fails before Ignite starts when one of the node's two ports is taken (D-185) — the statically addressed case,
     * where a moved port is one no peer will look at. What an operator would otherwise see is SSL or
     * {@code invalid stream header} errors from a cluster whose members are on different ports. The message names the
     * setting, the port and the fix; the cause is kept for the "address already in use" detail.
     */
    private static void requireFree(IgniteSettings settings, int port, String role) {
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            // As Ignite binds it: reuse lets a lingering TIME_WAIT socket through, while an active listener still fails.
            probe.setReuseAddress(true);
            probe.bind(new java.net.InetSocketAddress(port), 1);
        } catch (IOException e) {
            throw new IllegalStateException("C93_CLUSTER_PORT " + settings.discoveryPort()
                    + " is unusable: this node's " + role + " port " + port + " is already in use ("
                    + e.getMessage() + "). A node binds exactly its base and the communication port "
                    + COMMUNICATION_OFFSET + " above it, and peers must list the same base — free the port, or give "
                    + "this node and every peer another base.", e);
        }
    }

    /**
     * Waits until the cluster is active (D-031). On a node's very first start, with no baseline yet, it activates
     * the cluster. A restarting clustered node never does: Ignite activates by itself once every baseline
     * (data-holding) node is back.
     * If some never come back, the cluster is re-baselined without them after {@code baselineAdjust}, but only
     * if no more are missing than there are backups, so no data can be lost; otherwise it keeps waiting.
     * A {@code initialClusterSize} of one is the standalone case and never waits: a single node with no baseline,
     * or a baseline that names only nodes of another identity, has nothing to protect, so it activates at once
     * rather than stalling on nodes that will never return.
     */
    static void awaitActive(Ignite ignite, IgniteSettings settings, boolean firstStart) {
        long waitingSince = System.currentTimeMillis();
        long lastReport = 0;
        Object self = ignite.cluster().localNode().consistentId();
        while (ignite.cluster().state() != ClusterState.ACTIVE) {
            var baseline = ignite.cluster().currentBaselineTopology();
            var serverNodes = ignite.cluster().forServers().nodes();
            int servers = serverNodes.size();
            if (baseline == null) {
                if (firstStart && (!settings.persistent() || servers >= settings.initialClusterSize())) {
                    ignite.cluster().state(ClusterState.ACTIVE);
                    break;
                }
                // A standalone node has nothing and no one to wait for. Even when it reused a data directory
                // another identity wrote to (so its own store is absent and firstStart can't be trusted), waiting
                // for a baseline that will never appear would stall it forever: activate now.
                if (settings.initialClusterSize() == 1 && servers == 1) {
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
            java.util.Set<Object> present = serverNodes.stream()
                    .map(org.apache.ignite.cluster.ClusterNode::consistentId).collect(java.util.stream.Collectors.toSet());
            // A standalone cluster whose baseline names only absent nodes of another identity (a data directory
            // reused from a different node id) holds no local data to protect: activate instead of waiting the
            // full baseline-adjust timeout for nodes that will never return.
            if (settings.initialClusterSize() == 1 && servers == 1 && !present.contains(self)) {
                LOG.warn("Standalone node: baseline {} names no node of this identity; activating now instead of waiting", baseline);
                ignite.cluster().state(ClusterState.ACTIVE);
                break;
            }
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

    /**
     * Whether this node already has its own durable store on disk. Ignite keeps a node's local database in
     * {@code <dataDirectory>/db/<masked consistent id>}, so a data directory another identity has written to must
     * not be mistaken for this node's own: doing so left {@link #awaitActive} waiting for a baseline this node
     * never had. The masking rules Ignite uses to turn the consistent id into a folder name are reused here.
     */
    static boolean nodeDatabasePresent(IgniteSettings settings) {
        return settings.persistent()
                && Files.isDirectory(settings.dataDirectory().resolve("db").resolve(IgniteUtils.maskForFileName(settings.nodeId())));
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

        boolean exact = exactPorts(settings);
        List<String> addresses = settings.members().stream()
                .map(m -> m.contains(":") ? m
                        : exact ? m + ":" + settings.discoveryPort()
                        : m + ":" + settings.discoveryPort() + ".." + (settings.discoveryPort() + DISCOVERY_PORT_RANGE - 1))
                .toList();
        IgniteConfiguration configuration = new IgniteConfiguration()
                .setIgniteInstanceName("cluster93-" + settings.nodeId())
                .setConsistentId(settings.nodeId())
                .setWorkDirectory(work.toString())
                .setGridLogger(new Slf4jLogger())
                .setMetricsLogFrequency(0)
                .setDataStorageConfiguration(storage)
                .setCacheConfiguration(ephemeralTemplate("__vertx.*"), ephemeralTemplate("*"))
                .setSqlConfiguration(new SqlConfiguration().setQueryEnginesConfiguration(
                        new CalciteQueryEngineConfiguration().setDefault(true)))
                .setDiscoverySpi(new TcpDiscoverySpi()
                        .setJoinTimeout(settings.joinTimeout().toMillis())
                        .setLocalPort(settings.discoveryPort())
                        // No fallback when peers expect this exact port; the shorthand form keeps the range (D-185).
                        .setLocalPortRange(exact ? 0 : DISCOVERY_PORT_RANGE)
                        .setIpFinder(new TcpDiscoveryVmIpFinder().setAddresses(addresses)))
                .setCommunicationSpi(new TcpCommunicationSpi()
                        .setLocalPort(settings.discoveryPort() + COMMUNICATION_OFFSET)
                        .setLocalPortRange(exact ? 0 : COMMUNICATION_PORT_RANGE))
                // Ignite's thin-client/JDBC/ODBC connector (10800) and its REST command protocol (11211) are on by
                // default, unauthenticated, on every interface. Cluster93 uses neither, so neither listens (D-065).
                .setClientConnectorConfiguration(null)
                .setConnectorConfiguration(null);
        if (settings.bindHost() != null) {
            configuration.setLocalHost(settings.bindHost());
        }
        if (settings.ssl() != null) {
            // Discovery and communication both use it when it's set; the context requires the peer's node certificate (D-066).
            configuration.setSslContextFactory(settings.ssl());
        }
        return configuration;
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
            return Files.createTempDirectory("cluster93-" + nodeId + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A clustered Vert.x that uses this Ignite as its cluster manager. */
    public Future<Vertx> vertx() {
        return Vertx.builder().withClusterManager(new IgniteClusterManager(ignite)).buildClustered();
    }

    /** The same, with metrics recorded through {@code metrics} (phase 5). */
    public Future<Vertx> vertx(io.vertx.core.VertxOptions options, io.vertx.core.spi.VertxMetricsFactory metrics) {
        return Vertx.builder().with(options).withMetrics(metrics).withClusterManager(new IgniteClusterManager(ignite)).buildClustered();
    }

    /** Bytes used in the data region, for the metrics. */
    public long dataRegionUsedBytes() {
        org.apache.ignite.DataRegionMetrics metrics = ignite.dataRegionMetrics(DATA_REGION);
        return metrics == null ? 0 : metrics.getTotalUsedSize();
    }

    /**
     * Forces node {@code nodeId} (its consistent id) out of the cluster, if it's in it now; for revocation (D-066).
     * Discovery marks it failed for every node, which isn't up to the node itself (unlike {@code stopNodes}, which asks
     * it to stop). Its attempts to reconnect then fail the TLS check. Returns whether it was in the cluster.
     */
    public boolean failNode(String nodeId) {
        if (nodeId.equals(settings.nodeId())) {
            return false;
        }
        var target = ignite.cluster().forPredicate(node -> nodeId.equals(String.valueOf(node.consistentId())));
        if (target.nodes().isEmpty()) {
            return false;
        }
        org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi discovery =
                (org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi) ignite.configuration().getDiscoverySpi();
        target.nodes().forEach(node -> discovery.failNode(node.id(), "Node " + nodeId + " was revoked"));
        return true;
    }

    public boolean active() {
        return ignite.cluster().state().active();
    }

    public Ignite ignite() {
        return ignite;
    }

    public IgniteSettings settings() {
        return settings;
    }

    /** Stops this node's Ignite (after Vert.x has closed). Blocking. */
    /**
     * Every binary type this node knows, with how many schemas (field layouts) it has. Diagnostic: after start, this
     * should never change, since a schema first used while a node is leaving stalls the survivors (D-042).
     */
    public java.util.Map<String, Integer> binarySchemas() {
        java.util.Map<String, Integer> schemas = new java.util.TreeMap<>();
        for (org.apache.ignite.binary.BinaryType type : ignite.binary().types()) {
            try {
                Object metadata = ((org.apache.ignite.internal.binary.BinaryTypeImpl) type).metadata();
                java.lang.reflect.Method method = metadata.getClass().getDeclaredMethod("schemas");
                method.setAccessible(true);
                schemas.put(type.typeName(), ((java.util.Collection<?>) method.invoke(metadata)).size());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Can't read Ignite's binary metadata", e);
            }
        }
        return schemas;
    }

    public void stop() {
        Ignition.stop(ignite.name(), true);
    }
}
