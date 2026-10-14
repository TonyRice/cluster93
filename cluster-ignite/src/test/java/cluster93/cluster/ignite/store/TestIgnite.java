package cluster93.cluster.ignite.store;

import cluster93.cluster.ignite.IgniteCluster;
import cluster93.cluster.ignite.IgniteSettings;
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
            if (!ignite.name().equals("cluster93-contract")) {
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
                IgniteDomainStore.TABLE, IgniteKvStore.TABLE, IgniteFileStore.FILES, IgniteFileStore.GARBAGE, IgniteFileStore.DIRS, IgniteFileStore.REFS,
                IgniteAuthStore.TENANTS, IgniteAuthStore.TOKENS, IgniteAuthStore.SETTINGS,
                IgniteStorageConfigStore.POLICIES, IgniteStorageConfigStore.PROVIDERS, IgniteStorageConfigStore.ROUTES,
                IgniteQueueStore.TABLE, IgniteAppStore.APPS, IgniteAppStore.APP_DEPLOYMENTS)) {
            cluster.ignite().cache(table.name()).clear();
        }
        cluster.ignite().cache(IgniteAuditLog.TABLE.name()).clear();
        cluster93.core.identity.Identity.KINDS.forEach(kind -> cluster.ignite().cache(kind.name()).clear());
        cluster.ignite().cache(cluster93.core.audit.AuditExporter.STATE.name()).clear();
        cluster.ignite().cache(cluster93.core.files.FileTree.OPS.name()).clear();
        cluster.ignite().cache(cluster93.core.files.FileLocks.KIND.name()).clear();
        cluster.ignite().cache(cluster93.core.packages.PackageStore.VERSIONS.name()).clear();
        cluster.ignite().cache(cluster93.core.packages.PackageStore.BLOBS.name()).clear();
        cluster.ignite().cache(IgniteChunks.CACHE).clear();
        return stores;
    }
}
