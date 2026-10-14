package cluster93.cluster.ignite.store;

import cluster93.core.apps.AppDeployment;
import cluster93.core.apps.AppManifest;
import cluster93.core.apps.AppRecord;
import cluster93.core.apps.AppStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link IgniteAppStore} deployment registration, promotion, rollback and deletion, matching the in-memory store. */
class IgniteAppStoreTest {

    private AppStore store;

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().apps;
    }

    private static AppDeployment deployment(String tenant, String app, String id, long createdAt) {
        AppManifest manifest = AppManifest.parse(new JsonObject().put("name", app));
        return new AppDeployment(tenant, app, id, createdAt, "commit-" + id, manifest, null, null);
    }

    @Test
    void savesAndListsDeployments() throws Exception {
        AppDeployment first = deployment("acme", "shop", "dpl_a", 100);
        AppDeployment second = deployment("acme", "shop", "dpl_b", 200);
        AppDeployment other = deployment("acme", "site", "dpl_c", 150);

        assertThat(await(store.saveDeployment(second))).isEqualTo(second);
        assertThat(await(store.saveDeployment(first))).isEqualTo(first);
        await(store.saveDeployment(other));

        assertThat(await(store.getDeployment("acme", "shop", "dpl_a"))).isEqualTo(first);
        assertThat(await(store.getDeployment("acme", "shop", "missing"))).isNull();
        assertThat(await(store.listDeployments("acme", "shop"))).containsExactly(first, second);

        // The VFS prefix is derived when it isn't passed in.
        assertThat(first.vfsPrefix()).isEqualTo("/.apps/shop/deployments/dpl_a");
    }

    @Test
    void savingTheSameDeploymentIsIdempotentAndDifferentContentIsRejected() throws Exception {
        AppDeployment first = deployment("acme", "shop", "dpl_a", 100);
        await(store.saveDeployment(first));

        assertThat(await(store.saveDeployment(first))).isEqualTo(first);

        AppDeployment conflicting = deployment("acme", "shop", "dpl_a", 999);
        assertThat(cause(store.saveDeployment(conflicting))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promotionCreatesTheAppAndTracksHistory() throws Exception {
        await(store.saveDeployment(deployment("acme", "shop", "dpl_a", 100)));
        await(store.saveDeployment(deployment("acme", "shop", "dpl_b", 200)));

        AppRecord afterFirst = await(store.promote("acme", "shop", "dpl_a"));
        assertThat(afterFirst.activeDeploymentId()).isEqualTo("dpl_a");
        assertThat(afterFirst.deploymentHistory()).containsExactly("dpl_a");

        AppRecord afterSecond = await(store.promote("acme", "shop", "dpl_b"));
        assertThat(afterSecond.activeDeploymentId()).isEqualTo("dpl_b");
        assertThat(afterSecond.deploymentHistory()).containsExactly("dpl_a", "dpl_b");
        assertThat(await(store.getApp("acme", "shop"))).isEqualTo(afterSecond);
    }

    @Test
    void promotingTheActiveDeploymentIsANoOp() throws Exception {
        await(store.saveDeployment(deployment("acme", "shop", "dpl_a", 100)));
        await(store.promote("acme", "shop", "dpl_a"));

        AppRecord again = await(store.promote("acme", "shop", "dpl_a"));
        assertThat(again.deploymentHistory()).containsExactly("dpl_a");
    }

    @Test
    void promotingAnUnknownDeploymentFails() {
        assertThat(cause(store.promote("acme", "shop", "dpl_missing")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rollbackPromotesThePreviousDeployment() throws Exception {
        for (String id : List.of("dpl_a", "dpl_b", "dpl_c")) {
            await(store.saveDeployment(deployment("acme", "shop", id, 100)));
        }
        await(store.promote("acme", "shop", "dpl_a"));
        await(store.promote("acme", "shop", "dpl_b"));
        await(store.promote("acme", "shop", "dpl_c"));

        AppRecord back = await(store.rollback("acme", "shop"));
        assertThat(back.activeDeploymentId()).isEqualTo("dpl_b");
        assertThat(back.deploymentHistory()).containsExactly("dpl_a", "dpl_b");

        AppRecord further = await(store.rollback("acme", "shop"));
        assertThat(further.activeDeploymentId()).isEqualTo("dpl_a");
        assertThat(further.deploymentHistory()).containsExactly("dpl_a");
    }

    @Test
    void rollbackWithNothingEarlierFails() throws Exception {
        await(store.saveDeployment(deployment("acme", "shop", "dpl_a", 100)));
        await(store.promote("acme", "shop", "dpl_a"));

        assertThat(cause(store.rollback("acme", "shop"))).isInstanceOf(IllegalStateException.class);
        assertThat(cause(store.rollback("acme", "missing"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listsAppsByTenantInNameOrder() throws Exception {
        for (String app : List.of("zeta", "alpha", "mid")) {
            await(store.saveDeployment(deployment("acme", app, "dpl_1", 100)));
            await(store.promote("acme", app, "dpl_1"));
        }
        await(store.saveDeployment(deployment("globex", "web", "dpl_1", 100)));
        await(store.promote("globex", "web", "dpl_1"));

        assertThat(await(store.listApps("acme"))).extracting(AppRecord::name).containsExactly("alpha", "mid", "zeta");
        assertThat(await(store.listApps("globex"))).extracting(AppRecord::name).containsExactly("web");
    }

    @Test
    void deletesTheAppAndItsDeployments() throws Exception {
        await(store.saveDeployment(deployment("acme", "shop", "dpl_a", 100)));
        await(store.saveDeployment(deployment("acme", "shop", "dpl_b", 200)));
        await(store.promote("acme", "shop", "dpl_a"));

        assertThat(await(store.deleteApp("acme", "shop"))).isTrue();
        assertThat(await(store.getApp("acme", "shop"))).isNull();
        assertThat(await(store.listDeployments("acme", "shop"))).isEmpty();
        assertThat(await(store.deleteApp("acme", "shop"))).isFalse();
    }

    @Test
    void concurrentPromotionsLoseNothing() throws Exception {
        List<Future<AppRecord>> promotions = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = "dpl_" + i;
            await(store.saveDeployment(deployment("acme", "shop", id, i)));
            promotions.add(store.promote("acme", "shop", id));
        }
        await(Future.all(promotions));

        AppRecord record = await(store.getApp("acme", "shop"));
        assertThat(record.deploymentHistory()).hasSize(30).doesNotHaveDuplicates();
        assertThat(record.deploymentHistory()).contains(record.activeDeploymentId());
        assertThat(record.deploymentHistory().get(record.deploymentHistory().size() - 1))
                .isEqualTo(record.activeDeploymentId());
    }

    private static Throwable cause(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            throw new AssertionError("Expected the future to fail");
        } catch (Exception e) {
            Throwable current = e;
            while (current.getCause() != null && current != current.getCause()) {
                current = current.getCause();
            }
            return current;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
