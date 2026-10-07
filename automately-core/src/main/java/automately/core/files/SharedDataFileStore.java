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
    private final Future<AsyncMap<String, Long>> garbage;

    /** Bytes in the same shared maps, with the heap as the capacity that's watched (D-038). */
    public SharedDataFileStore(Vertx vertx) {
        this(vertx, StorageCapacity.heap(0.8));
    }

    public SharedDataFileStore(Vertx vertx, StorageCapacity capacity) {
        this(vertx, new SharedDataChunks(vertx, capacity));
    }

    public SharedDataFileStore(Vertx vertx, BlobStore blobs) {
        super(vertx, blobs);
        this.meta = vertx.sharedData().getAsyncMap("automately.files");
        this.garbage = vertx.sharedData().getAsyncMap("automately.file-garbage");
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
    protected Future<Long> sumSizes(String tenant) {
        return meta.compose(AsyncMap::values).map(values -> values.stream()
                .filter(v -> v.getString("tenant").equals(tenant)).mapToLong(v -> v.getLong("size")).sum());
    }

    @Override
    protected Future<Void> putGarbage(String fileId, long deleteAfter) {
        return garbage.compose(m -> m.put(fileId, deleteAfter));
    }

    @Override
    protected Future<Void> removeGarbage(String fileId) {
        return garbage.compose(m -> m.remove(fileId)).mapEmpty();
    }

    @Override
    protected Future<List<String>> dueGarbage(long now, int limit) {
        return garbage.compose(AsyncMap::entries).map(entries -> entries.entrySet().stream()
                .filter(e -> e.getValue() <= now).map(java.util.Map.Entry::getKey).limit(limit).toList());
    }
}
