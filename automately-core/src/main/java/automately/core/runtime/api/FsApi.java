package automately.core.runtime.api;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The file system as scripts see it (D-079): the tenant's files as one tree rooted at {@code /}, with the job's own
 * {@code /tmp}. Behind the {@code fs} module, which also offers each call synchronously. Paths may start with {@code /};
 * failures carry the store's codes ({@code not_found}, {@code already_exists}, …), which {@code fs} turns into Node's.
 */
public interface FsApi {

    /** At most this much is read into memory at once ({@code readFile}). */
    int MAX_READ_BYTES = 32 * 1024 * 1024;
    /** At most this big a file is edited in place ({@code appendFile}). */
    int MAX_EDIT_BYTES = 64 * 1024 * 1024;

    /** {@code {kind: "file"|"dir", size, modifiedAt, contentType?, etag?}}, or null. */
    Future<JsonObject> stat(String path);

    /** One level: {@code [{name, kind, size}]}, by name. */
    Future<JsonArray> readdir(String path);

    Future<Void> mkdir(String path, boolean recursive);

    /** An empty folder, or with {@code recursive} everything in it. */
    Future<Void> rmdir(String path, boolean recursive);

    /** A file. */
    Future<Void> unlink(String path);

    /** A file or folder; a file at {@code to} is replaced. */
    Future<Void> rename(String from, String to);

    Future<Void> copyFile(String from, String to, boolean overwrite);

    Future<Buffer> readFile(String path);

    Future<Void> writeFile(String path, Buffer data, String contentType);

    Future<Void> appendFile(String path, Buffer data);
}
