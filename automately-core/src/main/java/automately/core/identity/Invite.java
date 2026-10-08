package automately.core.identity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * An invitation to a tenant, or to become an operator (tenant {@link Identity#OPERATORS}), redeemed once with its code
 * (D-070). Stored by the code's hash; the code itself is shown once, when it's made.
 */
public record Invite(String id, String tenant, List<String> roles, List<String> groups, String user, String note, String createdBy,
                     long createdAt, long expiresAt) {

    public Invite {
        roles = List.copyOf(roles);
        groups = List.copyOf(groups);
    }

    boolean expired(long now) {
        return expiresAt <= now;
    }

    public JsonObject toJson() {
        return new JsonObject().put("id", id).put("tenant", tenant).put("roles", new JsonArray(roles)).put("groups", new JsonArray(groups))
                .put("user", user).put("note", note).put("createdBy", createdBy).put("createdAt", createdAt).put("expiresAt", expiresAt);
    }

    public static Invite fromJson(JsonObject json) {
        return new Invite(json.getString("id"), json.getString("tenant"), Member.strings(json.getJsonArray("roles")),
                Member.strings(json.getJsonArray("groups")), json.getString("user"), json.getString("note"), json.getString("createdBy"),
                json.getLong("createdAt", 0L), json.getLong("expiresAt", 0L));
    }
}
