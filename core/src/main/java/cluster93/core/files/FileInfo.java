package cluster93.core.files;

import io.vertx.core.json.JsonObject;

/**
 * One version of a file: its metadata row. {@code fileId} names its blob, and {@code backend} says which
 * {@link BlobStore} holds it (D-056); rows written before backends existed are in the cluster.
 */
public record FileInfo(String tenant, String path, String fileId, long size, String contentType, String etag, String backend,
                       long createdAt) {

    public JsonObject toJson() {
        return new JsonObject().put("tenant", tenant).put("path", path).put("fileId", fileId).put("size", size)
                .put("contentType", contentType).put("etag", etag).put("backend", backend).put("createdAt", createdAt);
    }

    /** What scripts and the API see. */
    public JsonObject toViewJson() {
        return new JsonObject().put("path", path).put("size", size).put("contentType", contentType).put("etag", etag).put("createdAt", createdAt);
    }

    public static FileInfo fromJson(JsonObject json) {
        return json == null ? null : new FileInfo(json.getString("tenant"), json.getString("path"), json.getString("fileId"), json.getLong("size"),
                json.getString("contentType"), json.getString("etag"), json.getString("backend", ChunkedBlobStore.NAME), json.getLong("createdAt"));
    }
}
