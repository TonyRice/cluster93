package automately.cluster.ignite;

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
 */
public record IgniteSettings(String nodeId, Path dataDirectory, int discoveryPort, List<String> members, int backups,
                             int dataMemoryMb, Duration baselineAdjust, int initialClusterSize, String bindHost) {

    /** Listening on every interface: tests and single-machine development. */
    public IgniteSettings(String nodeId, Path dataDirectory, int discoveryPort, List<String> members, int backups,
                          int dataMemoryMb, Duration baselineAdjust, int initialClusterSize) {
        this(nodeId, dataDirectory, discoveryPort, members, backups, dataMemoryMb, baselineAdjust, initialClusterSize, null);
    }

    public boolean persistent() {
        return dataDirectory != null;
    }
}
