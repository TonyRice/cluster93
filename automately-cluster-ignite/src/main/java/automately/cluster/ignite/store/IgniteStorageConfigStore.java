package automately.cluster.ignite.store;

import automately.core.storage.StorageConfigStore;
import automately.core.storage.StoragePolicy;
import automately.core.storage.StorageProvider;
import automately.core.storage.StorageRoutes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.ignite.Ignite;

import java.util.List;
import java.util.Map;

/** {@link StorageConfigStore} in three durable tables: policies and routes by tenant, providers by tenant and name. */
public final class IgniteStorageConfigStore implements StorageConfigStore {

    static final Table.Definition POLICIES = new Table.Definition("STORAGE_POLICIES", Table.columns("tenant", String.class), List.of());
    static final Table.Definition PROVIDERS = new Table.Definition("STORAGE_PROVIDERS",
            Table.columns("tenant", String.class, "id", String.class), List.of(Table.index("tenant"), Table.index("id")));
    static final Table.Definition ROUTES = new Table.Definition("STORAGE_ROUTES", Table.columns("tenant", String.class), List.of());
    private static final char SEPARATOR = '\u001f';

    private final Table policies;
    private final Table providers;
    private final Table routes;

    public IgniteStorageConfigStore(Vertx vertx, Ignite ignite) {
        this.policies = new Table(vertx, ignite, POLICIES, json -> Map.of("tenant", json.getString("tenant")));
        this.providers = new Table(vertx, ignite, PROVIDERS, json -> Map.of("tenant", json.getString("tenant"), "id", json.getString("id")));
        this.routes = new Table(vertx, ignite, ROUTES, json -> Map.of("tenant", json.getString("tenant")));
    }

    private static String key(String tenant, String name) {
        return tenant + SEPARATOR + name;
    }

    @Override
    public Future<StoragePolicy> policy(String tenant) {
        return policies.get(tenant).map(json -> json == null ? StoragePolicy.none(tenant) : StoragePolicy.fromJson(json));
    }

    @Override
    public Future<Void> putPolicy(StoragePolicy policy) {
        return policies.put(policy.tenant(), policy.toJson());
    }

    @Override
    public Future<StorageProvider> provider(String tenant, String name) {
        return providers.get(key(tenant, name)).map(StorageProvider::fromJson);
    }

    @Override
    public Future<StorageProvider> providerById(String id) {
        return providers.queryJson("SELECT json FROM STORAGE_PROVIDERS WHERE id = ?", id)
                .map(rows -> rows.isEmpty() ? null : StorageProvider.fromJson(rows.getFirst()));
    }

    @Override
    public Future<List<StorageProvider>> providers(String tenant) {
        return providers.queryJson("SELECT json FROM STORAGE_PROVIDERS WHERE tenant = ? ORDER BY _KEY", tenant)
                .map(rows -> rows.stream().map(StorageProvider::fromJson).toList());
    }

    @Override
    public Future<Void> putProvider(StorageProvider provider) {
        return providers.put(key(provider.tenant(), provider.name()), provider.toJson());
    }

    @Override
    public Future<Boolean> deleteProvider(String tenant, String name) {
        return providers.remove(key(tenant, name)).map(removed -> removed != null);
    }

    @Override
    public Future<StorageRoutes> routes(String tenant) {
        return routes.get(tenant).map(json -> json == null ? StorageRoutes.none(tenant) : StorageRoutes.fromJson(json));
    }

    @Override
    public Future<Void> putRoutes(StorageRoutes value) {
        return routes.put(value.tenant(), value.toJson());
    }

    @Override
    public Future<Void> forget(String tenant) {
        return policies.remove(tenant).compose(v -> routes.remove(tenant)).compose(v -> providers(tenant))
                .compose(owned -> Future.all(owned.stream().map(p -> providers.remove(key(tenant, p.name()))).toList()))
                .mapEmpty();
    }
}
