package cluster93.cluster.ignite.store;

import cluster93.core.auth.ApiToken;
import cluster93.core.auth.AuthStore;
import cluster93.core.auth.Tenant;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link AuthStore} in three durable tables: tenants, tokens (keyed by hash, indexed by tenant and id), and settings. */
public final class IgniteAuthStore implements AuthStore {

    static final Table.Definition TENANTS = new Table.Definition("TENANTS", Table.columns("createdAt", Long.class), List.of());
    static final Table.Definition TOKENS = new Table.Definition("TOKENS",
            Table.columns("tenant", String.class, "id", String.class), List.of(Table.index("tenant"), Table.index("id")));
    static final Table.Definition SETTINGS = new Table.Definition("SETTINGS", Table.columns("updatedAt", Long.class), List.of());
    private static final String ADMIN = "admin-token-hash";

    private final Table tenants;
    private final Table tokens;
    private final Table settings;

    public IgniteAuthStore(Vertx vertx, Ignite ignite) {
        this.tenants = new Table(vertx, ignite, TENANTS, json -> Map.of("createdAt", json.getLong("createdAt")));
        this.tokens = new Table(vertx, ignite, TOKENS, json -> Map.of("tenant", json.getString("tenant"), "id", json.getString("id")));
        this.settings = new Table(vertx, ignite, SETTINGS, json -> Map.of("updatedAt", json.getLong("updatedAt")));
    }

    @Override
    public Future<Boolean> createTenant(Tenant tenant) {
        return tenants.putIfAbsent(tenant.name(), tenant.toJson());
    }

    @Override
    public Future<Tenant> tenant(String name) {
        return tenants.get(name).map(json -> json == null ? null : Tenant.fromJson(json));
    }

    @Override
    public Future<List<Tenant>> tenants() {
        return tenants.queryJson("SELECT json FROM TENANTS ORDER BY _KEY").map(rows -> rows.stream().map(Tenant::fromJson).toList());
    }

    @Override
    public Future<Boolean> deleteTenant(String name) {
        return tenants.remove(name).compose(removed -> tokens(name)
                .compose(owned -> Future.all(owned.stream().map(t -> tokens.remove(t.hash())).toList()))
                .map(v -> removed != null));
    }

    @Override
    public Future<Void> saveToken(ApiToken token) {
        return tokens.put(token.hash(), token.toJson());
    }

    @Override
    public Future<ApiToken> tokenByHash(String hash) {
        return tokens.get(hash).map(json -> json == null ? null : ApiToken.fromJson(json));
    }

    @Override
    public Future<List<ApiToken>> tokens(String tenant) {
        return tokens.queryJson("SELECT json FROM TOKENS WHERE tenant = ?", tenant).map(rows -> rows.stream().map(ApiToken::fromJson)
                .sorted(java.util.Comparator.comparingLong(ApiToken::createdAt).thenComparing(ApiToken::id)).toList());
    }

    @Override
    public Future<Boolean> revokeToken(String tenant, String id) {
        return tokens.queryJson("SELECT json FROM TOKENS WHERE tenant = ? AND id = ?", tenant, id).compose(rows -> rows.isEmpty()
                ? Future.succeededFuture(false)
                : tokens.remove(ApiToken.fromJson(rows.getFirst()).hash()).map(removed -> removed != null));
    }

    @Override
    public Future<Void> touchToken(String hash, long now) {
        return tokens.update(hash, json -> Optional.of(ApiToken.fromJson(json).usedAt(now).toJson())).mapEmpty();
    }

    @Override
    public Future<String> adminHash() {
        return settings.get(ADMIN).map(json -> json == null ? null : json.getString("value"));
    }

    @Override
    public Future<String> adminHashIfAbsent(String hash) {
        return settings.putIfAbsent(ADMIN, setting(hash)).compose(created -> adminHash());
    }

    @Override
    public Future<Void> replaceAdminHash(String hash) {
        return settings.put(ADMIN, setting(hash));
    }

    private static JsonObject setting(String value) {
        return new JsonObject().put("value", value).put("updatedAt", System.currentTimeMillis());
    }
}
