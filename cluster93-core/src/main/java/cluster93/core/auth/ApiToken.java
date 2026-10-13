package cluster93.core.auth;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A token as stored: never the secret, only its {@link TokenFormat#hash} (D-045). A <em>personal</em> token has a
 * {@code user}, and what it may do is worked out from that user's roles on every request (D-070); a <em>service</em>
 * token has none and keeps its own scopes. {@code createdBy} says who made it.
 */
public record ApiToken(String id, String tenant, String name, Set<String> scopes, String hash, long createdAt, Long expiresAt,
                       Long lastUsedAt, String user, String createdBy) {

    public ApiToken(String id, String tenant, String name, Set<String> scopes, String hash, long createdAt, Long expiresAt, Long lastUsedAt) {
        this(id, tenant, name, scopes, hash, createdAt, expiresAt, lastUsedAt, null, null);
    }

    public boolean expired(long now) {
        return expiresAt != null && expiresAt <= now;
    }

    public ApiToken usedAt(long now) {
        return new ApiToken(id, tenant, name, scopes, hash, createdAt, expiresAt, now, user, createdBy);
    }

    public JsonObject toJson() {
        return toViewJson().put("hash", hash);
    }

    /** What the API shows: everything but the hash. */
    public JsonObject toViewJson() {
        return new JsonObject().put("id", id).put("tenant", tenant).put("name", name)
                .put("scopes", new JsonArray(List.copyOf(new TreeSet<>(scopes))))
                .put("createdAt", createdAt).put("expiresAt", expiresAt).put("lastUsedAt", lastUsedAt)
                .put("user", user).put("createdBy", createdBy);
    }

    public static ApiToken fromJson(JsonObject json) {
        return new ApiToken(json.getString("id"), json.getString("tenant"), json.getString("name"),
                Set.copyOf(json.getJsonArray("scopes").getList().stream().map(String::valueOf).toList()), json.getString("hash"),
                json.getLong("createdAt"), json.getLong("expiresAt"), json.getLong("lastUsedAt"), json.getString("user"),
                json.getString("createdBy"));
    }
}
