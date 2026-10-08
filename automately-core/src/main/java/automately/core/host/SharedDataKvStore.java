package automately.core.host;

import io.vertx.core.Future;
import automately.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.List;

/**
 * {@link KvStore} on a Vert.x {@link AsyncMap}. Each entry stores its own {@code expiresAt}, checked on
 * every read; the map TTL (a little longer) only cleans up, because Hazelcast expires entries up to about
 * a second early (D-013). {@link #list} scans the map, which is fine until a durable store replaces this.
 */
public final class SharedDataKvStore implements KvStore {

    static final String MAP_NAME = "automately.kv";
    /** Can't appear in tenants, namespaces or keys (validated by the host API). */
    static final char SEPARATOR = '\u001f';
    static final long CLEANUP_SLACK_MS = 5_000;
    static final int MAX_INCREMENT_ATTEMPTS = 20;

    private final SharedMap<String, JsonObject> map;

    public SharedDataKvStore(Vertx vertx) {
        this.map = new SharedMap<>(vertx, MAP_NAME);
    }

    static String mapKey(String tenant, String namespace, String key) {
        return tenant + SEPARATOR + namespace + SEPARATOR + key;
    }

    private static boolean live(JsonObject entry, long now) {
        return entry != null && (entry.getLong("expiresAt") == null || entry.getLong("expiresAt") > now);
    }

    @Override
    public Future<Object> get(String tenant, String namespace, String key) {
        return map.compose(m -> m.get(mapKey(tenant, namespace, key)))
                .map(entry -> live(entry, System.currentTimeMillis()) ? entry.getValue("value") : null);
    }

    @Override
    public Future<Void> set(String tenant, String namespace, String key, Object value, Long ttlMs) {
        JsonObject entry = new JsonObject().put("value", value).put("size", KvStore.entrySize(namespace, key, value));
        if (ttlMs == null) {
            return map.compose(m -> m.put(mapKey(tenant, namespace, key), entry));
        }
        entry.put("expiresAt", System.currentTimeMillis() + ttlMs);
        return map.compose(m -> m.put(mapKey(tenant, namespace, key), entry, ttlMs + CLEANUP_SLACK_MS));
    }

    @Override
    public Future<Object> putIfAbsent(String tenant, String namespace, String key, Object value) {
        JsonObject entry = new JsonObject().put("value", value).put("size", KvStore.entrySize(namespace, key, value));
        String mapKey = mapKey(tenant, namespace, key);
        return map.compose(m -> m.putIfAbsent(mapKey, entry).compose(existing -> {
            if (existing == null) {
                return Future.succeededFuture(value);
            }
            if (live(existing, System.currentTimeMillis())) {
                return Future.succeededFuture(existing.getValue("value"));
            }
            // An expired entry doesn't count: replace it, unless someone else just did.
            return m.replaceIfPresent(mapKey, existing, entry)
                    .compose(replaced -> replaced ? Future.succeededFuture(value) : m.get(mapKey).map(now -> now == null ? null : now.getValue("value")));
        }));
    }

    @Override
    public Future<Boolean> delete(String tenant, String namespace, String key) {
        return map.compose(m -> m.remove(mapKey(tenant, namespace, key)))
                .map(previous -> live(previous, System.currentTimeMillis()));
    }

    @Override
    public Future<JsonObject> list(String tenant, String namespace, String prefix, int limit, String cursor) {
        String base = tenant + SEPARATOR + namespace + SEPARATOR;
        long now = System.currentTimeMillis();
        return map.compose(AsyncMap::entries).map(entries -> {
            List<String> keys = entries.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(base + prefix) && live(e.getValue(), now))
                    .map(e -> e.getKey().substring(base.length()))
                    .filter(k -> cursor == null || k.compareTo(cursor) > 0)
                    .sorted()
                    .limit(limit + 1L)
                    .toList();
            boolean more = keys.size() > limit;
            List<String> page = more ? keys.subList(0, limit) : keys;
            return new JsonObject().put("keys", new JsonArray(page)).put("cursor", more ? page.getLast() : null);
        });
    }

    @Override
    public Future<Long> usage(String tenant) {
        String base = tenant + SEPARATOR;
        long now = System.currentTimeMillis();
        return map.compose(AsyncMap::entries).map(entries -> entries.entrySet().stream()
                .filter(e -> e.getKey().startsWith(base) && live(e.getValue(), now))
                .mapToLong(e -> e.getValue().getLong("size", 0L))
                .sum());
    }

    private static String namespaceOf(String mapKey) {
        return mapKey.split(String.valueOf(SEPARATOR), 3)[1];
    }

    private static String keyOf(String mapKey) {
        return mapKey.split(String.valueOf(SEPARATOR), 3)[2];
    }

    @Override
    public Future<Long> increment(String tenant, String namespace, String key, long by) {
        String mapKey = mapKey(tenant, namespace, key);
        return map.compose(m -> increment(m, mapKey, by, 1));
    }

    private Future<Long> increment(AsyncMap<String, JsonObject> m, String mapKey, long by, int tries) {
        long now = System.currentTimeMillis();
        return m.get(mapKey).compose(current -> {
            boolean exists = live(current, now);
            Object value = exists ? current.getValue("value") : null;
            if (value != null && !(value instanceof Number)) {
                return Future.failedFuture(new automately.core.runtime.HostException("TypeError", "not_a_number",
                        "kv.increment needs a numeric value; the key holds " + value.getClass().getSimpleName()));
            }
            long next = (value == null ? 0 : ((Number) value).longValue()) + by;
            JsonObject entry = new JsonObject().put("value", next).put("size", KvStore.entrySize(namespaceOf(mapKey), keyOf(mapKey), next));
            if (exists && current.getLong("expiresAt") != null) {
                entry.put("expiresAt", current.getLong("expiresAt"));
            }
            Future<Boolean> written = current == null
                    ? m.putIfAbsent(mapKey, entry).map(previous -> previous == null)
                    : m.replaceIfPresent(mapKey, current, entry);
            return written.compose(ok -> {
                if (ok) {
                    return Future.succeededFuture(next);
                }
                if (tries >= MAX_INCREMENT_ATTEMPTS) {
                    return Future.failedFuture("Gave up incrementing after " + tries + " conflicting writes");
                }
                return increment(m, mapKey, by, tries + 1);
            });
        });
    }
}
