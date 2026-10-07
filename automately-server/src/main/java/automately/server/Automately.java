package automately.server;

import automately.core.job.JobStore;
import automately.core.job.SharedDataJobStore;
import automately.core.node.NodeRegistry;
import automately.core.node.SharedDataNodeRegistry;
import automately.core.runtime.ScriptRuntime;
import automately.core.scheduler.JobScheduler;
import automately.core.scheduler.NodeVerticle;
import automately.core.scheduler.OrphanReaper;
import automately.core.scheduler.RetentionVerticle;
import automately.core.host.KvStore;
import automately.cluster.ignite.IgniteCapacity;
import automately.cluster.ignite.IgniteCluster;
import automately.core.files.FileGarbageVerticle;
import automately.core.files.FileStore;
import automately.core.files.SharedDataFileStore;
import automately.core.files.StorageCapacity;
import automately.core.storage.TenantQuota;
import automately.core.files.BlobStore;
import automately.core.files.DiskBlobStore;
import automately.core.files.SharedDataChunks;
import automately.core.files.s3.S3BlobStore;
import automately.core.storage.SecretBox;
import automately.core.storage.SharedDataStorageConfigStore;
import automately.core.storage.SharedFolderCheck;
import automately.core.storage.StorageConfigStore;
import automately.core.storage.StorageRoom;
import automately.core.storage.TenantBlobStores;
import automately.server.api.StorageApi;
import automately.core.auth.AuthStore;
import automately.core.auth.Authenticator;
import automately.core.auth.SharedDataAuthStore;
import automately.cluster.ignite.IgniteSettings;
import automately.cluster.ignite.store.IgniteStores;
import automately.core.service.ServiceStore;
import automately.core.service.ServiceSupervisor;
import automately.core.service.SharedDataServiceStore;
import automately.core.state.JobStateStore;
import automately.core.state.SharedDataJobStateStore;
import automately.runtime.javet.JavetScriptRuntime;
import automately.core.host.DomainStore;
import automately.core.host.SharedDataDomainStore;
import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.HostRouter;
import automately.core.host.HostServicesFactory;
import automately.core.host.NodeHostServices;
import automately.core.host.ServeRegistry;
import automately.core.host.InternalHttpVerticle;
import automately.core.host.SharedDataKvStore;
import automately.core.host.TxtResolver;
import automately.server.api.ApiVerticle;
import automately.server.ingress.DnsTxtResolver;
import automately.server.ingress.IngressVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/** Wires one node together. This is the only place that chooses implementations. */
public final class Automately {

    private static final Logger LOG = LoggerFactory.getLogger(Automately.class);

    static final java.time.Duration RETENTION_INTERVAL = java.time.Duration.ofMinutes(10);
    static final java.time.Duration FILE_GARBAGE_INTERVAL = java.time.Duration.ofMinutes(1);

    private Automately() {
    }

    public static Future<RunningNode> start(ServerConfig config) {
        return start(config, DnsTxtResolver::new);
    }

    /** {@code txt} builds the DNS TXT resolver used for domain verification; tests substitute a fake. */
    public static Future<RunningNode> start(ServerConfig config, java.util.function.Function<Vertx, TxtResolver> txt) {
        if (!config.clustered()) {
            Vertx vertx = Vertx.vertx();
            return wire(config, txt, vertx, Stores.inMemory(vertx, config), null);
        }
        // The cluster is the database (D-030): Ignite comes up first (blocking, before Vert.x serves anything),
        // creates its tables, and then becomes Vert.x's cluster manager.
        IgniteCluster cluster;
        try {
            cluster = IgniteCluster.start(new IgniteSettings(config.nodeId(), config.dataDirectory(), config.clusterPort(),
                    config.clusterMembers(), config.dataBackups(), config.dataMemoryMb(), config.baselineAdjust(), config.clusterSize()));
            IgniteStores.createTables(cluster);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return cluster.vertx()
                .compose(vertx -> {
                    IgniteStores durable = IgniteStores.open(vertx, cluster);
                    StorageCapacity capacity = IgniteCapacity.of(cluster, config.minFreeDiskBytes());
                    BlobStore blobs = fileBackend(vertx, config, () -> durable.chunks(capacity));
                    java.util.concurrent.atomic.AtomicBoolean diskRootShared = new java.util.concurrent.atomic.AtomicBoolean();
                    TenantBlobStores routed = tenantStores(vertx, config, blobs, durable.storage, diskRootShared);
                    Stores stores = new Stores(durable.jobs, durable.state, durable.services, durable.domains, durable.kv,
                            durable.files(routed), blobs, capacity, durable.auth, durable.storage, routed, diskRootShared);
                    return wire(config, txt, vertx, stores, cluster);
                })
                .recover(err -> {
                    cluster.stop();
                    return Future.failedFuture(err);
                });
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

    /** Where durable data lives: Ignite when clustered, in-memory maps for a single development node. */
    record Stores(JobStore jobs, JobStateStore state, ServiceStore services, DomainStore domains, KvStore kv, FileStore files,
                  BlobStore blobs, StorageCapacity capacity, AuthStore auth, StorageConfigStore storage, TenantBlobStores routed,
                  java.util.concurrent.atomic.AtomicBoolean diskRootShared) {

        static Stores inMemory(Vertx vertx, ServerConfig config) {
            // In-memory data lives on the JVM heap, so that's what admission control watches (D-038).
            StorageCapacity capacity = StorageCapacity.heap(0.8);
            BlobStore blobs = fileBackend(vertx, config, () -> new SharedDataChunks(vertx, capacity));
            StorageConfigStore storage = new SharedDataStorageConfigStore(vertx);
            java.util.concurrent.atomic.AtomicBoolean diskRootShared = new java.util.concurrent.atomic.AtomicBoolean();
            TenantBlobStores routed = tenantStores(vertx, config, blobs, storage, diskRootShared);
            return new Stores(new SharedDataJobStore(vertx), new SharedDataJobStateStore(vertx), new SharedDataServiceStore(vertx),
                    new SharedDataDomainStore(vertx), new SharedDataKvStore(vertx),
                    new SharedDataFileStore(vertx, routed), blobs, capacity, new SharedDataAuthStore(vertx), storage, routed, diskRootShared);
        }
    }

    private static Future<RunningNode> wire(ServerConfig config, java.util.function.Function<Vertx, TxtResolver> txt, Vertx vertx,
                                            Stores stores, IgniteCluster cluster) {
        // A fresh id per process start: a restarted node must not be mistaken for its previous life (D-009).
        String instanceId = UUID.randomUUID().toString();
        JobStore jobs = stores.jobs();
        JobStateStore state = stores.state();
        ServiceStore services = stores.services();
        DomainStore domains = stores.domains();
        NodeRegistry nodes = new SharedDataNodeRegistry(vertx, config.heartbeat().multipliedBy(3));
        ScriptRuntime runtime = new JavetScriptRuntime(vertx, config.maxHeapMb(), config.capacity());
        JobScheduler scheduler = new JobScheduler(vertx, jobs, nodes);
        ServeRegistry serving = new ServeRegistry();
        // One quota for everything a tenant stores: files and kv together (D-043).
        TenantQuota quota = new TenantQuota(config.tenantStorageBytes(),
                tenant -> stores.files().usage(tenant).compose(files -> stores.kv().usage(tenant).map(kv -> files + kv)));
        StorageRoom room = new StorageRoom(quota, stores.storage(), stores.files());
        HostServicesFactory hostServices = NodeHostServices.factory(vertx, stores.kv(),
                new FetchClient(vertx, new EgressPolicy(config.egressAllowPrivate())), serving, stores.files(), stores.capacity(), quota, room);
        HostRouter hostRouter = new HostRouter(config.ingressDomain(), domains);
        Authenticator authenticator = new Authenticator(stores.auth(), config.adminToken());
        ApiVerticle api = new ApiVerticle(config, jobs, state, nodes, scheduler, services, domains, txt.apply(vertx), hostRouter, stores.files(),
                quota, authenticator, stores.auth(), room, new StorageApi.Wiring(stores.storage(), stores.routed(),
                        config.secretsKey() == null ? null : new SecretBox(config.secretsKey()), config.diskRoot(), stores.blobs().name()));
        // The ingress and the internal endpoints authenticate each other with a secret the cluster agrees on once.
        // A node whose file backend can't be used refuses to start, saying why, rather than failing uploads later.
        // Tenant disk providers need a disk root every node sees (D-057); a single node always does.
        Future<Void> diskRoot = config.diskRoot() == null ? Future.succeededFuture()
                : new SharedFolderCheck(vertx, config.diskRoot(), instanceId, nodes, stores.diskRootShared()).start()
                        .recover(err -> {
                            LOG.warn("AUTOMATELY_DISK_ROOT {} isn't usable, so tenant disk providers are off: {}", config.diskRoot(), err.getMessage());
                            return Future.succeededFuture();
                        });
        return diskRoot.compose(d -> stores.blobs().check())
                .onSuccess(v -> LOG.info("Files are stored in the {} backend{}", stores.blobs().name(), config.filesBackend().equals(S3BlobStore.NAME)
                        ? ": " + config.s3().describe() : config.filesBackend().equals(DiskBlobStore.NAME) ? ": " + config.filesDirectory() : ""))
                .compose(v -> authenticator.ensureAdminToken(new SecureRandom())).onSuccess(generated -> generated.ifPresent(token -> LOG.warn(
                        "\n\n  No AUTOMATELY_ADMIN_TOKEN was set, so this cluster generated its admin token (shown once, save it now):\n\n"
                        + "    {}\n\n  It manages tenants and tokens only. Rotate it with POST /v1/admin/rotate (D-044).\n", token)))
                .compose(v -> clusterSecret(vertx)).compose(secret -> {
            InternalHttpVerticle endpoint = new InternalHttpVerticle(config.internalPort(), secret, serving);
            IngressVerticle ingress = new IngressVerticle(config.ingressPort(), hostRouter, services, jobs, nodes, secret,
                    config.ingressIdleTimeout(), config.maxRequestBytes());
            return vertx.deployVerticle(endpoint)
                    .compose(id -> vertx.deployVerticle(new NodeVerticle(config.nodeId(), instanceId, config.capacity(), jobs, state, nodes,
                            runtime, config.heartbeat(), config.stopGrace(), hostServices, config.advertiseHost() + ":" + endpoint.port())))
                    .compose(id -> vertx.deployVerticle(new OrphanReaper(instanceId, jobs, nodes, scheduler, config.heartbeat(),
                            config.orphanGrace(), config.requeueAfter())))
                    .compose(id -> vertx.deployVerticle(new ServiceSupervisor(instanceId, services, jobs, nodes, scheduler,
                            config.heartbeat(), ServiceSupervisor.Timing.DEFAULT)))
                    .compose(id -> vertx.deployVerticle(new RetentionVerticle(instanceId, jobs, state, nodes, config.jobRetention(),
                            RETENTION_INTERVAL)))
                    .compose(id -> vertx.deployVerticle(new FileGarbageVerticle(instanceId, stores.files(), nodes, FILE_GARBAGE_INTERVAL)))
                    .compose(id -> vertx.deployVerticle(api))
                    .compose(id -> vertx.deployVerticle(ingress))
                    .map(id -> new RunningNode(config.nodeId(), instanceId, vertx, api.port(), ingress.port(), cluster, api.routes()));
        }).recover(err -> {
            // Not waited for: this runs on one of Vert.x's own threads, which a close can't report back to.
            vertx.close();
            return Future.failedFuture(err);
        });
    }

    /** One random secret per cluster, agreed through shared data (the first node to ask sets it). */
    static Future<String> clusterSecret(Vertx vertx) {
        String candidate = UUID.randomUUID() + "-" + UUID.randomUUID();
        return vertx.sharedData().<String, String>getAsyncMap("automately.cluster")
                .compose(map -> map.putIfAbsent("internal-secret", candidate).map(existing -> existing == null ? candidate : existing));
    }

    /** A started node. {@code apiRoutes} lists every REST route as "METHOD path", for checks that walk them all. */
    public record RunningNode(String nodeId, String instanceId, Vertx vertx, int httpPort, int ingressPort, IgniteCluster cluster,
                              List<String> apiRoutes) {

        /** Stops Vert.x (which stops the node's scripts, D-015), then this node's Ignite. */
        public Future<Void> stop() {
            return vertx.close().andThen(ar -> {
                if (cluster != null) {
                    cluster.stop();
                }
            });
        }
    }
}
