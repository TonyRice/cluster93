package cluster93.core.storage;

import io.vertx.core.Future;
import cluster93.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;

/** {@link StorageConfigStore} on Vert.x shared maps, for a single in-memory node. */
public final class SharedDataStorageConfigStore implements StorageConfigStore {

    private static final char SEPARATOR = '\u001f';

    private final SharedMap<String, JsonObject> policies;
    private final SharedMap<String, JsonObject> providers;
    private final SharedMap<String, JsonObject> routes;

    public SharedDataStorageConfigStore(Vertx vertx) {
        this.policies = new SharedMap<>(vertx, "cluster93.storage-policies");
        this.providers = new SharedMap<>(vertx, "cluster93.storage-providers");
        this.routes = new SharedMap<>(vertx, "cluster93.storage-routes");
    }

    private static String key(String tenant, String name) {
        return tenant + SEPARATOR + name;
    }

    @Override
    public Future<StoragePolicy> policy(String tenant) {
        return policies.compose(m -> m.get(tenant)).map(json -> json == null ? StoragePolicy.none(tenant) : StoragePolicy.fromJson(json));
    }

    @Override
    public Future<Void> putPolicy(StoragePolicy policy) {
        return policies.compose(m -> m.put(policy.tenant(), policy.toJson()));
    }

    @Override
    public Future<StorageProvider> provider(String tenant, String name) {
        return providers.compose(m -> m.get(key(tenant, name))).map(StorageProvider::fromJson);
    }

    @Override
    public Future<StorageProvider> providerById(String id) {
        return providers.compose(AsyncMap::values).map(all -> all.stream().map(StorageProvider::fromJson)
                .filter(p -> p.id().equals(id)).findFirst().orElse(null));
    }

    @Override
    public Future<List<StorageProvider>> providers(String tenant) {
        return providers.compose(AsyncMap::values).map(all -> all.stream().map(StorageProvider::fromJson)
                .filter(p -> p.tenant().equals(tenant)).sorted(Comparator.comparing(StorageProvider::name)).toList());
    }

    @Override
    public Future<Void> putProvider(StorageProvider provider) {
        return providers.compose(m -> m.put(key(provider.tenant(), provider.name()), provider.toJson()));
    }

    @Override
    public Future<Boolean> deleteProvider(String tenant, String name) {
        return providers.compose(m -> m.remove(key(tenant, name))).map(removed -> removed != null);
    }

    @Override
    public Future<StorageRoutes> routes(String tenant) {
        return routes.compose(m -> m.get(tenant)).map(json -> json == null ? StorageRoutes.none(tenant) : StorageRoutes.fromJson(json));
    }

    @Override
    public Future<Void> putRoutes(StorageRoutes value) {
        return routes.compose(m -> m.put(value.tenant(), value.toJson()));
    }

    @Override
    public Future<Void> forget(String tenant) {
        return policies.compose(m -> m.remove(tenant))
                .compose(v -> routes.compose(m -> m.remove(tenant)))
                .compose(v -> providers(tenant))
                .compose(owned -> Future.all(owned.stream().map(p -> deleteProvider(tenant, p.name())).toList()))
                .mapEmpty();
    }
}
