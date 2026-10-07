package automately.cluster.ignite.store;

import automately.cluster.ignite.IgniteCluster;
import automately.core.files.ChunkedFileStore;
import automately.core.files.StorageCapacity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.CacheConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Files in the cluster's durable store: metadata in an indexed table (tenant, path), 1 MB chunks in their own
 * replicated cache (raw bytes), and a garbage table for versions waiting to be deleted. Any node can stream any file,
 * and files survive node loss and full restarts.
 */
public final class IgniteFileStore extends ChunkedFileStore {

    static final Table.Definition FILES = new Table.Definition("FILES",
            Table.columns("tenant", String.class, "path", String.class, "size", Long.class), List.of(Table.index("tenant", "path")));
    static final Table.Definition GARBAGE = new Table.Definition("FILE_GARBAGE",
            Table.columns("deleteAfter", Long.class), List.of(Table.index("deleteAfter")));
    static final String CHUNKS = "FILE_CHUNKS";
    private static final char SEPARATOR = '\u001f';

    private final Table files;
    private final Table garbage;
    private final IgniteCache<String, byte[]> chunks;

    public IgniteFileStore(Vertx vertx, Ignite ignite, StorageCapacity capacity) {
        super(vertx, capacity);
        this.files = new Table(vertx, ignite, FILES, json -> Map.of("tenant", json.getString("tenant"), "path", json.getString("path"),
                "size", json.getLong("size")));
        this.garbage = new Table(vertx, ignite, GARBAGE, json -> Map.of("deleteAfter", json.getLong("deleteAfter")));
        this.chunks = ignite.cache(CHUNKS);
    }

    /** The chunk cache: raw bytes, replicated like every durable table. Blocking; called at startup. */
    static void createChunkCache(Ignite ignite, int backups) {
        ignite.getOrCreateCache(new CacheConfiguration<String, byte[]>(CHUNKS)
                .setCacheMode(CacheMode.PARTITIONED)
                .setBackups(backups)
                .setAffinity(new org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction(false, IgniteCluster.PARTITIONS))
                .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                .setWriteSynchronizationMode(org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC)
                .setDataRegionName(IgniteCluster.DATA_REGION));
    }

    private static String key(String tenant, String path) {
        return tenant + SEPARATOR + path;
    }

    @Override
    protected Future<JsonObject> getMeta(String tenant, String path) {
        return files.get(key(tenant, path));
    }

    @Override
    protected Future<JsonObject> putMeta(String tenant, String path, JsonObject meta) {
        return files.getAndPut(key(tenant, path), meta);
    }

    @Override
    protected Future<JsonObject> removeMeta(String tenant, String path) {
        return files.remove(key(tenant, path));
    }

    @Override
    protected Future<List<JsonObject>> listMeta(String tenant, String prefix, int limit, String cursor) {
        StringBuilder sql = new StringBuilder("SELECT json FROM FILES WHERE tenant = ?");
        List<Object> args = new ArrayList<>(List.of(tenant));
        if (!prefix.isEmpty()) {
            sql.append(" AND path >= ? AND path < ?");
            args.add(prefix);
            args.add(prefix + Character.MAX_VALUE);
        }
        if (cursor != null) {
            sql.append(" AND path > ?");
            args.add(cursor);
        }
        sql.append(" ORDER BY path LIMIT ?");
        args.add(limit);
        return files.queryJson(sql.toString(), args.toArray());
    }

    @Override
    protected Future<Long> sumSizes(String tenant) {
        return files.query("SELECT SUM(size) FROM FILES WHERE tenant = ?", tenant)
                .map(rows -> rows.isEmpty() || rows.getFirst().getFirst() == null ? 0L : ((Number) rows.getFirst().getFirst()).longValue());
    }

    @Override
    protected Future<Void> putChunk(String fileId, int index, Buffer data) {
        return IgniteFutures.async(vertx, () -> chunks.putAsync(fileId + ":" + index, data.getBytes()));
    }

    @Override
    protected Future<Buffer> getChunk(String fileId, int index) {
        return IgniteFutures.async(vertx, () -> chunks.getAsync(fileId + ":" + index)).map(bytes -> bytes == null ? null : Buffer.buffer(bytes));
    }

    @Override
    protected Future<Void> removeChunk(String fileId, int index) {
        return IgniteFutures.async(vertx, () -> chunks.removeAsync(fileId + ":" + index)).mapEmpty();
    }

    @Override
    protected Future<Void> putGarbage(String fileId, long deleteAfter) {
        return garbage.put(fileId, new JsonObject().put("fileId", fileId).put("deleteAfter", deleteAfter));
    }

    @Override
    protected Future<Void> removeGarbage(String fileId) {
        return garbage.remove(fileId).mapEmpty();
    }

    @Override
    protected Future<List<String>> dueGarbage(long now, int limit) {
        return garbage.queryJson("SELECT json FROM FILE_GARBAGE WHERE deleteAfter <= ? LIMIT ?", now, limit)
                .map(rows -> rows.stream().map(r -> r.getString("fileId")).toList());
    }
}
