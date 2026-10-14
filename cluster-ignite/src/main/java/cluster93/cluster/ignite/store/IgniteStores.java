package cluster93.cluster.ignite.store;

import cluster93.cluster.ignite.IgniteCluster;
import cluster93.core.host.DomainStore;
import cluster93.core.files.FileStore;
import cluster93.core.files.StorageCapacity;
import cluster93.core.host.KvStore;
import cluster93.core.job.JobStore;
import cluster93.core.service.ServiceStore;
import cluster93.core.state.JobStateStore;
import io.vertx.core.Vertx;

import java.util.List;

/** Cluster93's durable tables on Ignite (D-030). */
public final class IgniteStores {

    private static final List<Table.Definition> TABLES = java.util.stream.Stream.concat(java.util.stream.Stream.of(
            IgniteJobStore.TABLE, IgniteJobStateStore.TABLE, IgniteServiceStore.TABLE, IgniteDomainStore.TABLE, IgniteKvStore.TABLE,
            IgniteFileStore.FILES, IgniteFileStore.GARBAGE, IgniteFileStore.DIRS, IgniteFileStore.REFS, IgniteAuthStore.TENANTS, IgniteAuthStore.TOKENS, IgniteAuthStore.SETTINGS,
            IgniteStorageConfigStore.POLICIES, IgniteStorageConfigStore.PROVIDERS, IgniteStorageConfigStore.ROUTES,
            IgniteQueueStore.TABLE, IgniteAuditLog.TABLE, IgniteAppStore.APPS, IgniteAppStore.APP_DEPLOYMENTS),
            java.util.stream.Stream.concat(cluster93.core.identity.Identity.KINDS.stream(),
                    java.util.stream.Stream.of(cluster93.core.audit.AuditExporter.STATE, cluster93.core.files.FileTree.OPS,
                    cluster93.core.files.FileLocks.KIND, cluster93.core.packages.PackageStore.VERSIONS,
                    cluster93.core.packages.PackageStore.BLOBS, cluster93.core.host.egress.EgressPolicies.KIND,
                    cluster93.core.host.egress.EgressPolicies.CLUSTER_KIND))
                    .map(IgniteRecords::definition)).toList();

    public final JobStore jobs;
    public final JobStateStore state;
    public final ServiceStore services;
    public final DomainStore domains;
    public final KvStore kv;
    public final cluster93.core.auth.AuthStore auth;
    public final cluster93.core.storage.StorageConfigStore storage;
    public final cluster93.core.queue.QueueStore queues;
    /** Users, memberships, roles and groups, and invites (D-070). */
    public final cluster93.core.identity.Records.Factory records;
    public final cluster93.core.audit.AuditLog audit;
    public final cluster93.core.apps.AppStore apps;
    private final Vertx vertx;
    private final IgniteCluster cluster;

    private IgniteStores(Vertx vertx, IgniteCluster cluster) {
        this.jobs = new IgniteJobStore(vertx, cluster.ignite());
        this.state = new IgniteJobStateStore(vertx, cluster.ignite());
        this.services = new IgniteServiceStore(vertx, cluster.ignite());
        this.domains = new IgniteDomainStore(vertx, cluster.ignite());
        this.kv = new IgniteKvStore(vertx, cluster.ignite());
        this.auth = new IgniteAuthStore(vertx, cluster.ignite());
        this.storage = new IgniteStorageConfigStore(vertx, cluster.ignite());
        this.queues = new IgniteQueueStore(vertx, cluster.ignite());
        this.records = kind -> new IgniteRecords(vertx, cluster.ignite(), kind);
        this.audit = new IgniteAuditLog(vertx, cluster.ignite());
        this.apps = new IgniteAppStore(vertx, cluster.ignite());
        this.vertx = vertx;
        this.cluster = cluster;
    }

    /** File metadata in the cluster, bytes in {@code blobs} (D-056). The tenant quota is applied by callers (D-043). */
    public IgniteFileStore files(cluster93.core.files.BlobStore blobs) {
        return files(cluster93.core.files.BlobStores.single(blobs));
    }

    /** File metadata in the cluster, bytes routed per tenant (D-057). */
    public IgniteFileStore files(cluster93.core.files.BlobStores stores) {
        return new IgniteFileStore(vertx, cluster.ignite(), stores);
    }

    /** The default file backend: chunks in the cluster, with this node's storage admission control (D-038). */
    public cluster93.core.files.BlobStore chunks(StorageCapacity capacity) {
        return new IgniteChunks(vertx, cluster.ignite(), capacity);
    }

    /** Creates any missing tables. Blocking; call once at startup, before Vert.x serves traffic. */
    public static void createTables(IgniteCluster cluster) {
        TABLES.forEach(table -> {
            Table.create(cluster.ignite(), table, cluster.settings().backups());
            Table.registerSchema(cluster.ignite(), table);
        });
        IgniteChunks.createCache(cluster.ignite(), cluster.settings().backups());
    }

    public static IgniteStores open(Vertx vertx, IgniteCluster cluster) {
        return new IgniteStores(vertx, cluster);
    }
}
