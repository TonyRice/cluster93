package automately.cluster.ignite.store;

import automately.cluster.ignite.IgniteCluster;
import automately.core.host.DomainStore;
import automately.core.files.FileStore;
import automately.core.files.StorageCapacity;
import automately.core.host.KvStore;
import automately.core.job.JobStore;
import automately.core.service.ServiceStore;
import automately.core.state.JobStateStore;
import io.vertx.core.Vertx;

import java.util.List;

/** Automately's durable tables on Ignite (D-030). */
public final class IgniteStores {

    private static final List<Table.Definition> TABLES = java.util.stream.Stream.concat(java.util.stream.Stream.of(
            IgniteJobStore.TABLE, IgniteJobStateStore.TABLE, IgniteServiceStore.TABLE, IgniteDomainStore.TABLE, IgniteKvStore.TABLE,
            IgniteFileStore.FILES, IgniteFileStore.GARBAGE, IgniteFileStore.DIRS, IgniteAuthStore.TENANTS, IgniteAuthStore.TOKENS, IgniteAuthStore.SETTINGS,
            IgniteStorageConfigStore.POLICIES, IgniteStorageConfigStore.PROVIDERS, IgniteStorageConfigStore.ROUTES,
            IgniteQueueStore.TABLE, IgniteAuditLog.TABLE),
            java.util.stream.Stream.concat(automately.core.identity.Identity.KINDS.stream(),
                    java.util.stream.Stream.of(automately.core.audit.AuditExporter.STATE)).map(IgniteRecords::definition)).toList();

    public final JobStore jobs;
    public final JobStateStore state;
    public final ServiceStore services;
    public final DomainStore domains;
    public final KvStore kv;
    public final automately.core.auth.AuthStore auth;
    public final automately.core.storage.StorageConfigStore storage;
    public final automately.core.queue.QueueStore queues;
    /** Users, memberships, roles and groups, and invites (D-070). */
    public final automately.core.identity.Records.Factory records;
    public final automately.core.audit.AuditLog audit;
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
        this.vertx = vertx;
        this.cluster = cluster;
    }

    /** File metadata in the cluster, bytes in {@code blobs} (D-056). The tenant quota is applied by callers (D-043). */
    public IgniteFileStore files(automately.core.files.BlobStore blobs) {
        return files(automately.core.files.BlobStores.single(blobs));
    }

    /** File metadata in the cluster, bytes routed per tenant (D-057). */
    public IgniteFileStore files(automately.core.files.BlobStores stores) {
        return new IgniteFileStore(vertx, cluster.ignite(), stores);
    }

    /** The default file backend: chunks in the cluster, with this node's storage admission control (D-038). */
    public automately.core.files.BlobStore chunks(StorageCapacity capacity) {
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
