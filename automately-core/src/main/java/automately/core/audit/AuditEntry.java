package automately.core.audit;

import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * One change someone made, or tried to (D-071): when, in which tenant (null for the cluster), who (user name and id,
 * token, and whether as an operator or the admin token), the API operation, its path without the query, the answer's
 * status, and where the request came from. {@code id} sorts by time, so it's also the paging cursor.
 *
 * <p>Entries are chained per node (D-072): {@code chain} names the node's chain, {@code seq} counts within it,
 * {@code prev} is the previous entry's {@code hash}, and {@code hash} covers everything else, keyed when the cluster has
 * a secrets key. Null until {@link AuditChain#seal} sets them.
 */
public record AuditEntry(String id, long at, String tenant, String user, String userId, String token, boolean operator, String action,
                         String method, String path, int status, String ip, String forwardedFor,
                         String chain, long seq, String prev, String hash) {

    /** An entry not yet chained. */
    public static AuditEntry of(long at, SecureRandom random, String tenant, String user, String userId, String token, boolean operator,
                                String action, String method, String path, int status, String ip, String forwardedFor) {
        return new AuditEntry(newId(at, random), at, tenant, user, userId, token, operator, action, method, path, status, ip, forwardedFor,
                null, 0, null, null);
    }

    /** A new entry's id: the time, zero-padded so ids sort by it, then random to keep them apart. */
    public static String newId(long at, SecureRandom random) {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return String.format("%013d-%s", at, HexFormat.of().formatHex(bytes));
    }

    AuditEntry chained(String chainId, long number, String previous, String digest) {
        return new AuditEntry(id, at, tenant, user, userId, token, operator, action, method, path, status, ip, forwardedFor, chainId, number,
                previous, digest);
    }

    /** Everything the hash covers: the whole entry, chain position included, but not the hash itself. */
    JsonObject unhashed() {
        JsonObject json = toJson();
        json.remove("hash");
        return json;
    }

    public JsonObject toJson() {
        return new JsonObject().put("id", id).put("at", at).put("tenant", tenant)
                .put("actor", new JsonObject().put("user", user).put("userId", userId).put("token", token).put("operator", operator))
                .put("action", action).put("method", method).put("path", path).put("status", status).put("ip", ip).put("forwardedFor", forwardedFor)
                .put("chain", chain).put("seq", seq).put("prev", prev).put("hash", hash);
    }

    public static AuditEntry fromJson(JsonObject json) {
        JsonObject actor = json.getJsonObject("actor", new JsonObject());
        return new AuditEntry(json.getString("id"), json.getLong("at", 0L), json.getString("tenant"), actor.getString("user"),
                actor.getString("userId"), actor.getString("token"), actor.getBoolean("operator", false), json.getString("action"),
                json.getString("method"), json.getString("path"), json.getInteger("status", 0), json.getString("ip"), json.getString("forwardedFor"),
                json.getString("chain"), json.getLong("seq", 0L), json.getString("prev"), json.getString("hash"));
    }
}
