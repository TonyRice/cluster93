package automately.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Node configuration, read from {@code AUTOMATELY_*} environment variables.
 *
 * @param heartbeat     how often nodes announce themselves; a node is considered gone after 3 missed heartbeats
 * @param orphanGrace   how much longer a job's node must stay gone before the job is treated as orphaned
 * @param requeueAfter  how long an attempt may sit QUEUED before it is dispatched again
 * @param stopGrace     how long a stopped job or service gets to finish before its isolate is terminated
 * @param maxServicesPerTenant how many enabled services one tenant may have
 * @param clusterMembers Hazelcast TCP/IP join addresses, e.g. {@code node1,node2,node3}; a host without a port is probed from {@code clusterPort} upwards
 */
public record ServerConfig(
        String nodeId,
        int httpPort,
        int capacity,
        int maxHeapMb,
        Duration defaultTimeout,
        Duration maxTimeout,
        Duration heartbeat,
        Duration orphanGrace,
        Duration requeueAfter,
        Duration stopGrace,
        int maxServicesPerTenant,
        boolean clustered,
        String clusterName,
        int clusterPort,
        List<String> clusterMembers) {

    public static ServerConfig fromEnv(Map<String, String> env) {
        Duration heartbeat = Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_HEARTBEAT_MS", "1000")));
        return new ServerConfig(
                env.getOrDefault("AUTOMATELY_NODE_ID", defaultNodeId()),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_HTTP_PORT", "8080")),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_CAPACITY", "8")),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_MAX_HEAP_MB", "128")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_DEFAULT_TIMEOUT_MS", "10000"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_MAX_TIMEOUT_MS", "60000"))),
                heartbeat,
                Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_ORPHAN_GRACE_MS", String.valueOf(heartbeat.toMillis() * 3)))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_REQUEUE_AFTER_MS", "10000"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_STOP_GRACE_MS", "5000"))),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_MAX_SERVICES_PER_TENANT", "10")),
                Boolean.parseBoolean(env.getOrDefault("AUTOMATELY_CLUSTERED", "false")),
                env.getOrDefault("AUTOMATELY_CLUSTER_NAME", "automately"),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_CLUSTER_PORT", "5701")),
                Arrays.stream(env.getOrDefault("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1").split(","))
                        .map(String::trim)
                        .filter(member -> !member.isEmpty())
                        .toList());
    }

    private static String defaultNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
