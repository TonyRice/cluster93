package cluster93.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Node configuration, read from {@code C93_*} environment variables.
 *
 * @param heartbeat     how often nodes announce themselves; a node is considered gone after 3 missed heartbeats
 * @param orphanGrace   how much longer a job's node must stay gone before the job is treated as orphaned
 * @param requeueAfter  how long an attempt may sit QUEUED before it is dispatched again
 * @param stopGrace     how long a stopped job or service gets to finish before its isolate is terminated
 * @param maxServicesPerTenant how many enabled services one tenant may have
 * @param httpBind      the address the API, ingress and metrics endpoints bind, so a node's control plane can be
 *                      restricted to loopback (D-151); the internal endpoint is deliberately not covered by it,
 *                      because peers must reach it and it is protected by node certificates
 * @param maxJobsPerTenant how many jobs one tenant may run at once (D-156). {@code capacity / maxJobsPerTenant}
 *                      is roughly how many tenants this node serves concurrently; a hard cap, so a quiet node
 *                      idles by design, and a single-tenant node should set it to {@code capacity}
 * @param ingressPort   where services' HTTP traffic arrives (routed by Host)
 * @param ingressDomain services get automatic hosts {@code <service>--<tenant>.<ingressDomain>}
 * @param egressAllowPrivate let scripts' fetch reach private and loopback addresses (local development only)
 * @param egressPolicyFile   a JSON cluster egress policy stored at the cluster's first start, or null (D-111)
 * @param egressDnsServers   DNS servers that answer every script lookup and query, instead of the system's (D-122)
 * @param egressInternalDomains more internal zones whose names scripts can't resolve unless a rule names them (D-122)
 * @param internalPort  this node's internal endpoint, where the ingress proxies service traffic (cluster-only)
 * @param advertiseHost the address other nodes use to reach the internal endpoint
 * @param ingressIdleTimeout how long a proxied request may go without a byte before it's closed
 * @param maxRequestBytes largest request body the ingress accepts
 * @param ingressUpgradedIdleTimeout how long an upgraded connection (a WebSocket) may go without a byte before it's closed (D-125)
 * @param maxUpgradedPerRun upgraded connections (WebSockets) one run of a service may hold open at once (D-125)
 * @param tenantStorageBytes files one tenant may store
 * @param packageCacheBytes  package files a node keeps in memory once fetched and checked (D-089)
 * @param minFreeDiskBytes writes are refused when the data directory's disk has less free space than this (D-038)
 * @param dataDirectory durable storage for this node (Ignite persistence); null keeps data in memory only
 * @param dataBackups   extra copies of every piece of data on other nodes
 * @param dataMemoryMb  off-heap memory for the durable data region
 * @param baselineAdjust how long a departed data node is waited for before its data is rebalanced elsewhere
 * @param jobRetention  finished jobs older than this are deleted
 * @param clusterSize   nodes a brand-new durable cluster waits for before activating
 * @param clusterMembers Hazelcast TCP/IP join addresses, e.g. {@code node1,node2,node3}; a host without a port is probed from {@code clusterPort} upwards
 * @param roles         the personas this node plays ({@code api}, {@code ingress}, {@code worker}, {@code data}, {@code console}); a node only
 *                      serves a role it lists, a node without {@code worker} takes no tenant compute, and {@code console}
 *                      designates the node that serves the management web console (docs/design/node-roles.md)
 * @param capabilities  execution engines this node advertises for capability matching, such as {@code js:v8} or {@code js:graaljs}
 *                      (empty means advertise no special capabilities, so any worker node matches)
 * @param consoleDomain the host the web console answers on (docs/design/apps-and-console.md); null disables console-host handling
 * @param consoleAllowedCidrs client CIDRs allowed to reach the console host; empty means no network restriction
 */
public record ServerConfig(
        String nodeId,
        int httpPort,
        String httpBind,
        int capacity,
        int maxJobsPerTenant,
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
        Path egressPolicyFile,
        List<java.net.InetSocketAddress> egressDnsServers,
        List<String> egressInternalDomains,
        int internalPort,
        String advertiseHost,
        Duration ingressIdleTimeout,
        long maxRequestBytes,
        Duration ingressUpgradedIdleTimeout,
        int maxUpgradedPerRun,
        long tenantStorageBytes,
        long packageCacheBytes,
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
        cluster93.core.files.s3.S3Settings s3,
        String secretsKey,
        Path diskRoot,
        int metricsPort,
        String clusterBind,
        boolean clusterTls,
        Path tlsDirectory,
        String join,
        Duration clusterJoinTimeout,
        Duration auditRetention,
        cluster93.core.files.s3.S3Settings auditS3,
        Duration auditExportInterval,
        long tmpMemoryMb,
        long tmpMb,
        String workerMode,
        String workerDefaultMode,
        Map<String, String> workerBinaries,
        Set<String> roles,
        Set<String> capabilities,
        String consoleDomain,
        List<String> consoleAllowedCidrs) {

    public static final java.util.List<String> FILES_BACKENDS = java.util.List.of("cluster", "disk", "s3");
    public static final java.util.List<String> WORKER_MODES = java.util.List.of("embedded", "process");
    /** The personas a node can play (docs/design/node-roles.md): every role is on by default, as in development. */
    public static final java.util.List<String> ROLES = java.util.List.of("api", "ingress", "worker", "data", "console");

    public ServerConfig {
        if (!FILES_BACKENDS.contains(filesBackend)) {
            throw new IllegalArgumentException("C93_FILES_BACKEND must be one of " + FILES_BACKENDS + " (it was " + filesBackend + ")");
        }
        if (!WORKER_MODES.contains(workerMode)) {
            throw new IllegalArgumentException("C93_WORKER_MODE must be one of " + WORKER_MODES + " (it was " + workerMode + ")");
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("C93_ROLES must name at least one of " + ROLES);
        }
        for (String role : roles) {
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("C93_ROLES must be a comma-separated subset of " + ROLES + " (it named " + role + ")");
            }
        }
        if (secretsKey != null && secretsKey.length() < cluster93.core.storage.SecretBox.MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("C93_SECRETS_KEY must be at least " + cluster93.core.storage.SecretBox.MIN_KEY_LENGTH + " characters");
        }
        if (filesBackend.equals("disk") && filesDirectory == null) {
            throw new IllegalArgumentException("C93_FILES_DIR is required with C93_FILES_BACKEND=disk");
        }
        if (auditRetention.isNegative() || auditRetention.isZero()) {
            throw new IllegalArgumentException("C93_AUDIT_RETENTION_DAYS must be at least 1");
        }
        if (adminToken != null && adminToken.length() < cluster93.core.auth.Authenticator.MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("C93_ADMIN_TOKEN must be at least "
                    + cluster93.core.auth.Authenticator.MIN_ADMIN_TOKEN_LENGTH + " characters");
        }
    }

    public static ServerConfig fromEnv(Map<String, String> env) {
        Duration heartbeat = Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_HEARTBEAT_MS", "1000")));
        // Resolved once so the /tmp default and the durable directory agree on whether this node persists.
        Path dataDirectory = dataDirectory(env);
        // The process-mode worker engine used when a run does not name one; C93_WORKER_ENGINE is the legacy alias.
        String workerDefaultMode = env.getOrDefault("C93_WORKER_DEFAULT_MODE",
                env.getOrDefault("C93_WORKER_ENGINE", "graaljs")).trim().toLowerCase(java.util.Locale.ROOT);
        return new ServerConfig(
                env.getOrDefault("C93_NODE_ID", defaultNodeId()),
                Integer.parseInt(env.getOrDefault("C93_HTTP_PORT", "9393")),
                // The API, ingress and metrics bind address (D-151); all interfaces by default, as before.
                blankToNull(env.get("C93_HTTP_BIND")) == null ? "0.0.0.0" : env.get("C93_HTTP_BIND").trim(),
                Integer.parseInt(env.getOrDefault("C93_CAPACITY", "8")),
                // How many jobs one tenant may run at once (D-156). Half the node by default, so two tenants fit;
                // a single-tenant node should set it to C93_CAPACITY. This is the reservation, not the ordering.
                Integer.parseInt(env.getOrDefault("C93_MAX_JOBS_PER_TENANT",
                        String.valueOf(Math.max(1, Integer.parseInt(env.getOrDefault("C93_CAPACITY", "8")) / 2)))),
                Integer.parseInt(env.getOrDefault("C93_MAX_HEAP_MB", "128")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_DEFAULT_TIMEOUT_MS", "10000"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_MAX_TIMEOUT_MS", "60000"))),
                heartbeat,
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_ORPHAN_GRACE_MS", String.valueOf(heartbeat.toMillis() * 3)))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_REQUEUE_AFTER_MS", "10000"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_STOP_GRACE_MS", "5000"))),
                Integer.parseInt(env.getOrDefault("C93_MAX_SERVICES_PER_TENANT", "10")),
                Integer.parseInt(env.getOrDefault("C93_INGRESS_PORT", "9394")),
                env.getOrDefault("C93_INGRESS_DOMAIN", "localhost"),
                Boolean.parseBoolean(env.getOrDefault("C93_EGRESS_ALLOW_PRIVATE", "false")),
                // Seeds the cluster's egress policy at its first start only (D-111); the API changes it after that.
                blankToNull(env.get("C93_EGRESS_POLICY_FILE")) == null ? null : Path.of(env.get("C93_EGRESS_POLICY_FILE").trim()),
                // Split-horizon resolvers: these servers answer scripts instead of the system's (§8.2, D-122).
                cluster93.core.host.dns.DnsResolver.parseServers(env.get("C93_EGRESS_DNS")),
                Arrays.stream(env.getOrDefault("C93_EGRESS_INTERNAL_DOMAINS", "").split(","))
                        .map(String::trim)
                        .filter(domain -> !domain.isEmpty())
                        .toList(),
                Integer.parseInt(env.getOrDefault("C93_INTERNAL_PORT", "9396")),
                env.getOrDefault("C93_ADVERTISE_HOST", defaultAdvertiseHost()),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_INGRESS_IDLE_TIMEOUT_MS", "60000"))),
                Long.parseLong(env.getOrDefault("C93_MAX_REQUEST_BYTES", String.valueOf(1024L * 1024 * 1024))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_INGRESS_WEBSOCKET_IDLE_TIMEOUT_MS", "600000"))),
                Integer.parseInt(env.getOrDefault("C93_SERVICE_MAX_WEBSOCKETS", String.valueOf(cluster93.core.host.InternalHttpVerticle.DEFAULT_MAX_UPGRADED))),
                Long.parseLong(env.getOrDefault("C93_TENANT_STORAGE_MB", "1024")) * 1024 * 1024,
                Long.parseLong(env.getOrDefault("C93_PACKAGE_CACHE_MB", "64")) * 1024 * 1024,
                Long.parseLong(env.getOrDefault("C93_MIN_FREE_DISK_MB", "1024")) * 1024 * 1024,
                // Durable data location; a standalone node defaults to $HOME/.cluster93/data (dev.sh, a bare `java -jar`).
                dataDirectory,
                Integer.parseInt(env.getOrDefault("C93_DATA_BACKUPS", "1")),
                Integer.parseInt(env.getOrDefault("C93_DATA_MEMORY_MB", "256")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_BASELINE_ADJUST_MS", "60000"))),
                Duration.ofDays(Long.parseLong(env.getOrDefault("C93_JOB_RETENTION_DAYS", "7"))),
                Integer.parseInt(env.getOrDefault("C93_CLUSTER_SIZE", "1")),
                Boolean.parseBoolean(env.getOrDefault("C93_CLUSTERED", "false")),
                Integer.parseInt(env.getOrDefault("C93_CLUSTER_PORT", "5701")),
                Arrays.stream(env.getOrDefault("C93_CLUSTER_MEMBERS", "127.0.0.1").split(","))
                        .map(String::trim)
                        .filter(member -> !member.isEmpty())
                        .toList(),
                blankToNull(env.get("C93_ADMIN_TOKEN")),
                Long.parseLong(env.getOrDefault("C93_RATE_LIMIT_PER_MINUTE", "1200")),
                env.getOrDefault("C93_FILES_BACKEND", "cluster").trim().toLowerCase(java.util.Locale.ROOT),
                blankToNull(env.get("C93_FILES_DIR")) == null ? null : Path.of(env.get("C93_FILES_DIR").trim()),
                "s3".equalsIgnoreCase(env.getOrDefault("C93_FILES_BACKEND", "").trim()) ? cluster93.core.files.s3.S3Settings.fromEnv(env) : null,
                blankToNull(env.get("C93_SECRETS_KEY")),
                blankToNull(env.get("C93_DISK_ROOT")) == null ? null : Path.of(env.get("C93_DISK_ROOT").trim()),
                parsePort(env.getOrDefault("C93_METRICS_PORT", "9395")),
                // Ignite listens on this address only: C93_CLUSTER_BIND, or an explicitly set advertised host (D-065).
                blankToNull(env.getOrDefault("C93_CLUSTER_BIND", env.get("C93_ADVERTISE_HOST"))),
                // Node certificates are required between clustered nodes unless explicitly turned off (D-066).
                clusterTlsRequired(env.getOrDefault("C93_CLUSTER_TLS", "required")),
                tlsDirectory(env),
                blankToNull(env.get("C93_JOIN")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("C93_CLUSTER_JOIN_TIMEOUT_MS", "60000"))),
                // How long audit entries are kept (D-071).
                Duration.ofDays(Long.parseLong(env.getOrDefault("C93_AUDIT_RETENTION_DAYS", "90").trim())),
                // Where the audit log is copied, outside the cluster (D-073); null keeps it in the cluster only.
                cluster93.core.files.s3.S3Settings.auditFromEnv(env),
                Duration.ofSeconds(Long.parseLong(env.getOrDefault("C93_AUDIT_EXPORT_INTERVAL_S", "300").trim())),
                // A job's /tmp (D-079): this much in memory (all of it without a data directory), then spilled up to the total.
                Long.parseLong(env.getOrDefault("C93_TMP_MEMORY_MB", dataDirectory == null ? "64" : "8").trim()),
                Long.parseLong(env.getOrDefault("C93_TMP_MB", "1024").trim()),
                env.getOrDefault("C93_WORKER_MODE", "process").trim().toLowerCase(java.util.Locale.ROOT),
                workerDefaultMode,
                parseWorkerBinaries(env, workerDefaultMode),
                parseSet(env.getOrDefault("C93_ROLES", String.join(",", ROLES))),
                parseSet(env.getOrDefault("C93_CAPABILITIES", "")),
                // The console's host binding and the networks allowed to reach it (docs/design/apps-and-console.md).
                blankToNull(env.get("C93_CONSOLE_DOMAIN")) == null ? null
                        : env.get("C93_CONSOLE_DOMAIN").trim().toLowerCase(java.util.Locale.ROOT),
                parseList(env.getOrDefault("C93_CONSOLE_ALLOWED_CIDRS", "")));
    }

    /** A comma-separated list, trimmed, empty entries dropped, in the order given. */
    private static List<String> parseList(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(entry -> !entry.isEmpty()).toList();
    }

    /** A comma-separated list, trimmed, empty entries dropped, in the order given. */
    private static Set<String> parseSet(String value) {
        Set<String> parsed = new LinkedHashSet<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return Set.copyOf(parsed);
    }

    /**
     * Where durable data lives: {@code C93_DATA_DIR} when set, else {@code $HOME/.cluster93/data} for a standalone
     * node. A clustered node without an explicit directory stays in memory: several nodes on one host (tests, a local
     * cluster) need their own directories, which the operator names.
     */
    private static Path dataDirectory(Map<String, String> env) {
        String explicit = blankToNull(env.get("C93_DATA_DIR"));
        if (explicit != null) {
            return Path.of(explicit);
        }
        if (Boolean.parseBoolean(env.getOrDefault("C93_CLUSTERED", "false"))) {
            return null;
        }
        return Path.of(System.getProperty("user.home"), ".cluster93", "data");
    }

    private static Map<String, String> parseWorkerBinaries(Map<String, String> env, String workerDefaultMode) {
        Map<String, String> binaries = new java.util.HashMap<>();
        String genericBinary = blankToNull(env.get("C93_WORKER_BINARY"));
        if (genericBinary != null) {
            binaries.put("default", genericBinary);
            binaries.put("javet", genericBinary);
            binaries.put("graaljs", genericBinary);
        } else {
            // No explicit binary: pre-populate defaults from the native workers the build script installs (D-129).
            String javet = cluster93.runner.protocol.pool.TenantWorkerPool.resolvePath("bin/worker-javet");
            if (javet != null) {
                binaries.put("javet", javet);
            }
            String graaljs = cluster93.runner.protocol.pool.TenantWorkerPool.resolvePath("bin/worker-graaljs");
            if (graaljs != null) {
                binaries.put("graaljs", graaljs);
            }
            // Select the "default" binary according to the worker default mode, falling back to whichever is present.
            String defaultBinary = null;
            if ("graaljs".equals(workerDefaultMode) && graaljs != null) {
                defaultBinary = graaljs;
            } else if ("javet".equals(workerDefaultMode) && javet != null) {
                defaultBinary = javet;
            } else if (graaljs != null) {
                defaultBinary = graaljs;
            } else if (javet != null) {
                defaultBinary = javet;
            }
            if (defaultBinary != null) {
                binaries.put("default", defaultBinary);
            }
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().startsWith("C93_WORKER_BINARY_")) {
                String mode = entry.getKey().substring("C93_WORKER_BINARY_".length()).toLowerCase(java.util.Locale.ROOT);
                if (!entry.getValue().isBlank()) {
                    binaries.put(mode, entry.getValue().trim());
                }
            }
        }
        return Map.copyOf(binaries);
    }

    private static boolean clusterTlsRequired(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "required" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("C93_CLUSTER_TLS must be \"required\" or \"off\" (it was " + value + ")");
        };
    }

    /** Where the node's key and certificates live: C93_TLS_DIR, else {@code <data dir>/tls}. */
    private static Path tlsDirectory(Map<String, String> env) {
        String explicit = blankToNull(env.get("C93_TLS_DIR"));
        if (explicit != null) {
            return Path.of(explicit);
        }
        String data = blankToNull(env.get("C93_DATA_DIR"));
        return data == null ? null : Path.of(data).resolve("tls");
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
