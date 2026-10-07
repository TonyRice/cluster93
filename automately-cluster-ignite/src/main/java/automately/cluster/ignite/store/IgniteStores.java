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

    private static final List<Table.Definition> TABLES = List.of(
            IgniteJobStore.TABLE, IgniteJobStateStore.TABLE, IgniteServiceStore.TABLE, IgniteDomainStore.TABLE, IgniteKvStore.TABLE,
            IgniteFileStore.FILES, IgniteFileStore.GARBAGE);

    public final JobStore jobs;
    public final JobStateStore state;
    public final ServiceStore services;
    public final DomainStore domains;
    public final KvStore kv;
    private final Vertx vertx;
    private final IgniteCluster cluster;

    private IgniteStores(Vertx vertx, IgniteCluster cluster) {
        this.jobs = new IgniteJobStore(vertx, cluster.ignite());
        this.state = new IgniteJobStateStore(vertx, cluster.ignite());
        this.services = new IgniteServiceStore(vertx, cluster.ignite());
        this.domains = new IgniteDomainStore(vertx, cluster.ignite());
        this.kv = new IgniteKvStore(vertx, cluster.ignite());
        this.vertx = vertx;
        this.cluster = cluster;
    }

    /** Files, with this node's storage admission control (D-038). The tenant quota is applied by callers (D-043). */
    public FileStore files(StorageCapacity capacity) {
        return new IgniteFileStore(vertx, cluster.ignite(), capacity);
    }

    /** Creates any missing tables. Blocking; call once at startup, before Vert.x serves traffic. */
    public static void createTables(IgniteCluster cluster) {
        TABLES.forEach(table -> {
            Table.create(cluster.ignite(), table, cluster.settings().backups());
            Table.registerSchema(cluster.ignite(), table);
        });
        IgniteFileStore.createChunkCache(cluster.ignite(), cluster.settings().backups());
    }

    public static IgniteStores open(Vertx vertx, IgniteCluster cluster) {
        return new IgniteStores(vertx, cluster);
    }
}
