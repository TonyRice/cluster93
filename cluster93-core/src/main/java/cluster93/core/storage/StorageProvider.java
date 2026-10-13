package cluster93.core.storage;

import io.vertx.core.json.JsonObject;

/**
 * A tenant's own place for file bytes (D-057): an S3-compatible bucket with its keys, or a folder under the admin's
 * disk root. {@code settings} holds what isn't secret (endpoint, region, bucket, prefix, pathStyle, accessKeyId);
 * {@code sealedSecret} is the secret key, encrypted with the cluster's key and never returned by the API.
 */
public record StorageProvider(String id, String tenant, String name, String type, JsonObject settings, String sealedSecret,
                              long createdAt, long updatedAt) {

    public static final String S3 = "s3";
    public static final String DISK = "disk";
    /** File metadata names a tenant provider's blobs with this, then its id. */
    public static final String BACKEND_PREFIX = "provider:";

    public String backend() {
        return BACKEND_PREFIX + id;
    }

    public JsonObject toJson() {
        return toViewJson().put("sealedSecret", sealedSecret);
    }

    /** What the API shows: never the secret, only whether one is set. */
    public JsonObject toViewJson() {
        return new JsonObject().put("id", id).put("tenant", tenant).put("name", name).put("type", type).put("settings", settings.copy())
                .put("hasSecret", sealedSecret != null).put("createdAt", createdAt).put("updatedAt", updatedAt);
    }

    public static StorageProvider fromJson(JsonObject json) {
        return json == null ? null : new StorageProvider(json.getString("id"), json.getString("tenant"), json.getString("name"),
                json.getString("type"), json.getJsonObject("settings", new JsonObject()), json.getString("sealedSecret"),
                json.getLong("createdAt"), json.getLong("updatedAt"));
    }
}
