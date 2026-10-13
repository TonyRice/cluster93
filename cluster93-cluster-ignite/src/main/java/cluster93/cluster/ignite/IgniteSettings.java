package cluster93.cluster.ignite;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * How this node's Ignite is set up.
 *
 * @param nodeId           also Ignite's consistent id, so a restarted node finds its own data on disk
 * @param dataDirectory    where durable data lives; null means in-memory only (development and tests)
 * @param discoveryPort    first discovery port; communication uses {@code discoveryPort + 100}
 * @param members          addresses to join, {@code host} or {@code host:port}; a bare host is probed from {@code discoveryPort}
 * @param backups          extra copies of every piece of durable data on other nodes
 * @param dataMemoryMb     off-heap memory for the durable region (data beyond it stays on disk)
 * @param baselineAdjust   how long a departed node is waited for before its data is rebalanced elsewhere
 * @param initialClusterSize how many nodes a brand-new durable cluster waits for before it activates, so data is
 *                         replicated from the first write (like Consul's bootstrap_expect)
 * @param bindHost         the one address Ignite listens on; null means every interface (D-065)
 * @param ssl              TLS for discovery and communication, with node certificates required on both sides (D-066);
 *                         null means plain TCP (development and tests that opt out)
 * @param joinTimeout      how long a node keeps trying to join members that answer but refuse it (a revoked or
 *                         foreign certificate) before its start fails; zero waits forever
 */
public record IgniteSettings(String nodeId, Path dataDirectory, int discoveryPort, List<String> members, int backups,
                             int dataMemoryMb, Duration baselineAdjust, int initialClusterSize, String bindHost,
                             javax.cache.configuration.Factory<javax.net.ssl.SSLContext> ssl, Duration joinTimeout) {

    public static final Duration DEFAULT_JOIN_TIMEOUT = Duration.ofSeconds(60);

    public IgniteSettings(String nodeId, Path dataDirectory, int discoveryPort, List<String> members, int backups,
                          int dataMemoryMb, Duration baselineAdjust, int initialClusterSize, String bindHost,
                          javax.cache.configuration.Factory<javax.net.ssl.SSLContext> ssl) {
        this(nodeId, dataDirectory, discoveryPort, members, backups, dataMemoryMb, baselineAdjust, initialClusterSize, bindHost, ssl,
                DEFAULT_JOIN_TIMEOUT);
    }

    public IgniteSettings(String nodeId, Path dataDirectory, int discoveryPort, List<String> members, int backups,
                          int dataMemoryMb, Duration baselineAdjust, int initialClusterSize, String bindHost) {
        this(nodeId, dataDirectory, discoveryPort, members, backups, dataMemoryMb, baselineAdjust, initialClusterSize, bindHost, null);
    }

    /** Listening on every interface: tests and single-machine development. */
    public IgniteSettings(String nodeId, Path dataDirectory, int discoveryPort, List<String> members, int backups,
                          int dataMemoryMb, Duration baselineAdjust, int initialClusterSize) {
        this(nodeId, dataDirectory, discoveryPort, members, backups, dataMemoryMb, baselineAdjust, initialClusterSize, null, null);
    }

    public boolean persistent() {
        return dataDirectory != null;
    }
}
