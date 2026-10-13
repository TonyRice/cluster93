package cluster93.core.files;

import cluster93.core.runtime.BodyStream;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Files with their metadata in the cluster's database and their bytes in a {@link BlobStore}: the cluster's own, or a
 * tenant's provider when its routes say so (D-040, D-056, D-057).
 *
 * <p>Each write is a new blob. Once it's complete, one metadata write publishes it, so readers see the old version or
 * the new one, never a mix. The previous version goes to the garbage table, with the backend that holds it, and its
 * blob is deleted after a grace period long enough for downloads already in progress to finish. Subclasses only
 * provide the metadata and garbage storage.
 */
public abstract class ClusterFileStore implements FileStore {

    /** How long a replaced or deleted version's blob is kept for readers still streaming it. */
    public static final Duration READER_GRACE = Duration.ofMinutes(10);
    /** An upload that never finished (its writer died) is cleaned up after this. */
    public static final Duration ABANDONED_UPLOAD = Duration.ofHours(6);

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClusterFileStore.class);

    protected final Vertx vertx;
    private final BlobStores stores;

    protected ClusterFileStore(Vertx vertx, BlobStores stores) {
        this.vertx = vertx;
        this.stores = stores;
    }

    public BlobStores stores() {
        return stores;
    }

    // --- storage primitives ---

    protected abstract Future<JsonObject> getMeta(String tenant, String path);

    /** Stores the metadata and completes with the previous metadata, or null. */
    protected abstract Future<JsonObject> putMeta(String tenant, String path, JsonObject meta);

    /** Removes the metadata and completes with what it was, or null. */
    protected abstract Future<JsonObject> removeMeta(String tenant, String path);

    /** Metadata in path order. */
    protected abstract Future<List<JsonObject>> listMeta(String tenant, String prefix, int limit, String cursor);

    /** Bytes a tenant has in backends that aren't tenant providers (their names don't start "provider:"). */
    protected abstract Future<Long> sumOwnBytes(String tenant);

    protected abstract Future<Long> sumBytesIn(String tenant, List<String> backends);

    protected abstract Future<Long> countFilesIn(String backend);

    /** A blob to delete after {@code deleteAfter}, and where it is. */
    protected abstract Future<Void> putGarbage(String fileId, String backend, String tenant, long deleteAfter);

    protected abstract Future<Void> removeGarbage(String fileId);

    /** Due garbage rows: {@code {"fileId", "backend", "tenant"}}. */
    protected abstract Future<List<JsonObject>> dueGarbage(long now, int limit);

    // --- directories (D-076) ---

    /** A directory's row ({@code {tenant, path, createdAt}}), or null. */
    protected abstract Future<JsonObject> getDir(String tenant, String path);

    /** Completes with whether it was stored (false if the directory already existed). */
    protected abstract Future<Boolean> putDirIfAbsent(String tenant, String path, JsonObject row);

    protected abstract Future<JsonObject> removeDir(String tenant, String path);

    /** Directory rows directly inside {@code parent} ("" is the root), by name, after {@code after} (or from the start). */
    protected abstract Future<List<JsonObject>> childDirs(String tenant, String parent, String after, int limit);

    /** File rows directly inside {@code parent}, by name, after {@code after}. */
    protected abstract Future<List<JsonObject>> childFiles(String tenant, String parent, String after, int limit);

    /** Removes the metadata only if it's still exactly {@code expected}; completes with whether it did. */
    protected abstract Future<Boolean> removeMetaIfUnchanged(String tenant, String path, JsonObject expected);

    // --- shared blobs (D-077) ---

    /**
     * Atomically adds {@code delta} to how many file rows name this blob, and completes with the new count. A blob with
     * no count yet has one: its own file row. The count is kept at zero rather than removed, so the collector can tell a
     * blob nobody names (0, or no count and a garbage row) from one a copy just took up again.
     */
    protected abstract Future<Long> addRefs(String fileId, long delta);

    /** The count, or null if the blob never had one. */
    protected abstract Future<Long> refs(String fileId);

    protected abstract Future<Void> removeRefs(String fileId);

    // --- FileStore ---

    @Override
    public Future<FileInfo> stat(String tenant, String path) {
        return getMeta(tenant, path).map(FileInfo::fromJson);
    }

    @Override
    public Future<Long> usage(String tenant) {
        return sumOwnBytes(tenant);
    }

    @Override
    public Future<Long> usageIn(String tenant, List<String> backends) {
        return backends.isEmpty() ? Future.succeededFuture(0L) : sumBytesIn(tenant, backends);
    }

    @Override
    public Future<Long> countIn(String backend) {
        return countFilesIn(backend);
    }

    @Override
    public Future<Void> ensureRoom(String tenant, String path, Room room, long bytes) {
        return stores.forWrite(tenant, path).compose(target -> room.bytes(tenant, target)
                .compose(left -> bytes > left ? Future.failedFuture(room.tooBig(tenant, target)) : Future.succeededFuture()));
    }

    @Override
    public Future<FileWriter> create(String tenant, String path, String contentType, Room room) {
        String fileId = UUID.randomUUID().toString();
        String type = contentType == null ? "application/octet-stream" : contentType;
        // A file can't take a directory's place, and its folders are made if they're missing (D-076).
        Future<Void> place = stores.checkWritable(tenant, path).compose(ok -> getDir(tenant, path)).compose(dir -> dir != null
                ? Future.failedFuture(conflict("is_a_directory", path + " is a directory"))
                : ensureDirs(tenant, Paths.parent(path)));
        return place.compose(placed -> stores.forWrite(tenant, path)).compose(target -> room.bytes(tenant, target).compose(maxBytes -> {
            if (maxBytes <= 0) {
                return Future.failedFuture(quota());
            }
            // Recorded first, so a writer that dies mid-upload leaves nothing behind for long.
            return putGarbage(fileId, target.name(), tenant, System.currentTimeMillis() + ABANDONED_UPLOAD.toMillis())
                    .compose(v -> target.create(fileId, type))
                    .map(blob -> new Writer(tenant, path, type, fileId, maxBytes, target, blob));
        }));
    }

    static HostException quota() {
        return HostException.limit("Storage quota reached: the tenant has no room left for this upload (files, kv and queued messages together)");
    }

    @Override
    public BodyStream open(FileInfo file, long start, long endInclusive) {
        if (file.backend().equals(stores.primary().name())) {
            return stores.primary().open(file.fileId(), file.size(), start, endInclusive);
        }
        return BodyStreams.deferred(stores.forRead(file.tenant(), file.backend())
                .map(store -> store.open(file.fileId(), file.size(), start, endInclusive)));
    }

    @Override
    public Future<Boolean> delete(String tenant, String path) {
        return stores.checkRemovable(tenant, path).compose(ok -> removeMeta(tenant, path)).compose(previous -> previous == null
                ? Future.succeededFuture(false)
                : release(FileInfo.fromJson(previous)).map(true));
    }

    /** One fewer row names this version's blob; when none does, it's deleted after the readers' grace (D-077). */
    protected Future<Void> release(FileInfo version) {
        return addRefs(version.fileId(), -1).compose(left -> left > 0
                ? Future.succeededFuture()
                : putGarbage(version.fileId(), version.backend(), version.tenant(), System.currentTimeMillis() + READER_GRACE.toMillis()));
    }

    // --- move and copy (D-077) ---

    @Override
    public Future<FileInfo> copy(String tenant, String from, String to, boolean overwrite) {
        return place(tenant, from, to, overwrite).compose(source -> addRefs(source.fileId(), 1).compose(v -> publish(tenant, to, source)
                .recover(err -> addRefs(source.fileId(), -1).compose(x -> Future.failedFuture(err)))));
    }

    @Override
    public Future<FileInfo> move(String tenant, String from, String to, boolean overwrite) {
        return stores.checkRemovable(tenant, from).compose(ok -> place(tenant, from, to, overwrite)).compose(source -> publish(tenant, to, source)
                // Only the version that was moved leaves its old path: if it was replaced meanwhile, the new one stays,
                // and the moved version is now a copy, so it gains a reference.
                .compose(moved -> removeMetaIfUnchanged(tenant, from, source.toJson()).compose(removed -> removed
                        ? Future.succeededFuture(moved)
                        : addRefs(source.fileId(), 1).map(moved))));
    }

    /** The source file, once the target is known to be a free name for a file (or one to overwrite) with its folders made. */
    private Future<FileInfo> place(String tenant, String from, String to, boolean overwrite) {
        if (from.equals(to)) {
            return Future.failedFuture(HostException.invalid("The source and the target are the same: " + from));
        }
        return stores.checkWritable(tenant, to).compose(ok -> getMeta(tenant, from)).compose(sourceRow -> {
            if (sourceRow == null) {
                return getDir(tenant, from).compose(dir -> Future.failedFuture(dir != null
                        ? conflict("is_a_directory", from + " is a folder; move or copy folders as a tree")
                        : new HostException("Error", "not_found", "No such file: " + from)));
            }
            return getDir(tenant, to).compose(dir -> dir != null
                    ? Future.failedFuture(conflict("is_a_directory", to + " is a folder"))
                    : getMeta(tenant, to).compose(existing -> existing != null && !overwrite
                            ? Future.failedFuture(conflict("already_exists", to + " already exists; overwrite it to replace it"))
                            : ensureDirs(tenant, Paths.parent(to))))
                    .map(FileInfo.fromJson(sourceRow));
        });
    }

    /** Puts {@code source}'s blob at {@code to}; whatever was there loses its reference. */
    private Future<FileInfo> publish(String tenant, String to, FileInfo source) {
        FileInfo placed = new FileInfo(tenant, to, source.fileId(), source.size(), source.contentType(), source.etag(), source.backend(),
                System.currentTimeMillis());
        return putMeta(tenant, to, placed.toJson()).compose(previous -> previous == null || previous.getString("fileId").equals(source.fileId())
                ? Future.succeededFuture(placed)
                : release(FileInfo.fromJson(previous)).map(placed));
    }

    @Override
    public Future<JsonObject> list(String tenant, String prefix, int limit, String cursor) {
        return listMeta(tenant, prefix, limit + 1, cursor).map(rows -> {
            boolean more = rows.size() > limit;
            List<JsonObject> page = more ? rows.subList(0, limit) : rows;
            return new JsonObject()
                    .put("files", new io.vertx.core.json.JsonArray(page.stream().map(r -> FileInfo.fromJson(r).toViewJson()).toList()))
                    .put("cursor", more ? page.getLast().getString("path") : null);
        });
    }

    @Override
    public Future<Integer> collectGarbage(long now) {
        return dueGarbage(now, 100).compose(rows -> {
            Future<Void> chain = Future.succeededFuture();
            for (JsonObject row : rows) {
                String id = row.getString("fileId");
                String backend = row.getString("backend", stores.primary().name());
                // A copy made after the blob was released took it up again: it stays (D-077).
                chain = chain.compose(v -> refs(id)).compose(count -> count != null && count > 0
                        ? removeGarbage(id)
                        : stores.forRead(row.getString("tenant"), backend)
                                .compose(store -> store.delete(id))
                                .recover(err -> {
                                    // A provider that's gone, or a backend no longer configured: the bytes are out of reach,
                                    // so the row goes rather than being retried forever.
                                    LOG.warn("Can't delete blob {} in {}: {}", id, backend, err.getMessage());
                                    return Future.succeededFuture();
                                })
                                .compose(v -> removeGarbage(id))
                                .compose(v -> removeRefs(id)));
            }
            return chain.map(rows.size());
        });
    }

    // --- directories (D-076) ---

    @Override
    public Future<JsonObject> statEntry(String tenant, String path) {
        if (path.isEmpty()) {
            return Future.succeededFuture(dirView(new JsonObject().put("path", "").put("createdAt", 0L)));
        }
        return stores.describe(tenant).compose(storage -> getMeta(tenant, path).compose(file -> file != null
                ? Future.succeededFuture(annotate(fileView(FileInfo.fromJson(file)), FileInfo.fromJson(file).backend(), storage))
                : getDir(tenant, path).map(dir -> dir == null ? null : annotate(dirView(dir), null, storage))));
    }

    /** Where an entry's bytes are (files), or which provider a folder is a mount of (D-078). */
    static JsonObject annotate(JsonObject entry, String backend, JsonObject storage) {
        if (backend != null) {
            entry.put("storage", storage.getJsonObject("names").getString(backend, backend.startsWith("provider:") ? "a removed provider" : backend));
        }
        JsonObject mount = storage.getJsonObject("mounts").getJsonObject(entry.getString("path"));
        if (mount != null) {
            entry.put("mount", mount);
        }
        return entry;
    }

    @Override
    public Future<Boolean> mkdir(String tenant, String path, boolean parents) {
        return stores.checkWritable(tenant, path).compose(ok -> getMeta(tenant, path)).compose(file -> {
            if (file != null) {
                return Future.failedFuture(conflict("already_exists", "A file already exists at " + path));
            }
            String parent = Paths.parent(path);
            Future<Void> above = parents ? ensureDirs(tenant, parent) : requireDir(tenant, parent);
            return above.compose(v -> putDirIfAbsent(tenant, path, dirRow(tenant, path))).compose(created -> created || parents
                    ? Future.succeededFuture(created)
                    : Future.failedFuture(conflict("already_exists", path + " already exists")));
        });
    }

    @Override
    public Future<Boolean> rmdir(String tenant, String path) {
        return stores.checkRemovable(tenant, path).compose(ok -> getDir(tenant, path)).compose(dir -> {
            if (dir == null) {
                return getMeta(tenant, path).compose(file -> file != null
                        ? Future.failedFuture(conflict("not_a_directory", path + " is a file"))
                        : Future.succeededFuture(false));
            }
            return childDirs(tenant, path, null, 1).compose(dirs -> childFiles(tenant, path, null, 1).compose(files -> !dirs.isEmpty() || !files.isEmpty()
                    ? Future.failedFuture(conflict("not_empty", path + " isn't empty"))
                    : removeDir(tenant, path).map(true)));
        });
    }

    @Override
    public Future<JsonObject> listDir(String tenant, String dir, int limit, String cursor) {
        Future<Void> exists = dir.isEmpty() ? Future.succeededFuture() : getDir(tenant, dir).compose(row -> row != null
                ? Future.succeededFuture()
                : getMeta(tenant, dir).compose(file -> Future.failedFuture(file != null
                        ? conflict("not_a_directory", dir + " is a file")
                        : new HostException("Error", "not_found", "No such directory: " + dir))));
        return exists.compose(v -> stores.describe(tenant)).compose(storage -> childDirs(tenant, dir, cursor, limit + 1).compose(dirs -> childFiles(tenant, dir, cursor, limit + 1).map(files -> {
            // Directories and files share one set of names, so merging the two sorted pages gives the next page.
            List<JsonObject> entries = new java.util.ArrayList<>();
            dirs.forEach(row -> entries.add(annotate(dirView(row), null, storage)));
            files.forEach(row -> entries.add(annotate(fileView(FileInfo.fromJson(row)), FileInfo.fromJson(row).backend(), storage)));
            entries.sort(java.util.Comparator.comparing(e -> e.getString("name")));
            boolean more = entries.size() > limit;
            List<JsonObject> page = more ? entries.subList(0, limit) : entries;
            return new JsonObject().put("path", dir).put("entries", new io.vertx.core.json.JsonArray(List.copyOf(page)))
                    .put("cursor", more ? page.getLast().getString("name") : null);
        })));
    }

    /** Makes {@code path} and any directories above it that are missing; refuses if a file is in the way. */
    protected Future<Void> ensureDirs(String tenant, String path) {
        if (path.isEmpty()) {
            return Future.succeededFuture();
        }
        // Every directory's ancestors exist (they're made first and removed last), so one found ends the climb.
        return getDir(tenant, path).compose(dir -> dir != null ? Future.succeededFuture()
                : getMeta(tenant, path).compose(file -> file != null
                        ? Future.failedFuture(conflict("not_a_directory", path + " is a file, so it can't hold others"))
                        : ensureDirs(tenant, Paths.parent(path)).compose(v -> putDirIfAbsent(tenant, path, dirRow(tenant, path))).mapEmpty()));
    }

    private Future<Void> requireDir(String tenant, String path) {
        return path.isEmpty() ? Future.succeededFuture() : getDir(tenant, path).compose(dir -> dir != null
                ? Future.succeededFuture()
                : Future.failedFuture(new HostException("Error", "not_found", "No such directory: " + path)));
    }

    static JsonObject dirRow(String tenant, String path) {
        return new JsonObject().put("tenant", tenant).put("path", path).put("createdAt", System.currentTimeMillis());
    }

    static JsonObject dirView(JsonObject row) {
        String path = row.getString("path");
        return new JsonObject().put("name", Paths.name(path)).put("path", path).put("kind", "dir").put("createdAt", row.getLong("createdAt", 0L));
    }

    static JsonObject fileView(FileInfo file) {
        return file.toViewJson().put("name", Paths.name(file.path())).put("kind", "file");
    }

    static HostException conflict(String code, String message) {
        return new HostException("Error", code, message);
    }

    // --- writing ---

    /** Hashes and counts what passes through, enforces the room it was given, and publishes the version when complete. */
    private final class Writer implements FileWriter {

        private final String tenant;
        private final String path;
        private final String contentType;
        private final String fileId;
        private final long allowance;
        private final BlobStore target;
        private final BlobWriter blob;
        private final MessageDigest sha256;
        private long size;
        private boolean done;

        Writer(String tenant, String path, String contentType, String fileId, long allowance, BlobStore target, BlobWriter blob) {
            this.tenant = tenant;
            this.path = path;
            this.contentType = contentType;
            this.fileId = fileId;
            this.allowance = allowance;
            this.target = target;
            this.blob = blob;
            try {
                this.sha256 = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized Future<Void> write(Buffer data) {
            if (done) {
                return Future.failedFuture("The upload is already finished");
            }
            size += data.length();
            if (size > allowance) {
                done = true;
                return discard().compose(v -> Future.failedFuture(quota()));
            }
            sha256.update(data.getBytes());
            return blob.write(data).recover(err -> {
                synchronized (this) {
                    done = true;
                }
                return discard().compose(v -> Future.failedFuture(err));
            });
        }

        @Override
        public synchronized Future<FileInfo> finish() {
            if (done) {
                return Future.failedFuture("The upload is already finished");
            }
            done = true;
            FileInfo info = new FileInfo(tenant, path, fileId, size, contentType, "\"" + HexFormat.of().formatHex(sha256.digest()) + "\"",
                    target.name(), System.currentTimeMillis());
            // Only a blob that never completed is discarded here. Once the metadata names it, it's live; if publishing
            // fails before that, its abandoned-upload row in the garbage table still cleans it up.
            return blob.finish()
                    .recover(err -> discard().compose(v -> Future.failedFuture(err)))
                    .compose(v -> putMeta(tenant, path, info.toJson()))
                    .compose(previous -> removeGarbage(fileId).compose(v -> previous == null || previous.getString("fileId").equals(fileId)
                            ? Future.succeededFuture()
                            : release(FileInfo.fromJson(previous))))
                    .onSuccess(v -> cluster93.core.metrics.Metrics.of(vertx).fileStored(cluster93.core.metrics.Metrics.backendKind(target.name()), size))
                    .map(info);
        }

        @Override
        public synchronized Future<Void> abort() {
            if (done) {
                return Future.succeededFuture();
            }
            done = true;
            return discard();
        }

        /** The blob goes, and its garbage row is made due now, in case deleting it failed. */
        private Future<Void> discard() {
            return blob.abort().recover(err -> Future.succeededFuture())
                    .compose(v -> putGarbage(fileId, target.name(), tenant, 0));
        }
    }
}
