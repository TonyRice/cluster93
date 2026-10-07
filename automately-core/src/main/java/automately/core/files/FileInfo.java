package automately.core.files;

import io.vertx.core.json.JsonObject;

/**
 * One stored file version.
 *
 * @param fileId  a new id per write; chunks are stored under it, so replacing a file never disturbs a reader of the old version
 * @param etag    quoted SHA-256 of the content, for If-None-Match
 */
public record FileInfo(String tenant, String path, String fileId, long size, String contentType, String etag, int chunkSize, int chunks,
                       long createdAt) {

    public JsonObject toJson() {
        return new JsonObject().put("tenant", tenant).put("path", path).put("fileId", fileId).put("size", size)
                .put("contentType", contentType).put("etag", etag).put("chunkSize", chunkSize).put("chunks", chunks).put("createdAt", createdAt);
    }

    /** What scripts and the API see. */
    public JsonObject toViewJson() {
        return new JsonObject().put("path", path).put("size", size).put("contentType", contentType).put("etag", etag).put("createdAt", createdAt);
    }

    public static FileInfo fromJson(JsonObject json) {
        return json == null ? null : new FileInfo(json.getString("tenant"), json.getString("path"), json.getString("fileId"), json.getLong("size"),
                json.getString("contentType"), json.getString("etag"), json.getInteger("chunkSize"), json.getInteger("chunks"), json.getLong("createdAt"));
    }
}
