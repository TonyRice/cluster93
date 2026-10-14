package cluster93.core.files;

import io.vertx.core.Future;
import cluster93.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;

/** {@link ClusterFileStore} metadata on Vert.x {@link AsyncMap}s. For development and tests; listings scan. */
public final class SharedDataFileStore extends ClusterFileStore {

    private static final char SEPARATOR = '\u001f';

    private final SharedMap<String, JsonObject> meta;
    private final SharedMap<String, JsonObject> garbage;
    private final SharedMap<String, JsonObject> dirs;
    private final SharedMap<String, Long> refs;

    /** Bytes in the same shared maps, with the heap as the capacity that's watched (D-038). */
    public SharedDataFileStore(Vertx vertx) {
        this(vertx, StorageCapacity.heap(0.8));
    }

    public SharedDataFileStore(Vertx vertx, StorageCapacity capacity) {
        this(vertx, new SharedDataChunks(vertx, capacity));
    }

    public SharedDataFileStore(Vertx vertx, BlobStore blobs) {
        this(vertx, BlobStores.single(blobs));
    }

    public SharedDataFileStore(Vertx vertx, BlobStores stores) {
        super(vertx, stores);
        this.meta = new SharedMap<>(vertx, "cluster93.files");
        this.garbage = new SharedMap<>(vertx, "cluster93.file-garbage");
        this.dirs = new SharedMap<>(vertx, "cluster93.dirs");
        this.refs = new SharedMap<>(vertx, "cluster93.blob-refs");
    }

    private static String key(String tenant, String path) {
        return tenant + SEPARATOR + path;
    }

    @Override
    protected Future<JsonObject> getMeta(String tenant, String path) {
        return meta.compose(m -> m.get(key(tenant, path)));
    }

    @Override
    protected Future<JsonObject> putMeta(String tenant, String path, JsonObject value) {
        return meta.compose(m -> m.get(key(tenant, path)).compose(previous -> m.put(key(tenant, path), value).map(previous)));
    }

    @Override
    protected Future<JsonObject> removeMeta(String tenant, String path) {
        return meta.compose(m -> m.remove(key(tenant, path)));
    }

    @Override
    protected Future<List<JsonObject>> listMeta(String tenant, String prefix, int limit, String cursor) {
        return meta.compose(AsyncMap::values).map(values -> values.stream()
                .filter(v -> v.getString("tenant").equals(tenant) && v.getString("path").startsWith(prefix)
                        && (cursor == null || v.getString("path").compareTo(cursor) > 0))
                .sorted(Comparator.comparing(v -> v.getString("path")))
                .limit(limit)
                .toList());
    }

    @Override
    protected Future<Long> sumOwnBytes(String tenant) {
        return meta.compose(AsyncMap::values).map(values -> distinctBlobs(values.stream()
                .filter(v -> v.getString("tenant").equals(tenant) && !FileInfo.fromJson(v).backend().startsWith("provider:"))));
    }

    @Override
    protected Future<Long> sumBytesIn(String tenant, List<String> backends) {
        return meta.compose(AsyncMap::values).map(values -> distinctBlobs(values.stream()
                .filter(v -> v.getString("tenant").equals(tenant) && backends.contains(FileInfo.fromJson(v).backend()))));
    }

    /** Copies share a blob, so each blob's size counts once (D-077). */
    private static long distinctBlobs(java.util.stream.Stream<JsonObject> rows) {
        java.util.Map<String, Long> sizes = new java.util.HashMap<>();
        rows.forEach(v -> sizes.put(v.getString("fileId", v.getString("path")), v.getLong("size")));
        return sizes.values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    protected Future<Boolean> removeMetaIfUnchanged(String tenant, String path, JsonObject expected) {
        return meta.compose(m -> m.removeIfPresent(key(tenant, path), expected));
    }

    @Override
    protected Future<Long> addRefs(String fileId, long delta) {
        return refs.compose(m -> m.get(fileId).compose(current -> {
            long next = (current == null ? 1 : current) + delta;
            Future<Boolean> stored = current == null
                    ? m.putIfAbsent(fileId, next).map(previous -> previous == null)
                    : m.replaceIfPresent(fileId, current, next);
            return stored.compose(ok -> ok ? Future.succeededFuture(next) : addRefs(fileId, delta));
        }));
    }

    @Override
    protected Future<Long> refs(String fileId) {
        return refs.compose(m -> m.get(fileId));
    }

    @Override
    protected Future<Void> removeRefs(String fileId) {
        return refs.compose(m -> m.remove(fileId)).mapEmpty();
    }

    @Override
    protected Future<Long> countFilesIn(String backend) {
        return meta.compose(AsyncMap::values).map(values -> values.stream().filter(v -> FileInfo.fromJson(v).backend().equals(backend)).count());
    }

    @Override
    protected Future<JsonObject> getDir(String tenant, String path) {
        return dirs.compose(m -> m.get(key(tenant, path)));
    }

    @Override
    protected Future<Boolean> putDirIfAbsent(String tenant, String path, JsonObject row) {
        return dirs.compose(m -> m.putIfAbsent(key(tenant, path), row)).map(previous -> previous == null);
    }

    @Override
    protected Future<JsonObject> removeDir(String tenant, String path) {
        return dirs.compose(m -> m.remove(key(tenant, path)));
    }

    @Override
    protected Future<List<JsonObject>> childDirs(String tenant, String parent, String after, int limit) {
        return children(dirs, tenant, parent, after, limit);
    }

    @Override
    protected Future<List<JsonObject>> childFiles(String tenant, String parent, String after, int limit) {
        return children(meta, tenant, parent, after, limit);
    }

    private static Future<List<JsonObject>> children(SharedMap<String, JsonObject> map, String tenant, String parent, String after, int limit) {
        return map.compose(AsyncMap::values).map(values -> values.stream()
                .filter(v -> v.getString("tenant").equals(tenant) && Paths.parent(v.getString("path")).equals(parent)
                        && (after == null || Paths.name(v.getString("path")).compareTo(after) > 0))
                .sorted(Comparator.comparing(v -> Paths.name(v.getString("path"))))
                .limit(limit)
                .toList());
    }

    @Override
    protected Future<Void> putGarbage(String fileId, String backend, String tenant, long deleteAfter) {
        return garbage.compose(m -> m.put(fileId, new JsonObject().put("fileId", fileId).put("backend", backend).put("tenant", tenant)
                .put("deleteAfter", deleteAfter)));
    }

    @Override
    protected Future<Void> removeGarbage(String fileId) {
        return garbage.compose(m -> m.remove(fileId)).mapEmpty();
    }

    @Override
    protected Future<List<JsonObject>> dueGarbage(long now, int limit) {
        return garbage.compose(AsyncMap::values).map(rows -> rows.stream()
                .filter(r -> r.getLong("deleteAfter") <= now).limit(limit).toList());
    }
}
