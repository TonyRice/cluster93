package automately.core.auth;

import io.vertx.core.json.JsonObject;

/** A tenant: the owner of jobs, services, files and kv. Created by the admin (D-044). */
public record Tenant(String name, long createdAt) {

    public JsonObject toJson() {
        return new JsonObject().put("name", name).put("createdAt", createdAt);
    }

    public static Tenant fromJson(JsonObject json) {
        return new Tenant(json.getString("name"), json.getLong("createdAt"));
    }
}
