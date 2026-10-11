package automately.cluster.ignite.store;

import automately.core.files.BlobStore;
import automately.core.files.BlobStores;
import automately.core.files.FileInfo;
import automately.core.files.ClusterFileStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * File metadata in the cluster's durable store: an indexed table (tenant, path) and a garbage table for versions
 * waiting to be deleted. The bytes are in whichever {@link BlobStore} is configured: by default {@link IgniteChunks},
 * replicated alongside (D-040, D-056).
 */
public final class IgniteFileStore extends ClusterFileStore {

    static final Table.Definition FILES = new Table.Definition("FILES",
            Table.columns("tenant", String.class, "path", String.class, "size", Long.class, "backend", String.class,
                    "parent", String.class, "name", String.class, "fileId", String.class),
            List.of(Table.index("tenant", "path"), Table.index("backend"), Table.index("tenant", "parent", "name")));
    /** How many file rows name each blob, once copies share them (D-077). */
    static final Table.Definition REFS = new Table.Definition("BLOB_REFS", Table.columns("refCount", Long.class), List.of());
    /** Directories (D-076): one row each, so empty ones exist and a level lists from an index. */
    static final Table.Definition DIRS = new Table.Definition("DIRS",
            Table.columns("tenant", String.class, "parent", String.class, "name", String.class),
            List.of(Table.index("tenant", "parent", "name")));
    static final Table.Definition GARBAGE = new Table.Definition("FILE_GARBAGE",
            Table.columns("deleteAfter", Long.class), List.of(Table.index("deleteAfter")));
    private static final char SEPARATOR = '\u001f';

    private final Table files;
    private final Table garbage;
    private final Table dirs;
    private final Table refs;

    public IgniteFileStore(Vertx vertx, Ignite ignite, BlobStores stores) {
        super(vertx, stores);
        this.files = new Table(vertx, ignite, FILES, json -> Map.of("tenant", json.getString("tenant"), "path", json.getString("path"),
                "size", json.getLong("size"), "backend", FileInfo.fromJson(json).backend(),
                "parent", automately.core.files.Paths.parent(json.getString("path")), "name", automately.core.files.Paths.name(json.getString("path")),
                "fileId", json.getString("fileId")));
        this.refs = new Table(vertx, ignite, REFS, json -> Map.of("refCount", json.getLong("count")));
        this.dirs = new Table(vertx, ignite, DIRS, json -> Map.of("tenant", json.getString("tenant"),
                "parent", automately.core.files.Paths.parent(json.getString("path")), "name", automately.core.files.Paths.name(json.getString("path"))));
        this.garbage = new Table(vertx, ignite, GARBAGE, json -> Map.of("deleteAfter", json.getLong("deleteAfter")));
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
    protected Future<Long> sumOwnBytes(String tenant) {
        // Rows from before backends were recorded have no backend: they're in the cluster.
        // Copies share a blob, so each blob counts once (D-077).
        return sum("SELECT SUM(size) FROM (SELECT MAX(size) AS size FROM FILES WHERE tenant = ? AND (backend IS NULL OR backend NOT LIKE 'provider:%')"
                + " GROUP BY COALESCE(fileId, path))", tenant);
    }

    @Override
    protected Future<Long> sumBytesIn(String tenant, List<String> backends) {
        String marks = String.join(", ", java.util.Collections.nCopies(backends.size(), "?"));
        List<Object> args = new ArrayList<>(List.of(tenant));
        args.addAll(backends);
        return sum("SELECT SUM(size) FROM (SELECT MAX(size) AS size FROM FILES WHERE tenant = ? AND backend IN (" + marks + ")"
                + " GROUP BY COALESCE(fileId, path))", args.toArray());
    }

    @Override
    protected Future<Long> countFilesIn(String backend) {
        return sum("SELECT COUNT(*) FROM FILES WHERE backend = ?", backend);
    }

    private Future<Long> sum(String sql, Object... args) {
        return files.query(sql, args).map(rows -> rows.isEmpty() || rows.getFirst().getFirst() == null ? 0L : ((Number) rows.getFirst().getFirst()).longValue());
    }

    @Override
    protected Future<Boolean> removeMetaIfUnchanged(String tenant, String path, JsonObject expected) {
        return files.removeIfUnchanged(key(tenant, path), expected);
    }

    @Override
    protected Future<Long> addRefs(String fileId, long delta) {
        return refs.putIfAbsent(fileId, new JsonObject().put("count", 1L + delta)).compose(created -> created
                ? Future.succeededFuture(1L + delta)
                : refs.update(fileId, json -> java.util.Optional.of(json.put("count", json.getLong("count") + delta)))
                        .compose(updated -> updated.isPresent() ? Future.succeededFuture(updated.get().getLong("count")) : addRefs(fileId, delta)));
    }

    @Override
    protected Future<Long> refs(String fileId) {
        return refs.get(fileId).map(json -> json == null ? null : json.getLong("count"));
    }

    @Override
    protected Future<Void> removeRefs(String fileId) {
        return refs.remove(fileId).mapEmpty();
    }

    @Override
    protected Future<JsonObject> getDir(String tenant, String path) {
        return dirs.get(key(tenant, path));
    }

    @Override
    protected Future<Boolean> putDirIfAbsent(String tenant, String path, JsonObject row) {
        return dirs.putIfAbsent(key(tenant, path), row);
    }

    @Override
    protected Future<JsonObject> removeDir(String tenant, String path) {
        return dirs.remove(key(tenant, path));
    }

    @Override
    protected Future<List<JsonObject>> childDirs(String tenant, String parent, String after, int limit) {
        return children(dirs, "DIRS", tenant, parent, after, limit);
    }

    @Override
    protected Future<List<JsonObject>> childFiles(String tenant, String parent, String after, int limit) {
        return children(files, "FILES", tenant, parent, after, limit);
    }

    private static Future<List<JsonObject>> children(Table table, String name, String tenant, String parent, String after, int limit) {
        return after == null
                ? table.queryJson("SELECT json FROM " + name + " WHERE tenant = ? AND parent = ? ORDER BY name LIMIT ?", tenant, parent, limit)
                : table.queryJson("SELECT json FROM " + name + " WHERE tenant = ? AND parent = ? AND name > ? ORDER BY name LIMIT ?",
                        tenant, parent, after, limit);
    }

    /**
     * Brings files written before directories existed into the tree (D-076): fills in their parent and name columns,
     * and makes their folders. Idempotent and cheap once done, so every node runs it at start.
     */
    public Future<Integer> backfillTree() {
        return files.queryJson("SELECT json FROM FILES WHERE parent IS NULL OR fileId IS NULL LIMIT 1000").compose(rows -> {
            Future<Void> all = Future.succeededFuture();
            for (JsonObject row : rows) {
                String tenant = row.getString("tenant");
                String path = row.getString("path");
                all = all.compose(v -> files.update(key(tenant, path), json -> java.util.Optional.of(json)).mapEmpty())
                        .compose(v -> ensureDirs(tenant, automately.core.files.Paths.parent(path)).recover(err -> Future.succeededFuture()));
            }
            return all.compose(v -> rows.size() < 1000 ? Future.succeededFuture(rows.size()) : backfillTree().map(more -> more + rows.size()));
        });
    }

    @Override
    protected Future<Void> putGarbage(String fileId, String backend, String tenant, long deleteAfter) {
        return garbage.put(fileId, new JsonObject().put("fileId", fileId).put("backend", backend).put("tenant", tenant).put("deleteAfter", deleteAfter));
    }

    @Override
    protected Future<Void> removeGarbage(String fileId) {
        return garbage.remove(fileId).mapEmpty();
    }

    @Override
    protected Future<List<JsonObject>> dueGarbage(long now, int limit) {
        return garbage.queryJson("SELECT json FROM FILE_GARBAGE WHERE deleteAfter <= ? LIMIT ?", now, limit);
    }
}
