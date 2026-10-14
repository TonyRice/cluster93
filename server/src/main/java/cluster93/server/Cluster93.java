package cluster93.server;

import cluster93.core.job.JobStore;
import cluster93.core.job.SharedDataJobStore;
import cluster93.core.node.NodeRegistry;
import cluster93.core.node.SharedDataNodeRegistry;
import cluster93.core.runtime.ScriptRuntime;
import cluster93.core.scheduler.JobScheduler;
import cluster93.core.scheduler.NodeVerticle;
import cluster93.core.scheduler.OrphanReaper;
import cluster93.core.scheduler.RetentionVerticle;
import cluster93.core.host.KvStore;
import cluster93.cluster.ignite.IgniteCapacity;
import cluster93.cluster.ignite.IgniteCluster;
import cluster93.core.files.FileGarbageVerticle;
import cluster93.core.files.FileStore;
import cluster93.core.files.SharedDataFileStore;
import cluster93.core.files.StorageCapacity;
import cluster93.core.storage.TenantQuota;
import cluster93.core.metrics.Metrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.VertxOptions;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MicrometerMetricsFactory;
import io.vertx.micrometer.MicrometerMetricsOptions;
import cluster93.core.files.BlobStore;
import cluster93.core.files.DiskBlobStore;
import cluster93.core.files.SharedDataChunks;
import cluster93.core.files.s3.S3BlobStore;
import cluster93.core.storage.SecretBox;
import cluster93.core.storage.SharedDataStorageConfigStore;
import cluster93.core.storage.SharedFolderCheck;
import cluster93.core.storage.StorageConfigStore;
import cluster93.core.storage.StorageRoom;
import cluster93.core.storage.TenantBlobStores;
import cluster93.server.api.StorageApi;
import cluster93.core.auth.AuthStore;
import cluster93.core.auth.Authenticator;
import cluster93.core.auth.SharedDataAuthStore;
import cluster93.cluster.ignite.IgniteSettings;
import cluster93.cluster.ignite.store.IgniteStores;
import cluster93.core.service.ServiceStore;
import cluster93.core.service.ServiceSupervisor;
import cluster93.core.service.SharedDataServiceStore;
import cluster93.core.state.JobStateStore;
import cluster93.core.state.SharedDataJobStateStore;
import cluster93.runtime.javet.JavetScriptRuntime;
import cluster93.core.host.DomainStore;
import cluster93.core.host.SharedDataDomainStore;
import cluster93.core.host.EgressPolicy;
import cluster93.core.host.FetchClient;
import cluster93.core.host.HostRouter;
import cluster93.core.host.HostApisFactory;
import cluster93.core.host.NodeHostApis;
import cluster93.core.queue.QueueService;
import cluster93.core.callback.CallbackDelivery;
import cluster93.core.callback.CallbackJobStore;
import cluster93.core.callback.CallbackSecrets;
import cluster93.core.host.ServeRegistry;
import cluster93.core.host.InternalHttpVerticle;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.host.TxtResolver;
import cluster93.server.api.ApiVerticle;
import cluster93.server.ingress.DnsTxtResolver;
import cluster93.server.ingress.IngressVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/** Wires one node together. This is the only place that chooses implementations. */
public final class Cluster93 {

    private static final Logger LOG = LoggerFactory.getLogger(Cluster93.class);

    static final java.time.Duration RETENTION_INTERVAL = java.time.Duration.ofMinutes(10);
    static final java.time.Duration FILE_GARBAGE_INTERVAL = java.time.Duration.ofMinutes(1);
    /** How often unreferenced package files are looked for, and how old one must be before it's deleted (D-092). */
    static final java.time.Duration PACKAGE_GARBAGE_INTERVAL = java.time.Duration.ofMinutes(10);
    static final java.time.Duration PACKAGE_GARBAGE_GRACE = java.time.Duration.ofHours(1);

    private Cluster93() {
    }

    public static Future<RunningNode> start(ServerConfig config) {
        return start(config, DnsTxtResolver::new);
    }

    /** {@code txt} builds the DNS TXT resolver used for domain verification; tests substitute a fake. */
    public static Future<RunningNode> start(ServerConfig config, java.util.function.Function<Vertx, TxtResolver> txt) {
        if (!config.clustered()) {
            PrometheusMeterRegistry registry = registry(config);
            Vertx vertx = Vertx.builder().with(vertxOptions()).withMetrics(new MicrometerMetricsFactory(registry)).build();
            Metrics.install(vertx, registry);
            return wire(config, txt, vertx, Stores.inMemory(vertx, config), null, Security.NONE);
        }
        // The cluster is the database (D-030): Ignite comes up first (blocking, before Vert.x serves anything),
        // creates its tables, and then becomes Vert.x's cluster manager.
        IgniteCluster cluster;
        Security security;
        try {
            security = Security.of(config);
            cluster = IgniteCluster.start(new IgniteSettings(config.nodeId(), config.dataDirectory(), config.clusterPort(),
                    config.clusterMembers(), config.dataBackups(), config.dataMemoryMb(), config.baselineAdjust(), config.clusterSize(),
                    config.clusterBind(), security.tls() == null ? null : security.tls()::sslContext, config.clusterJoinTimeout()));
            if (config.clusterBind() == null) {
                LOG.warn("Ignite listens on every interface; set C93_CLUSTER_BIND (or C93_ADVERTISE_HOST) to the "
                        + "private address other nodes use (SECURITY.md)");
            }
            IgniteStores.createTables(cluster);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        PrometheusMeterRegistry registry = registry(config);
        return cluster.vertx(vertxOptions(security), new MicrometerMetricsFactory(registry))
                .compose(vertx -> {
                    Metrics.install(vertx, registry);
                    io.micrometer.core.instrument.Gauge.builder("c93.ignite.data.region.used", cluster, IgniteCluster::dataRegionUsedBytes)
                            .description("Bytes used in the cluster data region on this node").baseUnit("bytes").register(registry);
                    IgniteStores durable = IgniteStores.open(vertx, cluster);
                    StorageCapacity capacity = IgniteCapacity.of(cluster, config.minFreeDiskBytes());
                    BlobStore blobs = fileBackend(vertx, config, () -> durable.chunks(capacity));
                    java.util.concurrent.atomic.AtomicBoolean diskRootShared = new java.util.concurrent.atomic.AtomicBoolean();
                    TenantBlobStores routed = tenantStores(vertx, config, blobs, durable.storage, diskRootShared);
                    cluster93.cluster.ignite.store.IgniteFileStore fileStore = durable.files(routed);
                    // Files written before directories existed join the tree (D-076); a no-op once done.
                    fileStore.backfillTree().onSuccess(count -> {
                        if (count > 0) {
                            LOG.info("Added {} existing files to the directory tree", count);
                        }
                    }).onFailure(err -> LOG.warn("Couldn't add existing files to the directory tree: {}", err.getMessage()));
                    Stores stores = new Stores(durable.jobs, durable.state, durable.services, durable.domains, durable.kv,
                            fileStore, blobs, capacity, durable.auth, durable.storage, routed, diskRootShared, durable.queues,
                            durable.records, durable.audit, durable.apps);
                    return wire(config, txt, vertx, stores, cluster, security);
                })
                .recover(err -> {
                    cluster.stop();
                    return Future.failedFuture(err);
                });
    }

    /** Ready once this node has joined (it's among the live nodes) and, when clustered, the cluster is active. */
    static Future<List<String>> readiness(NodeRegistry nodes, String instanceId, IgniteCluster cluster) {
        return nodes.liveNodes().map(live -> {
            List<String> reasons = new java.util.ArrayList<>();
            if (live.stream().noneMatch(node -> node.instanceId().equals(instanceId))) {
                reasons.add("This node hasn't joined the live nodes yet");
            }
            if (cluster != null && !cluster.active()) {
                reasons.add("The cluster isn't active (waiting for its nodes)");
            }
            return reasons;
        });
    }

    /** One registry per node, tagged with the node's id (several nodes can share a JVM in tests). */
    static PrometheusMeterRegistry registry(ServerConfig config) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("node", config.nodeId());
        new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(registry);
        new io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics().bindTo(registry);
        new io.micrometer.core.instrument.binder.system.ProcessorMetrics().bindTo(registry);
        new io.micrometer.core.instrument.binder.system.UptimeMetrics().bindTo(registry);
        return registry;
    }

    /**
     * Vert.x's own metrics with labels that stay small: no client addresses or event-bus addresses (one per job),
     * which would make a series per request.
     */
    static VertxOptions vertxOptions() {
        return vertxOptions(Security.NONE);
    }

    static VertxOptions vertxOptions(Security security) {
        VertxOptions options = new VertxOptions().setMetricsOptions(new MicrometerMetricsOptions().setEnabled(true)
                .setLabels(java.util.EnumSet.of(Label.HTTP_METHOD, Label.HTTP_CODE, Label.HTTP_ROUTE, Label.LOCAL, Label.POOL_TYPE, Label.POOL_NAME)));
        if (security.tls() != null) {
            ClusterSecurity.secure(options.getEventBusOptions(), security.tls());
        }
        return options;
    }

    /**
     * Node certificates for this node (D-066), or none: single nodes, and clusters started with C93_CLUSTER_TLS=off.
     * The revocations are shared with the TLS context, which consults them on every handshake.
     */
    record Security(cluster93.tls.ClusterTls tls, cluster93.tls.Revocations revocations, java.nio.file.Path directory) {

        static final Security NONE = new Security(null, null, null);

        static Security of(ServerConfig config) {
            if (!config.clusterTls()) {
                LOG.warn("C93_CLUSTER_TLS=off: nodes talk to each other without authentication or encryption. Development only.");
                return NONE;
            }
            cluster93.tls.NodeIdentity identity = ClusterSecurity.identity(config);
            cluster93.tls.Revocations revocations = ClusterSecurity.revocations(config.tlsDirectory());
            LOG.info("Node certificate for {} (expires {}); cluster CA {}", identity.nodeId(), identity.expiresAt(),
                    cluster93.tls.ClusterCa.fingerprint(identity.ca().getPublicKey()));
            return new Security(new cluster93.tls.ClusterTls(identity, revocations), revocations, config.tlsDirectory());
        }
    }

    /**
     * Prometheus at {@code /metrics} on its own port, plus cluster gauges: live nodes, and whether this node leads
     * (the lowest instance id, as the reaper and supervisor decide). Completes with the bound port, or -1 if off.
     */
    static Future<Integer> serveMetrics(Vertx vertx, ServerConfig config, String instanceId, NodeRegistry nodes, IgniteCluster cluster) {
        Metrics metrics = Metrics.of(vertx);
        java.util.concurrent.atomic.AtomicInteger live = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger leader = new java.util.concurrent.atomic.AtomicInteger();
        io.micrometer.core.instrument.Gauge.builder("c93.cluster.nodes", live, java.util.concurrent.atomic.AtomicInteger::get)
                .description("Live nodes this node sees").register(metrics.registry());
        io.micrometer.core.instrument.Gauge.builder("c93.cluster.leader", leader, java.util.concurrent.atomic.AtomicInteger::get)
                .description("1 if this node is the leader").register(metrics.registry());
        vertx.setPeriodic(0, config.heartbeat().toMillis() * 5, id -> nodes.liveNodes().onSuccess(all -> {
            live.set(all.size());
            leader.set(all.stream().map(n -> n.instanceId()).min(String::compareTo).map(instanceId::equals).orElse(false) ? 1 : 0);
        }));
        if (config.metricsPort() < 0) {
            return Future.succeededFuture(-1);
        }
        PrometheusMeterRegistry registry = (PrometheusMeterRegistry) metrics.registry();
        return vertx.createHttpServer().requestHandler(request -> {
            if ("/metrics".equals(request.path())) {
                request.response().putHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8").end(registry.scrape());
            } else {
                request.response().setStatusCode(404).end();
            }
        }).listen(config.metricsPort(), config.httpBind()).map(io.vertx.core.http.HttpServer::actualPort);
    }

    /**
     * This node's own ports, which scripts can't reach on a node's address even with the development switch on (D-108):
     * the API, the ingress, the internal endpoint, metrics, and the cluster's discovery and communication ranges.
     */
    static java.util.Set<Integer> nodePorts(ServerConfig config) {
        java.util.Set<Integer> ports = new java.util.HashSet<>(java.util.List.of(config.httpPort(), config.ingressPort(), config.internalPort()));
        if (config.metricsPort() > 0) {
            ports.add(config.metricsPort());
        }
        // IgniteCluster's ranges: discovery from the cluster port, communication 100 above it, 10 ports each.
        for (int i = 0; i < 10; i++) {
            ports.add(config.clusterPort() + i);
            ports.add(config.clusterPort() + 100 + i);
        }
        ports.remove(0);
        return ports;
    }

    /** Tenants' own providers on top of the cluster's default (D-057). */
    static TenantBlobStores tenantStores(Vertx vertx, ServerConfig config, BlobStore primary, StorageConfigStore storage,
                                         java.util.concurrent.atomic.AtomicBoolean diskRootShared) {
        SecretBox secrets = config.secretsKey() == null ? null : new SecretBox(config.secretsKey());
        return new TenantBlobStores(vertx, primary, storage, secrets, config.diskRoot(), config.minFreeDiskBytes(), diskRootShared::get,
                config.egressAllowPrivate());
    }

    /** Where file bytes go (D-056): the cluster by default, or a folder, or an S3-compatible bucket. */
    static BlobStore fileBackend(Vertx vertx, ServerConfig config, java.util.function.Supplier<BlobStore> cluster) {
        return switch (config.filesBackend()) {
            case DiskBlobStore.NAME -> new DiskBlobStore(vertx, config.filesDirectory(), config.minFreeDiskBytes());
            case S3BlobStore.NAME -> new S3BlobStore(vertx, config.s3());
            default -> cluster.get();
        };
    }

    /**
     * Jobs' {@code /tmp} (D-079): in memory, spilling to {@code <data dir>/tmp/<job>} on durable nodes. Whatever a crash
     * left there is cleared here, at start.
     */
    static cluster93.core.files.TmpSettings tmpSettings(ServerConfig config) {
        long mb = cluster93.core.files.TmpSettings.MB;
        if (config.dataDirectory() == null) {
            return new cluster93.core.files.TmpSettings(null, config.tmpMemoryMb() * mb, config.tmpMemoryMb() * mb, 0);
        }
        java.nio.file.Path root = config.dataDirectory().resolve("tmp");
        cluster93.core.files.TmpStore.deleteTree(root);
        return new cluster93.core.files.TmpSettings(root, config.tmpMemoryMb() * mb, config.tmpMb() * mb, config.minFreeDiskBytes());
    }

    /** Where durable data lives: Ignite when clustered, in-memory maps for a single development node. */
    record Stores(JobStore jobs, JobStateStore state, ServiceStore services, DomainStore domains, KvStore kv, FileStore files,
                  BlobStore blobs, StorageCapacity capacity, AuthStore auth, StorageConfigStore storage, TenantBlobStores routed,
                  java.util.concurrent.atomic.AtomicBoolean diskRootShared, cluster93.core.queue.QueueStore queues,
                  cluster93.core.identity.Records.Factory records, cluster93.core.audit.AuditLog audit,
                  cluster93.core.apps.AppStore apps) {

        static Stores inMemory(Vertx vertx, ServerConfig config) {
            // In-memory data lives on the JVM heap, so that's what admission control watches (D-038).
            StorageCapacity capacity = StorageCapacity.heap(0.8);
            BlobStore blobs = fileBackend(vertx, config, () -> new SharedDataChunks(vertx, capacity));
            StorageConfigStore storage = new SharedDataStorageConfigStore(vertx);
            java.util.concurrent.atomic.AtomicBoolean diskRootShared = new java.util.concurrent.atomic.AtomicBoolean();
            TenantBlobStores routed = tenantStores(vertx, config, blobs, storage, diskRootShared);
            return new Stores(new SharedDataJobStore(vertx), new SharedDataJobStateStore(vertx), new SharedDataServiceStore(vertx),
                    new SharedDataDomainStore(vertx), new SharedDataKvStore(vertx),
                    new SharedDataFileStore(vertx, routed), blobs, capacity, new SharedDataAuthStore(vertx), storage, routed, diskRootShared,
                    new cluster93.core.queue.SharedDataQueueStore(vertx), cluster93.core.identity.SharedDataRecords.factory(vertx),
                    new cluster93.core.audit.MemoryAuditLog(), new cluster93.core.apps.SharedDataAppStore(vertx));
        }
    }

    /**
     * Stores {@code C93_EGRESS_POLICY_FILE} as the cluster's egress policy if the cluster has none yet (D-111).
     * After that the API owns it, so a changed file is ignored, and the log says so. A file that isn't a valid policy
     * stops the node from starting.
     */
    private static Future<Void> seedEgressPolicy(Vertx vertx, ServerConfig config, cluster93.core.host.egress.EgressPolicies policies) {
        if (config.egressPolicyFile() == null) {
            return Future.succeededFuture();
        }
        String file = config.egressPolicyFile().toString();
        return vertx.fileSystem().readFile(file).compose(bytes -> {
            cluster93.core.host.egress.ClusterEgress policy;
            try {
                policy = cluster93.core.host.egress.ClusterEgress.fromJson(new io.vertx.core.json.JsonObject(bytes));
            } catch (IllegalArgumentException | io.vertx.core.json.DecodeException e) {
                return Future.failedFuture(new IllegalArgumentException("C93_EGRESS_POLICY_FILE (" + file + ") isn't a valid egress policy: "
                        + e.getMessage()));
            }
            return policies.seedCluster(policy).onSuccess(stored -> LOG.info(stored
                    ? "Stored the cluster's egress policy from {} (D-111)"
                    : "The cluster already has an egress policy, so {} was ignored; change it with PUT /v1/egress/cluster-policy", file)).mapEmpty();
        });
    }

    private static Future<RunningNode> wire(ServerConfig config, java.util.function.Function<Vertx, TxtResolver> txt, Vertx vertx,
                                            Stores stores, IgniteCluster cluster, Security security) {
        // A fresh id per process start: a restarted node must not be mistaken for its previous life (D-009).
        String instanceId = UUID.randomUUID().toString();
        // Packages, in the cluster's own storage rather than any tenant's (D-083).
        cluster93.core.packages.PackageStore packages = new cluster93.core.packages.PackageStore(
                stores.records().open(cluster93.core.packages.PackageStore.VERSIONS),
                stores.records().open(cluster93.core.packages.PackageStore.BLOBS), stores.blobs());
        // One quota for everything a tenant stores: files, kv, queued messages and package files together (D-043, D-061, D-085).
        TenantQuota quota = new TenantQuota(config.tenantStorageBytes(),
                tenant -> stores.files().usage(tenant).compose(files -> stores.kv().usage(tenant).map(kv -> files + kv))
                        .compose(sum -> stores.queues().usage(tenant).map(queued -> sum + queued))
                        .compose(sum -> packages.usage(tenant).map(published -> sum + published)));
        QueueService queues = new QueueService(vertx, stores.queues(), quota, stores.capacity());
        // Every way a job ends goes through this store, which queues the job's completion callback (D-062).
        JobStore jobs = new CallbackJobStore(stores.jobs(), queues);
        SecretBox secretBox = config.secretsKey() == null ? null : new SecretBox(config.secretsKey());
        CallbackSecrets callbackSecrets = new CallbackSecrets(stores.kv(), secretBox);
        cluster93.core.secrets.TenantSecrets tenantSecrets = new cluster93.core.secrets.TenantSecrets(stores.kv(), secretBox);
        JobStateStore state = stores.state();
        ServiceStore services = stores.services();
        DomainStore domains = stores.domains();
        NodeRegistry nodes = new SharedDataNodeRegistry(vertx, config.heartbeat().multipliedBy(3));
        // Every connection a script causes goes through one gate: never a cluster node, then the cluster's and the
        // tenant's rules and posture (D-108, D-111).
        cluster93.core.host.egress.EgressPolicies egressPolicies = new cluster93.core.host.egress.EgressPolicies(
                stores.records().open(cluster93.core.host.egress.EgressPolicies.KIND),
                stores.records().open(cluster93.core.host.egress.EgressPolicies.CLUSTER_KIND), vertx);
        if (config.egressAllowPrivate()) {
            LOG.warn("C93_EGRESS_ALLOW_PRIVATE is on: every tenant's scripts may reach private and loopback addresses. "
                    + "It's for local development only; grant a tenant specific addresses with its egress policy instead (D-108).");
        }
        FetchClient fetch = new FetchClient(vertx, new cluster93.core.host.egress.Egress(vertx, config.egressAllowPrivate(),
                new cluster93.core.host.egress.ClusterAddresses(vertx, nodes, nodePorts(config)), egressPolicies,
                new cluster93.core.host.dns.DnsResolver(vertx, config.egressDnsServers()), config.egressInternalDomains()));
        ScriptRuntime runtime;
        java.util.concurrent.atomic.AtomicReference<cluster93.runner.protocol.pool.TenantWorkerPoolManager> workerPoolRef = new java.util.concurrent.atomic.AtomicReference<>();
        if ("process".equalsIgnoreCase(config.workerMode())) {
            var poolManager = new cluster93.runner.protocol.pool.TenantWorkerPoolManager(
                    vertx,
                    config.workerBinaries(),
                    config.workerDefaultMode(),
                    1,
                    // Still the pool's *worker* limit: workers are one-job-per-worker until the lifecycle change, so
                    // passing the per-tenant *job* cap here would halve a tenant's throughput. It moves when a
                    // worker holds several jobs (work order step 3).
                    config.capacity(),
                    config.capacity(),
                    config.maxHeapMb(),
                    cluster93.runner.protocol.pool.TenantWorkerPool.ReusePolicy.FRESH_PER_JOB,
                    new cluster93.runner.protocol.pool.CgroupMemoryLimiter(),
                    cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL
            );
            workerPoolRef.set(poolManager);
            runtime = new cluster93.runner.protocol.runtime.WorkerProcessRuntime(vertx, poolManager);
            // Say plainly what this node can run at once, and how the workers are shared: one worker per concurrent
            // job, and the pool is handed out round-robin between tenants, so one tenant cannot take all of them
            // (D-153). Tenants themselves are not capped — only concurrency is.
            LOG.info("Process workers: up to {} concurrent jobs ({} MB each), one worker per job, shared round-robin "
                            + "between tenants; default engine {}. Raise C93_CAPACITY for more concurrency.",
                    config.capacity(), config.maxHeapMb(), config.workerDefaultMode());
        } else {
            runtime = new JavetScriptRuntime(vertx, config.maxHeapMb(), config.capacity());
        }
        JobScheduler scheduler = new JobScheduler(vertx, jobs, nodes);
        ServeRegistry serving = new ServeRegistry();
        StorageRoom room = new StorageRoom(quota, stores.storage(), stores.files());
        cluster93.core.auth.RateLimiter limiter = new cluster93.core.auth.RateLimiter(config.rateLimitPerMinute());
        // Folder moves, copies and deletes, journaled so another node can finish one (D-077).
        cluster93.core.files.FileTree fileTree = new cluster93.core.files.FileTree(vertx, stores.files(),
                stores.records().open(cluster93.core.files.FileTree.OPS), instanceId);
        // Everyone else sees a folder operation's finished result while it runs (D-082); FileTree works on the real paths.
        cluster93.core.files.FileStore visibleFiles = new cluster93.core.files.MoveAwareFileStore(stores.files(),
                stores.records().open(cluster93.core.files.FileTree.OPS));
        fileTree.start();
        HostApisFactory hostApis = NodeHostApis.factory(new NodeHostApis.Node(vertx, stores.kv(), fetch, serving, visibleFiles, stores.capacity(),
                quota, room, queues, new cluster93.core.host.JobsWiring(jobs, scheduler, config.defaultTimeout(), config.maxTimeout(), limiter),
                tenantSecrets, fileTree, tmpSettings(config), new cluster93.core.files.FileLocks(stores.records().open(cluster93.core.files.FileLocks.KIND))));
        // Public links to files, signed and self-checking, so any node serves them (D-080).
        cluster93.core.files.FileLinks fileLinks = new cluster93.core.files.FileLinks(stores.kv(), config.secretsKey(), visibleFiles);
        HostRouter hostRouter = new HostRouter(config.ingressDomain(), domains);
        // Personal tokens act as their user, with what the user's roles give them now (D-070).
        cluster93.core.identity.Identity identity = new cluster93.core.identity.Identity(stores.records(), stores.auth());
        Authenticator authenticator = new Authenticator(stores.auth(), config.adminToken(), identity::resolve);
        // Every entry this node records is chained, keyed with the secrets key when there is one (D-072).
        cluster93.core.audit.AuditLog audit = new cluster93.core.audit.AuditChain(config.nodeId() + "/" + instanceId, config.secretsKey())
                .sealing(stores.audit());
        // The audit log is copied to a bucket outside the cluster when one is configured (D-073); one node at a time.
        cluster93.core.audit.AuditExporter exporter = config.auditS3() == null ? null : new cluster93.core.audit.AuditExporter(vertx, audit,
                stores.records().open(cluster93.core.audit.AuditExporter.STATE), new cluster93.core.files.s3.S3Client(vertx, config.auditS3()),
                instanceId, config.auditExportInterval(), java.time.Duration.ofMinutes(1));
        if (exporter != null) {
            exporter.start();
            LOG.info("The audit log is exported to {}", config.auditS3().describe());
        }
        // Each node prunes the audit log hourly; the deletes are idempotent, so overlapping is harmless (D-071). With an
        // export, nothing is pruned before it has been copied out.
        vertx.setPeriodic(java.time.Duration.ofHours(1).toMillis(), id -> {
            long cutoff = System.currentTimeMillis() - config.auditRetention().toMillis();
            (exporter == null ? Future.succeededFuture(cutoff) : exporter.exportedBefore().map(exported -> Math.min(exported, cutoff)))
                    .compose(audit::prune)
                    .onFailure(err -> LOG.warn("Pruning the audit log failed: {}", err.getMessage()));
        });
        // Which personas this node plays (docs/design/node-roles.md). A node that isn't a worker takes no tenant compute.
        boolean apiRole = config.roles().contains("api");
        boolean ingressRole = config.roles().contains("ingress");
        int nodeCapacity = config.roles().contains("worker") ? config.capacity() : 0;
        LOG.info("Node {} roles: {} (capacity {}, capabilities {})", config.nodeId(), config.roles(), nodeCapacity,
                config.capabilities().isEmpty() ? "none" : config.capabilities());
        ApiVerticle api = apiRole ? new ApiVerticle(config, jobs, state, nodes, scheduler, services, domains, txt.apply(vertx), hostRouter,
                visibleFiles, quota, authenticator, stores.auth(), room, new StorageApi.Wiring(stores.storage(), stores.routed(),
                        secretBox, config.diskRoot(), stores.blobs().name()),
                queues, callbackSecrets, tenantSecrets, identity, audit, fileTree, fileLinks, packages, stores.apps(), limiter, egressPolicies,
                security.tls() == null ? null : id -> ClusterSecurity.revoke(vertx, stores.kv(), cluster, id),
                () -> readiness(nodes, instanceId, cluster)) : null;
        // The ingress and the internal endpoints authenticate each other with a secret the cluster agrees on once.
        // A node whose file backend can't be used refuses to start, saying why, rather than failing uploads later.
        // Tenant disk providers need a disk root every node sees (D-057); a single node always does.
        Future<Void> diskRoot = config.diskRoot() == null ? Future.succeededFuture()
                : new SharedFolderCheck(vertx, config.diskRoot(), instanceId, nodes, stores.diskRootShared()).start()
                        .recover(err -> {
                            LOG.warn("C93_DISK_ROOT {} isn't usable, so tenant disk providers are off: {}", config.diskRoot(), err.getMessage());
                            return Future.succeededFuture();
                        });
        return diskRoot.compose(d -> stores.blobs().check())
                .onSuccess(v -> LOG.info("Files are stored in the {} backend{}", stores.blobs().name(), config.filesBackend().equals(S3BlobStore.NAME)
                        ? ": " + config.s3().describe() : config.filesBackend().equals(DiskBlobStore.NAME) ? ": " + config.filesDirectory() : ""))
                .compose(v -> authenticator.ensureAdminToken(new SecureRandom())).onSuccess(generated -> generated.ifPresent(token -> LOG.warn(
                        "\n\n  No C93_ADMIN_TOKEN was set, so this cluster generated its admin token (shown once, save it now):\n\n"
                        + "    {}\n\n  It manages tenants and tokens only. Rotate it with POST /v1/admin/rotate (D-044).\n", token)))
                .compose(v -> seedEgressPolicy(vertx, config, egressPolicies))
                .compose(v -> clusterSecret(vertx)).compose(secret -> {
            // With node certificates, only another node can reach the internal endpoint, so D-036's secret is retired (D-066).
            InternalHttpVerticle endpoint = security.tls() == null
                    ? new InternalHttpVerticle(config.internalPort(), secret, serving, new io.vertx.core.http.HttpServerOptions(), config.maxUpgradedPerRun())
                    : new InternalHttpVerticle(config.internalPort(), null, serving, ClusterSecurity.internalServer(security.tls()), config.maxUpgradedPerRun());
            // Apps are resolved by host before ordinary service routing (docs/design/apps-and-console.md).
            cluster93.server.ingress.AppRouter appRouter = new cluster93.server.ingress.AppRouter(stores.apps(), visibleFiles,
                    config.ingressDomain(), config.consoleDomain(),
                    cluster93.server.ingress.CidrMatcher.parse(config.consoleAllowedCidrs()), config.roles());
            IngressVerticle ingress = ingressRole ? new IngressVerticle(config.ingressPort(), config.httpBind(), hostRouter, services, jobs, nodes,
                    security.tls() == null ? secret : null, config.ingressIdleTimeout(), config.ingressUpgradedIdleTimeout(), config.maxRequestBytes(),
                    security.tls() == null ? java.util.function.UnaryOperator.identity() : client -> ClusterSecurity.internalClient(client, security.tls()),
                    appRouter, visibleFiles, () -> api == null ? -1 : api.port()) : null;
            return vertx.deployVerticle(endpoint)
                    .compose(id -> vertx.deployVerticle(new NodeVerticle(config.nodeId(), instanceId, nodeCapacity, jobs, state, nodes,
                            runtime, config.heartbeat(), config.stopGrace(), hostApis, config.advertiseHost() + ":" + endpoint.port(),
                            new cluster93.core.packages.PackageLoader(packages, config.packageCacheBytes(),
                                    cluster93.core.metrics.Metrics.of(vertx)), tenantSecrets)))
                    .compose(id -> vertx.deployVerticle(new OrphanReaper(config.nodeId(), instanceId, jobs, nodes, scheduler, config.heartbeat(),
                            config.orphanGrace(), config.requeueAfter())))
                    .compose(id -> vertx.deployVerticle(new ServiceSupervisor(instanceId, services, jobs, nodes, scheduler,
                            config.heartbeat(), ServiceSupervisor.Timing.DEFAULT)))
                    .compose(id -> vertx.deployVerticle(new RetentionVerticle(instanceId, jobs, state, nodes, config.jobRetention(),
                            RETENTION_INTERVAL)))
                    .compose(id -> vertx.deployVerticle(new FileGarbageVerticle(instanceId, stores.files(), nodes, FILE_GARBAGE_INTERVAL)))
                    .compose(id -> vertx.deployVerticle(new cluster93.core.packages.PackageGarbageVerticle(instanceId, packages, nodes,
                            PACKAGE_GARBAGE_INTERVAL, PACKAGE_GARBAGE_GRACE)))
                    .compose(id -> new CallbackDelivery(queues, jobs, fetch, callbackSecrets).start())
                    .compose(id -> security.tls() == null ? Future.succeededFuture()
                            : ClusterSecurity.follow(vertx, stores.kv(), security.revocations(), security.directory(), config.nodeId(),
                            () -> vertx.close().onComplete(ar -> {
                                if (cluster != null) {
                                    cluster.stop();
                                }
                            })))
                    .compose(id -> apiRole ? vertx.deployVerticle(api) : Future.succeededFuture())
                    .compose(id -> ingressRole ? vertx.deployVerticle(ingress) : Future.succeededFuture())
                    .compose(id -> serveMetrics(vertx, config, instanceId, nodes, cluster))
                    .map(metricsPort -> new RunningNode(config.nodeId(), instanceId, vertx, apiRole ? api.port() : -1,
                            ingressRole ? ingress.port() : -1, cluster, apiRole ? api.routes() : List.of(),
                            metricsPort, endpoint.port(), workerPoolRef.get() == null ? null : workerPoolRef.get()::close));
        }).recover(err -> {
            // Not waited for: this runs on one of Vert.x's own threads, which a close can't report back to.
            vertx.close();
            return Future.failedFuture(err);
        });
    }

    /** One random secret per cluster, agreed through shared data (the first node to ask sets it). */
    static Future<String> clusterSecret(Vertx vertx) {
        String candidate = UUID.randomUUID() + "-" + UUID.randomUUID();
        return vertx.sharedData().<String, String>getAsyncMap("cluster93.cluster")
                .compose(map -> map.putIfAbsent("internal-secret", candidate).map(existing -> existing == null ? candidate : existing));
    }

    /** A started node. {@code apiRoutes} lists every REST route as "METHOD path", for checks that walk them all. */
    public record RunningNode(String nodeId, String instanceId, Vertx vertx, int httpPort, int ingressPort, IgniteCluster cluster,
                              List<String> apiRoutes, int metricsPort, int internalPort, Runnable extraShutdown) {

        /** Stops Vert.x (which stops the node's scripts, D-015), this node's Ignite, and any worker processes (D-128). */
        public Future<Void> stop() {
            return vertx.close().andThen(ar -> {
                if (extraShutdown != null) {
                    try {
                        extraShutdown.run();
                    } catch (RuntimeException e) {
                        org.slf4j.LoggerFactory.getLogger(RunningNode.class)
                                .warn("Closing the worker pool failed: {}", e.getMessage());
                    }
                }
                if (cluster != null) {
                    cluster.stop();
                }
            });
        }
    }
}
