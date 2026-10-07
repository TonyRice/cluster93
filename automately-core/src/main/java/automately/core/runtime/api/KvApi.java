package automately.core.runtime.api;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/** The tenant's key-value store, in namespaces ({@code kv}, {@code kv.namespace(name)}). Values are JSON. */
public interface KvApi {

    int MAX_NAME_BYTES = 512;
    int MAX_VALUE_BYTES = 256 * 1024;
    int MAX_LIST_LIMIT = 1_000;

    /** The value, or null if there's none. */
    Future<Object> get(String namespace, String key);

    /** @param ttlMs null for no expiry */
    Future<Void> set(String namespace, String key, Object value, Long ttlMs);

    /** Whether there was a value. */
    Future<Boolean> delete(String namespace, String key);

    /** Keys starting with {@code prefix}, in order, after {@code cursor}: {@code {keys: [...], cursor}}, cursor null on the last page. */
    Future<JsonObject> list(String namespace, String prefix, int limit, String cursor);

    /** Adds {@code by} to an integer value (0 if absent) and returns the new value. */
    Future<Long> increment(String namespace, String key, long by);
}
