package automately.core.host;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * Tenant-scoped key-value storage behind the script's {@code kv} API. Keys live in namespaces; values
 * are JSON. Expiry is exact: an expired entry is never returned, whatever the backing map's TTL does.
 */
public interface KvStore {

    /** Completes with the value, or null if there is none (or it expired). */
    Future<Object> get(String tenant, String namespace, String key);

    /** {@code ttlMs} null means no expiry. */
    Future<Void> set(String tenant, String namespace, String key, Object value, Long ttlMs);

    /** Completes with whether a live entry was removed. */
    Future<Boolean> delete(String tenant, String namespace, String key);

    /**
     * Keys in a namespace starting with {@code prefix}, in order, after {@code cursor} (exclusive).
     * Completes with {@code {"keys": [...], "cursor": next-or-null}}.
     */
    Future<JsonObject> list(String tenant, String namespace, String prefix, int limit, String cursor);

    /** Atomically adds {@code by} to a numeric value (missing counts as 0) and returns the new value. */
    Future<Long> increment(String tenant, String namespace, String key, long by);

    /** Bytes a tenant's live entries take, by {@link #entrySize}; counted against its storage quota (D-043). */
    Future<Long> usage(String tenant);

    /** What an entry counts for: its namespace, key and JSON value, in UTF-8 bytes. */
    static long entrySize(String namespace, String key, Object value) {
        return utf8(namespace) + utf8(key) + utf8(io.vertx.core.json.Json.encode(value));
    }

    private static long utf8(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
