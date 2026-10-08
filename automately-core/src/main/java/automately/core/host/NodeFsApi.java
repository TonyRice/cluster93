package automately.core.host;

import automately.core.files.FileInfo;
import automately.core.files.FileStore;
import automately.core.files.FileTree;
import automately.core.files.FileWriter;
import automately.core.files.Paths;
import automately.core.files.Room;
import automately.core.files.StorageCapacity;
import automately.core.files.TmpSettings;
import automately.core.files.TmpStore;
import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import automately.core.runtime.api.FsApi;
import automately.core.storage.StorageRoom;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The file system for one job (D-079): the tenant's files ({@link FileStore}, folders, moves and copies through
 * {@link FileTree}) as one tree, with the job's own {@code /tmp} ({@link TmpStore}) inside it, and its packages'
 * files read-only at {@code /packages} (D-091). Writes to the tenant's files are counted against its quota as they
 * finish, like {@code files.write}. A {@code file:///} URL names the same path, so {@code import.meta.url} works.
 */
final class NodeFsApi implements FsApi {

    private static final String TMP = "tmp";

    private final HostScope scope;
    private final FileStore files;
    private final FileTree tree;
    private final StorageCapacity capacity;
    private final TenantQuota quota;
    private final Room room;
    private final TmpSettings tmpSettings;
    private final automately.core.files.FileLocks locks;
    /** Locks this job holds (path → token), released when it ends. */
    private final java.util.Map<String, String> held = new java.util.concurrent.ConcurrentHashMap<>();
    private final PackageFiles packages;
    private TmpStore tmp;

    NodeFsApi(HostScope scope, FileStore files, FileTree tree, StorageCapacity capacity, TenantQuota quota, Room room, TmpSettings tmpSettings,
              automately.core.files.FileLocks locks, automately.core.runtime.Packages packages) {
        this.packages = new PackageFiles(packages == null ? automately.core.runtime.Packages.NONE : packages);
        this.locks = locks;
        scope.onClose(() -> held.forEach((path, token) -> locks.release(scope.tenant(), path, token)));
        this.scope = scope;
        this.files = files;
        this.tree = tree;
        this.capacity = capacity;
        this.quota = quota;
        this.room = room;
        this.tmpSettings = tmpSettings;
    }

    /**
     * A path in the tree: {@code tmp} says it's in {@code /tmp}, {@code pkg} that it's under {@code /packages}, and
     * {@code rel} is the path within that (or the store).
     */
    private record Where(boolean tmp, boolean pkg, String rel, String shown) {

        Where(boolean tmp, String rel, String shown) {
            this(tmp, false, rel, shown);
        }
    }

    private static Where where(String raw) {
        if (raw == null) {
            throw HostException.invalid("A path is required");
        }
        String path = Paths.directory(fromUrl(raw));
        if (path.equals(PackageFiles.ROOT) || path.startsWith(PackageFiles.ROOT + "/")) {
            String rel = path.equals(PackageFiles.ROOT) ? "" : path.substring(PackageFiles.ROOT.length() + 1);
            if (java.util.Arrays.asList(rel.split("/")).contains("..")) {
                throw HostException.invalid("A path can't have '..' parts: " + raw);
            }
            return new Where(false, true, rel, "/" + path);
        }
        String invalid = path.isEmpty() ? null : FileStore.invalidPath(path);
        if (invalid != null) {
            throw HostException.invalid(invalid);
        }
        if (path.equals(TMP) || path.startsWith(TMP + "/")) {
            return new Where(true, path.equals(TMP) ? "" : path.substring(TMP.length() + 1), "/" + path);
        }
        return new Where(false, path, "/" + path);
    }

    /** {@code file:///a/b} is {@code /a/b}, percent-decoded; anything else is as given. */
    static String fromUrl(String raw) {
        if (!raw.startsWith("file:")) {
            return raw;
        }
        String rest = raw.substring("file:".length());
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
            if (!rest.startsWith("/") && !rest.startsWith("localhost/")) {
                throw HostException.invalid("A file URL can't name a host: " + raw);
            }
            rest = rest.startsWith("localhost/") ? rest.substring("localhost".length()) : rest;
        }
        int end = rest.length();
        for (char stop : new char[]{'?', '#'}) {
            int at = rest.indexOf(stop);
            end = at >= 0 ? Math.min(end, at) : end;
        }
        try {
            return java.net.URLDecoder.decode(rest.substring(0, end).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw HostException.invalid("Not a valid file URL: " + raw);
        }
    }

    private static HostException readOnly(Where at) {
        return new HostException("Error", "read_only", at.shown() + " is read-only: /packages holds the job's packages");
    }

    /** Fails for a change under {@code /packages}. */
    private static void writable(Where... places) {
        for (Where at : places) {
            if (at.pkg()) {
                throw readOnly(at);
            }
        }
    }

    /** The job's {@code /tmp}, made on first use and dropped when the job ends. */
    private synchronized TmpStore tmp() {
        if (tmp == null) {
            tmp = tmpSettings.open(scope.jobId() == null ? "script" : scope.jobId());
            TmpStore made = tmp;
            scope.onClose(made::close);
        }
        return tmp;
    }

    private FileStore store() {
        if (files == null) {
            throw new HostException("Error", "unavailable", "Files are not available here");
        }
        return files;
    }

    @Override
    public Future<JsonObject> stat(String path) {
        return Names.guarded(() -> {
            Where at = where(path);
            if (at.tmp()) {
                return Future.succeededFuture(tmp().stat(at.rel()));
            }
            if (at.pkg()) {
                return Future.succeededFuture(packages.stat(at.rel()));
            }
            return store().statEntry(scope.tenant(), at.rel()).map(entry -> entry == null ? null : new JsonObject()
                    .put("kind", entry.getString("kind")).put("size", entry.getLong("size", 0L))
                    .put("modifiedAt", entry.getLong("createdAt", 0L)).put("contentType", entry.getString("contentType"))
                    .put("etag", entry.getString("etag")));
        });
    }

    @Override
    public Future<JsonArray> readdir(String path) {
        return Names.guarded(() -> {
            Where at = where(path);
            if (at.tmp()) {
                return Future.succeededFuture(tmp().list(at.rel()));
            }
            if (at.pkg()) {
                JsonArray listed = packages.list(at.rel());
                return listed != null ? Future.succeededFuture(listed) : Future.failedFuture(missing(at));
            }
            JsonArray entries = new JsonArray();
            return page(at.rel(), null, entries).map(v -> {
                if (at.rel().isEmpty() && entries.stream().noneMatch(e -> TMP.equals(((JsonObject) e).getString("name")))) {
                    // /tmp is always there, though it isn't in the store; so is /packages when the job has some.
                    entries.add(new JsonObject().put("name", TMP).put("kind", "dir").put("size", 0L));
                    if (!packages.isEmpty()) {
                        entries.add(new JsonObject().put("name", PackageFiles.ROOT).put("kind", "dir").put("size", 0L));
                    }
                    entries.getList().sort(java.util.Comparator.comparing(e -> e instanceof JsonObject json ? json.getString("name")
                            : String.valueOf(((java.util.Map<?, ?>) e).get("name"))));
                }
                return entries;
            });
        });
    }

    private Future<Void> page(String dir, String cursor, JsonArray into) {
        return store().listDir(scope.tenant(), dir, 1000, cursor).compose(page -> {
            page.getJsonArray("entries").forEach(e -> {
                JsonObject entry = (JsonObject) e;
                into.add(new JsonObject().put("name", entry.getString("name")).put("kind", entry.getString("kind")).put("size", entry.getLong("size", 0L)));
            });
            String next = page.getString("cursor");
            return next == null ? Future.succeededFuture() : page(dir, next, into);
        });
    }

    @Override
    public Future<Void> mkdir(String path, boolean recursive) {
        return Names.guarded(() -> {
            Where at = where(path);
            writable(at);
            if (at.tmp()) {
                tmp().mkdir(at.rel(), recursive);
                return Future.succeededFuture();
            }
            return store().mkdir(scope.tenant(), at.rel(), recursive).mapEmpty();
        });
    }

    @Override
    public Future<Void> rmdir(String path, boolean recursive) {
        return Names.guarded(() -> {
            Where at = where(path);
            writable(at);
            if (at.tmp()) {
                if (!tmp().rmdir(at.rel(), recursive)) {
                    throw missing(at);
                }
                return Future.succeededFuture();
            }
            if (recursive) {
                return requireTree().delete(scope.tenant(), at.rel()).mapEmpty();
            }
            return store().rmdir(scope.tenant(), at.rel()).compose(removed -> removed ? Future.succeededFuture() : Future.failedFuture(missing(at)));
        });
    }

    @Override
    public Future<Void> unlink(String path) {
        return Names.guarded(() -> {
            Where at = where(path);
            writable(at);
            if (at.tmp()) {
                if (!tmp().delete(at.rel())) {
                    throw missing(at);
                }
                return Future.succeededFuture();
            }
            return store().delete(scope.tenant(), at.rel()).compose(deleted -> deleted ? Future.succeededFuture()
                    : store().statEntry(scope.tenant(), at.rel()).compose(entry -> Future.failedFuture(entry != null
                            ? new HostException("Error", "is_a_directory", at.shown() + " is a directory")
                            : missing(at))));
        });
    }

    @Override
    public Future<Void> rename(String from, String to) {
        return Names.guarded(() -> {
            Where source = where(from);
            Where target = where(to);
            writable(source, target);
            if (source.tmp() && target.tmp()) {
                tmp().rename(source.rel(), target.rel(), true);
                return Future.succeededFuture();
            }
            if (!source.tmp() && !target.tmp()) {
                return requireTree().move(scope.tenant(), source.rel(), target.rel(), true).mapEmpty();
            }
            // Between /tmp and the tenant's files the bytes have to move: files only.
            return readFile(from).compose(bytes -> writeFile(to, bytes, null)).compose(v -> unlink(from));
        });
    }

    @Override
    public Future<Void> copyFile(String from, String to, boolean overwrite) {
        return Names.guarded(() -> {
            Where source = where(from);
            Where target = where(to);
            writable(target);
            if (!source.tmp() && !target.tmp() && !source.pkg()) {
                return store().copy(scope.tenant(), source.rel(), target.rel(), overwrite).mapEmpty();
            }
            Future<Void> free = overwrite ? Future.succeededFuture() : stat(to).compose(there -> there == null ? Future.succeededFuture()
                    : Future.failedFuture(new HostException("Error", "already_exists", target.shown() + " already exists")));
            return free.compose(v -> readFile(from)).compose(bytes -> writeFile(to, bytes, null));
        });
    }

    @Override
    public Future<Buffer> readFile(String path) {
        return Names.guarded(() -> {
            Where at = where(path);
            if (at.tmp()) {
                return Future.succeededFuture(Buffer.buffer(tmp().read(at.rel())));
            }
            if (at.pkg()) {
                byte[] bytes = packages.read(at.rel());
                if (bytes == null) {
                    throw packages.isDir(at.rel()) ? new HostException("Error", "is_a_directory", at.shown() + " is a directory") : missing(at);
                }
                return Future.succeededFuture(Buffer.buffer(bytes));
            }
            return store().stat(scope.tenant(), at.rel()).compose(info -> {
                if (info == null) {
                    return store().statEntry(scope.tenant(), at.rel()).compose(entry -> Future.failedFuture(entry != null
                            ? new HostException("Error", "is_a_directory", at.shown() + " is a directory")
                            : missing(at)));
                }
                if (info.size() > MAX_READ_BYTES) {
                    return Future.failedFuture(HostException.limit(at.shown() + " is " + info.size() + " bytes; read files over "
                            + MAX_READ_BYTES / (1024 * 1024) + " MB as a stream (files.stream)"));
                }
                return info.size() == 0 ? Future.succeededFuture(Buffer.buffer()) : collect(store().open(info, 0, info.size() - 1));
            });
        });
    }

    @Override
    public Future<Void> writeFile(String path, Buffer data, String contentType) {
        return Names.guarded(() -> {
            Where at = where(path);
            writable(at);
            if (at.tmp()) {
                tmp().write(at.rel(), data.getBytes());
                return Future.succeededFuture();
            }
            String tenant = scope.tenant();
            return capacity.ensureRoom()
                    .compose(ok -> store().create(tenant, at.rel(), contentType, room))
                    .compose(writer -> write(writer, data))
                    .onSuccess(info -> {
                        if (StorageRoom.countsAgainstQuota(info.backend())) {
                            quota.added(tenant, info.size());
                        }
                    })
                    .mapEmpty();
        });
    }

    @Override
    public Future<Void> appendFile(String path, Buffer data) {
        // A new version with the old bytes then the new, published at once: readers never see half an append.
        return stat(path).compose(existing -> {
            if (existing == null) {
                return writeFile(path, data, null);
            }
            if (existing.getLong("size", 0L) + data.length() > MAX_EDIT_BYTES) {
                return Future.failedFuture(HostException.limit("Appending makes " + path + " bigger than "
                        + MAX_EDIT_BYTES / (1024 * 1024) + " MB; write big files as a stream (files.write)"));
            }
            return readFile(path).compose(old -> writeFile(path, old.appendBuffer(data), existing.getString("contentType")));
        });
    }

    @Override
    public Future<JsonObject> lock(String path, long ttlMs) {
        return Names.guarded(() -> {
            String p = lockPath(path);
            return requireLocks().acquire(scope.tenant(), p, java.time.Duration.ofMillis(ttlMs)).onSuccess(lease -> held.put(p, lease.getString("token")));
        });
    }

    @Override
    public Future<JsonObject> renewLock(String path, String token, long ttlMs) {
        return Names.guarded(() -> requireLocks().renew(scope.tenant(), lockPath(path), token, java.time.Duration.ofMillis(ttlMs)));
    }

    @Override
    public Future<Boolean> unlock(String path, String token) {
        return Names.guarded(() -> {
            String p = lockPath(path);
            held.remove(p, token);
            return requireLocks().release(scope.tenant(), p, token);
        });
    }

    /** Any path can be locked, whether or not a file is there: it names the work, not the bytes. */
    private static String lockPath(String path) {
        Where at = where(path);
        return at.tmp() ? "tmp/" + at.rel() : at.pkg() ? PackageFiles.ROOT + "/" + at.rel() : at.rel();
    }

    private automately.core.files.FileLocks requireLocks() {
        if (locks == null) {
            throw new HostException("Error", "unavailable", "Locks aren't available here");
        }
        return locks;
    }

    private static Future<FileInfo> write(FileWriter writer, Buffer data) {
        return writer.write(data).compose(v -> writer.finish()).recover(err -> writer.abort().compose(v -> Future.failedFuture(err)));
    }

    private static Future<Buffer> collect(BodyStream stream) {
        Promise<Buffer> done = Promise.promise();
        Buffer all = Buffer.buffer();
        stream.handler(all::appendBuffer, () -> done.tryComplete(all), done::tryFail);
        stream.resume();
        return done.future();
    }

    private FileTree requireTree() {
        if (tree == null) {
            throw new HostException("Error", "unavailable", "Moving folders isn't available here");
        }
        return tree;
    }

    private static HostException missing(Where at) {
        return new HostException("Error", "not_found", "No such file or directory: " + at.shown());
    }
}
