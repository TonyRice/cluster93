package automately.core.host;

import automately.core.runtime.HostException;
import automately.core.runtime.api.KvApi;

import java.nio.charset.StandardCharsets;

/** Checks for the names scripts choose: kv namespaces, keys and prefixes. */
final class Names {

    private Names() {
    }

    static String name(String value, String field, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.isEmpty())) {
            throw HostException.invalid("\"" + field + "\" must be a " + (allowEmpty ? "" : "non-empty ") + "string");
        }
        if (value.indexOf(SharedDataKvStore.SEPARATOR) >= 0) {
            throw HostException.invalid("\"" + field + "\" contains a forbidden character");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > KvApi.MAX_NAME_BYTES) {
            throw HostException.limit("\"" + field + "\" is over " + KvApi.MAX_NAME_BYTES + " bytes");
        }
        return value;
    }

    static int limit(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw HostException.invalid("\"limit\" must be between 1 and " + max);
        }
        return limit;
    }

    /** Runs {@code call}, turning a validation exception into a failed future. */
    static <T> io.vertx.core.Future<T> guarded(java.util.function.Supplier<io.vertx.core.Future<T>> call) {
        try {
            return call.get();
        } catch (HostException e) {
            return io.vertx.core.Future.failedFuture(e);
        }
    }
}
