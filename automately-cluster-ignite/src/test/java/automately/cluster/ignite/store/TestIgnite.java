package automately.cluster.ignite.store;

import automately.cluster.ignite.IgniteCluster;
import automately.cluster.ignite.IgniteSettings;
import io.vertx.core.Vertx;

import java.time.Duration;
import java.util.List;

/** One in-memory Ignite node and Vert.x shared by the contract tests in this JVM; tables are cleared between tests. */
final class TestIgnite {

    private static IgniteCluster cluster;
    private static Vertx vertx;
    private static IgniteStores stores;

    private TestIgnite() {
    }

    static IgniteCluster cluster() {
        stores();
        return cluster;
    }

    static Vertx vertx() {
        stores();
        return vertx;
    }

    /** Stops every Ignite a test started, but not the one shared by the contract tests. */
    static void stopAllExceptShared() {
        for (org.apache.ignite.Ignite ignite : org.apache.ignite.Ignition.allGrids()) {
            if (!ignite.name().equals("automately-contract")) {
                org.apache.ignite.Ignition.stop(ignite.name(), true);
            }
        }
    }

    static synchronized IgniteStores stores() {
        if (cluster == null) {
            cluster = IgniteCluster.start(new IgniteSettings("contract", null, 22500, List.of("127.0.0.1"), 0, 512, Duration.ofSeconds(1), 1));
            IgniteStores.createTables(cluster);
            vertx = Vertx.vertx();
            stores = IgniteStores.open(vertx, cluster);
        }
        for (Table.Definition table : List.of(IgniteJobStore.TABLE, IgniteJobStateStore.TABLE, IgniteServiceStore.TABLE,
                IgniteDomainStore.TABLE, IgniteKvStore.TABLE, IgniteFileStore.FILES, IgniteFileStore.GARBAGE,
                IgniteAuthStore.TENANTS, IgniteAuthStore.TOKENS, IgniteAuthStore.SETTINGS,
                IgniteStorageConfigStore.POLICIES, IgniteStorageConfigStore.PROVIDERS, IgniteStorageConfigStore.ROUTES,
                IgniteQueueStore.TABLE)) {
            cluster.ignite().cache(table.name()).clear();
        }
        cluster.ignite().cache(IgniteAuditLog.TABLE.name()).clear();
        automately.core.identity.Identity.KINDS.forEach(kind -> cluster.ignite().cache(kind.name()).clear());
        cluster.ignite().cache(automately.core.audit.AuditExporter.STATE.name()).clear();
        cluster.ignite().cache(IgniteChunks.CACHE).clear();
        return stores;
    }
}
