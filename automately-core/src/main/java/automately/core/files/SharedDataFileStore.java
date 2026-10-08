package automately.core.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;

/** {@link ClusterFileStore} metadata on Vert.x {@link AsyncMap}s. For development and tests; listings scan. */
public final class SharedDataFileStore extends ClusterFileStore {

    private static final char SEPARATOR = '\u001f';

    private final Future<AsyncMap<String, JsonObject>> meta;
    private final Future<AsyncMap<String, JsonObject>> garbage;
    private final Future<AsyncMap<String, JsonObject>> dirs;

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
        this.meta = vertx.sharedData().getAsyncMap("automately.files");
        this.garbage = vertx.sharedData().getAsyncMap("automately.file-garbage");
        this.dirs = vertx.sharedData().getAsyncMap("automately.dirs");
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
        return meta.compose(AsyncMap::values).map(values -> values.stream()
                .filter(v -> v.getString("tenant").equals(tenant) && !FileInfo.fromJson(v).backend().startsWith("provider:"))
                .mapToLong(v -> v.getLong("size")).sum());
    }

    @Override
    protected Future<Long> sumBytesIn(String tenant, List<String> backends) {
        return meta.compose(AsyncMap::values).map(values -> values.stream()
                .filter(v -> v.getString("tenant").equals(tenant) && backends.contains(FileInfo.fromJson(v).backend()))
                .mapToLong(v -> v.getLong("size")).sum());
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

    private static Future<List<JsonObject>> children(Future<AsyncMap<String, JsonObject>> map, String tenant, String parent, String after, int limit) {
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
