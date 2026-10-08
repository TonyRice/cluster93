package automately.core.audit;

import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * One change someone made, or tried to (D-071): when, in which tenant (null for the cluster), who (user, token, and
 * whether as an operator or the admin token), the API operation, its path without the query, the answer's status, and
 * where the request came from. {@code id} sorts by time, so it's also the paging cursor.
 */
public record AuditEntry(String id, long at, String tenant, String user, String token, boolean operator, String action, String method,
                         String path, int status, String ip, String forwardedFor) {

    /** A new entry's id: the time, zero-padded so ids sort by it, then random to keep them apart. */
    public static String newId(long at, SecureRandom random) {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return String.format("%013d-%s", at, HexFormat.of().formatHex(bytes));
    }

    public JsonObject toJson() {
        return new JsonObject().put("id", id).put("at", at).put("tenant", tenant)
                .put("actor", new JsonObject().put("user", user).put("token", token).put("operator", operator))
                .put("action", action).put("method", method).put("path", path).put("status", status).put("ip", ip).put("forwardedFor", forwardedFor);
    }

    public static AuditEntry fromJson(JsonObject json) {
        JsonObject actor = json.getJsonObject("actor", new JsonObject());
        return new AuditEntry(json.getString("id"), json.getLong("at", 0L), json.getString("tenant"), actor.getString("user"),
                actor.getString("token"), actor.getBoolean("operator", false), json.getString("action"), json.getString("method"),
                json.getString("path"), json.getInteger("status", 0), json.getString("ip"), json.getString("forwardedFor"));
    }
}
