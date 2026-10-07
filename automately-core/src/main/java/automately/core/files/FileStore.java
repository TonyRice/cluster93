package automately.core.files;

import automately.core.runtime.BodyStream;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * Tenant-scoped file storage: files are stored as 1 MB chunks in the cluster's durable store, so any node can serve any
 * file and files survive node loss and full restarts. See docs/design/durability-and-streaming.md (milestone 3).
 */
public interface FileStore {

    /**
     * A valid file path: 1–1024 bytes, relative ({@code a/b.txt}), no empty, {@code .} or {@code ..} segments, no
     * backslashes or control characters. Returns null if valid, or why not.
     */
    static String invalidPath(String path) {
        if (path == null || path.isEmpty()) {
            return "A file path must not be empty";
        }
        if (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
            return "A file path is limited to 1024 bytes";
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("\\")) {
            return "A file path is relative, like docs/readme.md (no leading or trailing slash, no backslashes)";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "A file path can't contain empty, '.' or '..' segments";
            }
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                return "A file path can't contain control characters";
            }
        }
        return null;
    }

    /** Completes with null if there is no such file. */
    Future<FileInfo> stat(String tenant, String path);

    /**
     * Starts an upload that may store at most {@code maxBytes} (what the tenant's quota has left, D-043). Fails with
     * a quota error if that's nothing; a write that goes past it fails and discards the upload.
     */
    Future<FileWriter> create(String tenant, String path, String contentType, long maxBytes);

    /** Bytes {@code start} to {@code endInclusive} of a version, as a paused stream. */
    BodyStream open(FileInfo file, long start, long endInclusive);

    /** {@code {"files": [view…], "cursor": next-or-null}}, in path order. */
    Future<JsonObject> list(String tenant, String prefix, int limit, String cursor);

    /** Completes with whether a file was removed. Its chunks are deleted after the grace period. */
    Future<Boolean> delete(String tenant, String path);

    /** Bytes stored by a tenant. */
    Future<Long> usage(String tenant);

    /** Deletes chunks of versions that were replaced, deleted or abandoned and are past their grace period. */
    Future<Integer> collectGarbage(long now);
}
