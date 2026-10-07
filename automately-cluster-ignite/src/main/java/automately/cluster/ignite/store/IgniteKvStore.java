package automately.cluster.ignite.store;

import automately.core.host.KvStore;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code kv} in a durable, replicated table indexed by (tenant, namespace, key), so {@code list} is an index
 * range scan. Each row carries its own {@code expiresAt}, which every read checks (D-027); Ignite's expiry
 * only cleans up.
 */
public final class IgniteKvStore implements KvStore {

    static final Table.Definition TABLE = new Table.Definition("KV",
            Table.columns("tenant", String.class, "ns", String.class, "k", String.class, "expiresAt", Long.class, "size", Long.class),
            List.of(Table.index("tenant", "ns", "k")));
    static final long CLEANUP_SLACK_MS = 5_000;
    static final char SEPARATOR = '\u001f';

    private final Table table;

    public IgniteKvStore(Vertx vertx, Ignite ignite) {
        this.table = new Table(vertx, ignite, TABLE, json -> {
            Map<String, Object> columns = new HashMap<>();
            columns.put("tenant", json.getString("tenant"));
            columns.put("ns", json.getString("ns"));
            columns.put("k", json.getString("k"));
            columns.put("expiresAt", json.getLong("expiresAt"));
            columns.put("size", json.getLong("size"));
            return columns;
        });
    }

    static String key(String tenant, String namespace, String key) {
        return tenant + SEPARATOR + namespace + SEPARATOR + key;
    }

    private static boolean live(JsonObject row, long now) {
        return row != null && (row.getLong("expiresAt") == null || row.getLong("expiresAt") > now);
    }

    private static JsonObject row(String tenant, String namespace, String key, Object value, Long expiresAt) {
        return new JsonObject().put("tenant", tenant).put("ns", namespace).put("k", key).put("value", value).put("expiresAt", expiresAt)
                .put("size", KvStore.entrySize(namespace, key, value));
    }

    @Override
    public Future<Object> get(String tenant, String namespace, String key) {
        return table.get(key(tenant, namespace, key)).map(row -> live(row, System.currentTimeMillis()) ? row.getValue("value") : null);
    }

    @Override
    public Future<Void> set(String tenant, String namespace, String key, Object value, Long ttlMs) {
        if (ttlMs == null) {
            return table.put(key(tenant, namespace, key), row(tenant, namespace, key, value, null));
        }
        long expiresAt = System.currentTimeMillis() + ttlMs;
        return table.put(key(tenant, namespace, key), row(tenant, namespace, key, value, expiresAt), ttlMs + CLEANUP_SLACK_MS);
    }

    @Override
    public Future<Object> putIfAbsent(String tenant, String namespace, String key, Object value) {
        String rowKey = key(tenant, namespace, key);
        JsonObject row = row(tenant, namespace, key, value, null);
        return table.putIfAbsent(rowKey, row).compose(created -> created ? Future.succeededFuture(value)
                : table.update(rowKey, current -> live(current, System.currentTimeMillis()) ? Optional.empty() : Optional.of(row))
                        .compose(replaced -> replaced.isPresent() ? Future.succeededFuture(value)
                                : table.get(rowKey).map(current -> current == null ? null : current.getValue("value"))));
    }

    @Override
    public Future<Boolean> delete(String tenant, String namespace, String key) {
        return table.remove(key(tenant, namespace, key)).map(previous -> live(previous, System.currentTimeMillis()));
    }

    @Override
    public Future<JsonObject> list(String tenant, String namespace, String prefix, int limit, String cursor) {
        long now = System.currentTimeMillis();
        StringBuilder sql = new StringBuilder("SELECT k FROM KV WHERE tenant = ? AND ns = ? AND (expiresAt IS NULL OR expiresAt > ?)");
        List<Object> args = new java.util.ArrayList<>(List.of(tenant, namespace, now));
        if (!prefix.isEmpty()) {
            sql.append(" AND k >= ? AND k < ?");
            args.add(prefix);
            args.add(prefix + Character.MAX_VALUE);
        }
        if (cursor != null) {
            sql.append(" AND k > ?");
            args.add(cursor);
        }
        sql.append(" ORDER BY k LIMIT ?");
        args.add(limit + 1);
        return table.query(sql.toString(), args.toArray()).map(rows -> {
            List<String> keys = rows.stream().map(r -> (String) r.getFirst()).toList();
            boolean more = keys.size() > limit;
            List<String> page = more ? keys.subList(0, limit) : keys;
            return new JsonObject().put("keys", new JsonArray(page)).put("cursor", more ? page.getLast() : null);
        });
    }

    @Override
    public Future<Long> usage(String tenant) {
        return table.query("SELECT COALESCE(SUM(size), 0) FROM KV WHERE tenant = ? AND (expiresAt IS NULL OR expiresAt > ?)",
                tenant, System.currentTimeMillis()).map(rows -> ((Number) rows.getFirst().getFirst()).longValue());
    }

    @Override
    public Future<Long> increment(String tenant, String namespace, String key, long by) {
        String rowKey = key(tenant, namespace, key);
        long[] result = new long[1];
        return table.putIfAbsent(rowKey, row(tenant, namespace, key, by, null)).compose(created -> {
            if (created) {
                return Future.succeededFuture(by);
            }
            return table.update(rowKey, current -> {
                long now = System.currentTimeMillis();
                Object value = live(current, now) ? current.getValue("value") : null;
                if (value != null && !(value instanceof Number)) {
                    throw new HostException("TypeError", "not_a_number",
                            "kv.increment needs a numeric value; the key holds " + value.getClass().getSimpleName());
                }
                result[0] = (value == null ? 0 : ((Number) value).longValue()) + by;
                Long expiresAt = live(current, now) ? current.getLong("expiresAt") : null;
                return Optional.of(row(tenant, namespace, key, result[0], expiresAt));
            }).map(updated -> result[0]);
        });
    }
}
