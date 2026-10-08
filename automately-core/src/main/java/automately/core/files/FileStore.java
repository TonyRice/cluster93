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
     * Starts an upload to the store this path routes to (D-057), which may hold at most what {@code room} allows there
     * (D-043). Fails if there's no room at all; a write that goes past it fails and discards the upload.
     */
    Future<FileWriter> create(String tenant, String path, String contentType, Room room);

    /** Fails, saying which limit, if a file of {@code bytes} at this path wouldn't fit where it would go (checked up front). */
    Future<Void> ensureRoom(String tenant, String path, Room room, long bytes);

    /** Bytes {@code start} to {@code endInclusive} of a version, as a paused stream. */
    BodyStream open(FileInfo file, long start, long endInclusive);

    /** {@code {"files": [view…], "cursor": next-or-null}}, in path order. */
    Future<JsonObject> list(String tenant, String prefix, int limit, String cursor);

    /** Completes with whether a file was removed. Its bytes are deleted after the grace period. */
    Future<Boolean> delete(String tenant, String path);

    /**
     * A file's view plus {@code kind: "file"}, or a directory's ({@code {name, path, kind: "dir", createdAt}}); null if
     * neither exists. The root ({@code ""}) is always a directory (D-076).
     */
    Future<JsonObject> statEntry(String tenant, String path);

    /**
     * Makes a directory; with {@code parents}, also any missing ones above it, and no error if it exists. Completes with
     * whether it was made. Fails ({@code already_exists}, {@code not_a_directory}, {@code not_found}) as the code says.
     */
    Future<Boolean> mkdir(String tenant, String path, boolean parents);

    /** Removes an empty directory; completes with whether there was one. Fails {@code not_empty} or {@code not_a_directory}. */
    Future<Boolean> rmdir(String tenant, String path);

    /** One level of a directory ({@code ""} is the root): {@code {path, entries: [dir and file views, by name], cursor}}. */
    Future<JsonObject> listDir(String tenant, String dir, int limit, String cursor);

    /** Bytes a tenant stores on the cluster's own backend; its own providers don't count (D-057). */
    Future<Long> usage(String tenant);

    /** Bytes a tenant stores in these backends (a disk allotment's providers). */
    Future<Long> usageIn(String tenant, java.util.List<String> backends);

    /** How many files (current versions) are in a backend: a provider holding any can't be deleted. */
    Future<Long> countIn(String backend);

    /** Deletes the bytes of versions that were replaced, deleted or abandoned and are past their grace period. */
    Future<Integer> collectGarbage(long now);
}
