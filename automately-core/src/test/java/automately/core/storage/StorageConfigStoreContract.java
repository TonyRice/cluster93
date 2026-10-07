package automately.core.storage;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Behaviour every {@link StorageConfigStore} must have; each implementation's test extends this. */
public abstract class StorageConfigStoreContract {

    /** Set by the implementation's test before each test. */
    protected StorageConfigStore store;

    private static StorageProvider provider(String id, String tenant, String name) {
        return new StorageProvider(id, tenant, name, "s3", new JsonObject().put("bucket", "b"), "sealed", 1, 1);
    }

    @Test
    public void policiesDefaultToNothingAllowed() throws Exception {
        assertThat(await(store.policy("acme"))).isEqualTo(StoragePolicy.none("acme"));
        StoragePolicy policy = new StoragePolicy("acme", Set.of("s3", "disk"), 10L << 20, true);
        await(store.putPolicy(policy));
        assertThat(await(store.policy("acme"))).isEqualTo(policy);
    }

    @Test
    public void providersByNameAndIdPerTenant() throws Exception {
        await(store.putProvider(provider("prv_1", "acme", "media")));
        await(store.putProvider(provider("prv_2", "acme", "backups")));
        await(store.putProvider(provider("prv_3", "globex", "media")));

        assertThat(await(store.provider("acme", "media")).id()).isEqualTo("prv_1");
        assertThat(await(store.providerById("prv_3")).tenant()).isEqualTo("globex");
        assertThat(await(store.providers("acme"))).extracting(StorageProvider::name).containsExactly("backups", "media");
        assertThat(await(store.provider("acme", "nope"))).isNull();
        assertThat(await(store.deleteProvider("acme", "media"))).isTrue();
        assertThat(await(store.deleteProvider("acme", "media"))).isFalse();
        assertThat(await(store.providerById("prv_1"))).isNull();
    }

    @Test
    public void routesAndForgetting() throws Exception {
        assertThat(await(store.routes("acme"))).isEqualTo(StorageRoutes.none("acme"));
        StorageRoutes routes = new StorageRoutes("acme", "media", Map.of("cache/", StorageRoutes.DEFAULT, "videos/", "media"));
        await(store.putRoutes(routes));
        await(store.putPolicy(new StoragePolicy("acme", Set.of("s3"), 0, false)));
        await(store.putProvider(provider("prv_9", "acme", "media")));
        assertThat(await(store.routes("acme"))).isEqualTo(routes);

        await(store.forget("acme"));
        assertThat(await(store.routes("acme"))).isEqualTo(StorageRoutes.none("acme"));
        assertThat(await(store.policy("acme"))).isEqualTo(StoragePolicy.none("acme"));
        assertThat(await(store.providers("acme"))).isEmpty();
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
