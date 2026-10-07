package automately.core.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;

/** {@link ChunkedFileStore} on Vert.x {@link AsyncMap}s. For development and tests; listings scan. */
public final class SharedDataFileStore extends ChunkedFileStore {

    private static final char SEPARATOR = '\u001f';

    private final Future<AsyncMap<String, JsonObject>> meta;
    private final Future<AsyncMap<String, Buffer>> chunks;
    private final Future<AsyncMap<String, Long>> garbage;

    public SharedDataFileStore(Vertx vertx, long quotaBytes) {
        this(vertx, quotaBytes, StorageCapacity.heap(0.8));
    }

    public SharedDataFileStore(Vertx vertx, long quotaBytes, StorageCapacity capacity) {
        super(vertx, quotaBytes, capacity);
        this.meta = vertx.sharedData().getAsyncMap("automately.files");
        this.chunks = vertx.sharedData().getAsyncMap("automately.file-chunks");
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
    protected Future<Void> putChunk(String fileId, int index, Buffer data) {
        return chunks.compose(m -> m.put(fileId + ":" + index, data));
    }

    @Override
    protected Future<Buffer> getChunk(String fileId, int index) {
        return chunks.compose(m -> m.get(fileId + ":" + index));
    }

    @Override
    protected Future<Void> removeChunk(String fileId, int index) {
        return chunks.compose(m -> m.remove(fileId + ":" + index)).mapEmpty();
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
