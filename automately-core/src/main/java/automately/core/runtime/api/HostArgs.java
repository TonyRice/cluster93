package automately.core.runtime.api;

import automately.core.runtime.HostException;
import io.vertx.core.json.Json;

import java.nio.charset.StandardCharsets;

/**
 * Converts a script's loosely typed arguments (already decoded to JSON-compatible Java values) into what the APIs
 * take, with the same errors in every language: a binding calls these instead of casting.
 */
public final class HostArgs {

    private HostArgs() {
    }

    /** The value if it's a string, else null (which the API then reports as missing). */
    public static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    /** A positive number of milliseconds, or null if absent. */
    public static Long positiveMillis(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number n) || n.longValue() < 1) {
            throw HostException.invalid("\"" + field + "\" must be a positive number of milliseconds");
        }
        return n.longValue();
    }

    public static int intBetween(Object value, String field, int min, int max) {
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < min || n.intValue() > max) {
            throw HostException.invalid("\"" + field + "\" must be between " + min + " and " + max);
        }
        return n.intValue();
    }

    public static long integer(Object value, String field) {
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw HostException.invalid("\"" + field + "\" must be an integer");
        }
        return n.longValue();
    }

    /** A JSON value of at most {@code maxBytes} once encoded. */
    public static Object json(Object value, int maxBytes) {
        if (Json.encode(value).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw HostException.limit("The value is over " + maxBytes + " bytes of JSON");
        }
        return value;
    }
}
