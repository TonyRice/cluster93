package cluster93.core.host;

import io.vertx.core.json.JsonObject;

/**
 * A custom domain claimed for a service. It routes only once verified: the owner publishes
 * {@link #txtValue()} in a TXT record at {@link #txtName()} (the 2017 anti-hijacking check).
 *
 * @param port the server port it reaches (D-123), or null for the service's main server
 */
public record Domain(String domain, String tenant, String service, String token, boolean verified, long createdAt, Long verifiedAt,
                     Integer port) {

    public Domain(String domain, String tenant, String service, String token, boolean verified, long createdAt, Long verifiedAt) {
        this(domain, tenant, service, token, verified, createdAt, verifiedAt, null);
    }

    public String txtName() {
        return "_cluster93." + domain;
    }

    public String txtValue() {
        return "cluster93-verify=" + token;
    }

    public Domain asVerified(long now) {
        return new Domain(domain, tenant, service, token, true, createdAt, now, port);
    }

    public JsonObject toJson() {
        return new JsonObject().put("domain", domain).put("tenant", tenant).put("service", service).put("token", token)
                .put("verified", verified).put("createdAt", createdAt).put("verifiedAt", verifiedAt).put("port", port);
    }

    /** What the API shows the owner, including how to verify. */
    public JsonObject toViewJson() {
        JsonObject json = toJson();
        json.remove("tenant");
        json.remove("token");
        if (!verified) {
            json.put("verification", new JsonObject().put("type", "TXT").put("name", txtName()).put("value", txtValue()));
        }
        return json;
    }

    public static Domain fromJson(JsonObject json) {
        return new Domain(json.getString("domain"), json.getString("tenant"), json.getString("service"), json.getString("token"),
                json.getBoolean("verified", false), json.getLong("createdAt", 0L), json.getLong("verifiedAt"), json.getInteger("port"));
    }
}
