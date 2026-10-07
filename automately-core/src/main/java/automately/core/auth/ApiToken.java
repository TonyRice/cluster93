package automately.core.auth;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** A tenant's token as stored: never the secret, only its {@link TokenFormat#hash} (D-045). */
public record ApiToken(String id, String tenant, String name, Set<String> scopes, String hash, long createdAt, Long expiresAt,
                       Long lastUsedAt) {

    public boolean expired(long now) {
        return expiresAt != null && expiresAt <= now;
    }

    public ApiToken usedAt(long now) {
        return new ApiToken(id, tenant, name, scopes, hash, createdAt, expiresAt, now);
    }

    public JsonObject toJson() {
        return toViewJson().put("hash", hash);
    }

    /** What the API shows: everything but the hash. */
    public JsonObject toViewJson() {
        return new JsonObject().put("id", id).put("tenant", tenant).put("name", name)
                .put("scopes", new JsonArray(List.copyOf(new TreeSet<>(scopes))))
                .put("createdAt", createdAt).put("expiresAt", expiresAt).put("lastUsedAt", lastUsedAt);
    }

    public static ApiToken fromJson(JsonObject json) {
        return new ApiToken(json.getString("id"), json.getString("tenant"), json.getString("name"),
                Set.copyOf(json.getJsonArray("scopes").getList().stream().map(String::valueOf).toList()), json.getString("hash"),
                json.getLong("createdAt"), json.getLong("expiresAt"), json.getLong("lastUsedAt"));
    }
}
