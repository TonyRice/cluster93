package automately.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
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
 * @param ingressPort   where services' HTTP traffic arrives (routed by Host)
 * @param ingressDomain services get automatic hosts {@code <service>--<tenant>.<ingressDomain>}
 * @param egressAllowPrivate let scripts' fetch reach private and loopback addresses (local development only)
 * @param internalPort  this node's internal endpoint, where the ingress proxies service traffic (cluster-only)
 * @param advertiseHost the address other nodes use to reach the internal endpoint
 * @param ingressIdleTimeout how long a proxied request may go without a byte before it's closed
 * @param maxRequestBytes largest request body the ingress accepts
 * @param tenantStorageBytes files one tenant may store
 * @param minFreeDiskBytes writes are refused when the data directory's disk has less free space than this (D-038)
 * @param dataDirectory durable storage for this node (Ignite persistence); null keeps data in memory only
 * @param dataBackups   extra copies of every piece of data on other nodes
 * @param dataMemoryMb  off-heap memory for the durable data region
 * @param baselineAdjust how long a departed data node is waited for before its data is rebalanced elsewhere
 * @param jobRetention  finished jobs older than this are deleted
 * @param clusterSize   nodes a brand-new durable cluster waits for before activating
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
        int ingressPort,
        String ingressDomain,
        boolean egressAllowPrivate,
        int internalPort,
        String advertiseHost,
        Duration ingressIdleTimeout,
        long maxRequestBytes,
        long tenantStorageBytes,
        long minFreeDiskBytes,
        Path dataDirectory,
        int dataBackups,
        int dataMemoryMb,
        Duration baselineAdjust,
        Duration jobRetention,
        int clusterSize,
        boolean clustered,
        int clusterPort,
        List<String> clusterMembers,
        String adminToken,
        long rateLimitPerMinute,
        String filesBackend,
        Path filesDirectory,
        automately.core.files.s3.S3Settings s3,
        String secretsKey,
        Path diskRoot,
        int metricsPort,
        String clusterBind) {

    public static final java.util.List<String> FILES_BACKENDS = java.util.List.of("cluster", "disk", "s3");

    public ServerConfig {
        if (!FILES_BACKENDS.contains(filesBackend)) {
            throw new IllegalArgumentException("AUTOMATELY_FILES_BACKEND must be one of " + FILES_BACKENDS + " (it was " + filesBackend + ")");
        }
        if (secretsKey != null && secretsKey.length() < automately.core.storage.SecretBox.MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("AUTOMATELY_SECRETS_KEY must be at least " + automately.core.storage.SecretBox.MIN_KEY_LENGTH + " characters");
        }
        if (filesBackend.equals("disk") && filesDirectory == null) {
            throw new IllegalArgumentException("AUTOMATELY_FILES_DIR is required with AUTOMATELY_FILES_BACKEND=disk");
        }
        if (adminToken != null && adminToken.length() < automately.core.auth.Authenticator.MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("AUTOMATELY_ADMIN_TOKEN must be at least "
                    + automately.core.auth.Authenticator.MIN_ADMIN_TOKEN_LENGTH + " characters");
        }
    }

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
                Integer.parseInt(env.getOrDefault("AUTOMATELY_INGRESS_PORT", "8000")),
                env.getOrDefault("AUTOMATELY_INGRESS_DOMAIN", "localhost"),
                Boolean.parseBoolean(env.getOrDefault("AUTOMATELY_EGRESS_ALLOW_PRIVATE", "false")),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_INTERNAL_PORT", "8090")),
                env.getOrDefault("AUTOMATELY_ADVERTISE_HOST", defaultAdvertiseHost()),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_INGRESS_IDLE_TIMEOUT_MS", "60000"))),
                Long.parseLong(env.getOrDefault("AUTOMATELY_MAX_REQUEST_BYTES", String.valueOf(1024L * 1024 * 1024))),
                Long.parseLong(env.getOrDefault("AUTOMATELY_TENANT_STORAGE_MB", "1024")) * 1024 * 1024,
                Long.parseLong(env.getOrDefault("AUTOMATELY_MIN_FREE_DISK_MB", "1024")) * 1024 * 1024,
                Optional.ofNullable(env.get("AUTOMATELY_DATA_DIR")).filter(dir -> !dir.isBlank()).map(Path::of).orElse(null),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_DATA_BACKUPS", "1")),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_DATA_MEMORY_MB", "256")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("AUTOMATELY_BASELINE_ADJUST_MS", "60000"))),
                Duration.ofDays(Long.parseLong(env.getOrDefault("AUTOMATELY_JOB_RETENTION_DAYS", "7"))),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_CLUSTER_SIZE", "1")),
                Boolean.parseBoolean(env.getOrDefault("AUTOMATELY_CLUSTERED", "false")),
                Integer.parseInt(env.getOrDefault("AUTOMATELY_CLUSTER_PORT", "5701")),
                Arrays.stream(env.getOrDefault("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1").split(","))
                        .map(String::trim)
                        .filter(member -> !member.isEmpty())
                        .toList(),
                blankToNull(env.get("AUTOMATELY_ADMIN_TOKEN")),
                Long.parseLong(env.getOrDefault("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "1200")),
                env.getOrDefault("AUTOMATELY_FILES_BACKEND", "cluster").trim().toLowerCase(java.util.Locale.ROOT),
                blankToNull(env.get("AUTOMATELY_FILES_DIR")) == null ? null : Path.of(env.get("AUTOMATELY_FILES_DIR").trim()),
                "s3".equalsIgnoreCase(env.getOrDefault("AUTOMATELY_FILES_BACKEND", "").trim()) ? automately.core.files.s3.S3Settings.fromEnv(env) : null,
                blankToNull(env.get("AUTOMATELY_SECRETS_KEY")),
                blankToNull(env.get("AUTOMATELY_DISK_ROOT")) == null ? null : Path.of(env.get("AUTOMATELY_DISK_ROOT").trim()),
                parsePort(env.getOrDefault("AUTOMATELY_METRICS_PORT", "9090")),
                // Ignite listens on this address only: AUTOMATELY_CLUSTER_BIND, or an explicitly set advertised host (D-065).
                blankToNull(env.getOrDefault("AUTOMATELY_CLUSTER_BIND", env.get("AUTOMATELY_ADVERTISE_HOST"))));
    }

    /** A port number, 0 for a random one, or "off" (-1). */
    private static int parsePort(String value) {
        return "off".equalsIgnoreCase(value.trim()) ? -1 : Integer.parseInt(value.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultAdvertiseHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    private static String defaultNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
