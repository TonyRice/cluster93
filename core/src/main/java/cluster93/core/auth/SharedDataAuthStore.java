package cluster93.core.auth;

import io.vertx.core.Future;
import cluster93.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** {@link AuthStore} on Vert.x shared maps, for a single in-memory node. Listing scans, which is fine at that size. */
public final class SharedDataAuthStore implements AuthStore {

    private static final String ADMIN = "admin-token-hash";

    private final SharedMap<String, JsonObject> tenants;
    private final SharedMap<String, JsonObject> tokens;
    private final SharedMap<String, String> settings;

    public SharedDataAuthStore(Vertx vertx) {
        this.tenants = new SharedMap<>(vertx, "cluster93.tenants");
        this.tokens = new SharedMap<>(vertx, "cluster93.tokens");
        this.settings = new SharedMap<>(vertx, "cluster93.settings");
    }

    @Override
    public Future<Boolean> createTenant(Tenant tenant) {
        return tenants.compose(m -> m.putIfAbsent(tenant.name(), tenant.toJson())).map(previous -> previous == null);
    }

    @Override
    public Future<Tenant> tenant(String name) {
        return tenants.compose(m -> m.get(name)).map(json -> json == null ? null : Tenant.fromJson(json));
    }

    @Override
    public Future<List<Tenant>> tenants() {
        return tenants.compose(AsyncMap::values).map(all -> all.stream().map(Tenant::fromJson).sorted(Comparator.comparing(Tenant::name)).toList());
    }

    @Override
    public Future<Boolean> deleteTenant(String name) {
        return tenants.compose(m -> m.remove(name)).compose(removed -> tokens.compose(AsyncMap::entries).compose(all -> {
            List<String> hashes = all.entrySet().stream().filter(e -> name.equals(e.getValue().getString("tenant"))).map(Map.Entry::getKey).toList();
            return Future.all(hashes.stream().map(hash -> tokens.compose(m -> m.remove(hash))).toList());
        }).map(v -> removed != null));
    }

    @Override
    public Future<Void> saveToken(ApiToken token) {
        return tokens.compose(m -> m.put(token.hash(), token.toJson()));
    }

    @Override
    public Future<ApiToken> tokenByHash(String hash) {
        return tokens.compose(m -> m.get(hash)).map(json -> json == null ? null : ApiToken.fromJson(json));
    }

    @Override
    public Future<List<ApiToken>> tokens(String tenant) {
        return tokens.compose(AsyncMap::values).map(all -> all.stream().map(ApiToken::fromJson).filter(t -> t.tenant().equals(tenant))
                .sorted(Comparator.comparingLong(ApiToken::createdAt).thenComparing(ApiToken::id)).toList());
    }

    @Override
    public Future<Boolean> revokeToken(String tenant, String id) {
        return tokens(tenant).compose(owned -> owned.stream().filter(t -> t.id().equals(id)).findFirst()
                .map(t -> tokens.compose(m -> m.remove(t.hash())).map(removed -> removed != null))
                .orElse(Future.succeededFuture(false)));
    }

    @Override
    public Future<Void> touchToken(String hash, long now) {
        return tokens.compose(m -> m.get(hash).compose(json -> json == null
                ? Future.succeededFuture()
                : m.replaceIfPresent(hash, json, ApiToken.fromJson(json).usedAt(now).toJson()).mapEmpty()));
    }

    @Override
    public Future<String> adminHash() {
        return settings.compose(m -> m.get(ADMIN));
    }

    @Override
    public Future<String> adminHashIfAbsent(String hash) {
        return settings.compose(m -> m.putIfAbsent(ADMIN, hash)).map(existing -> existing == null ? hash : existing);
    }

    @Override
    public Future<Void> replaceAdminHash(String hash) {
        return settings.compose(m -> m.put(ADMIN, hash));
    }
}
