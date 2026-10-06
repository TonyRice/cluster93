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

    static synchronized IgniteStores stores() {
        if (cluster == null) {
            cluster = IgniteCluster.start(new IgniteSettings("contract", null, 22500, List.of("127.0.0.1"), 0, 64, Duration.ofSeconds(1), 1));
            IgniteStores.createTables(cluster);
            vertx = Vertx.vertx();
            stores = IgniteStores.open(vertx, cluster);
        }
        for (Table.Definition table : List.of(IgniteJobStore.TABLE, IgniteJobStateStore.TABLE, IgniteServiceStore.TABLE,
                IgniteDomainStore.TABLE, IgniteKvStore.TABLE)) {
            cluster.ignite().cache(table.name()).clear();
        }
        return stores;
    }
}
