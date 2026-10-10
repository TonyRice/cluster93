package automately.runtime.js;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * One argument a script passed to a {@link HostFunction}, read the way the bindings always have (D-126). Only valid for
 * the duration of the call, on the run's thread.
 */
public interface JsArg {

    /** undefined, null, or no argument at all. */
    boolean isNullish();

    /** The value if it's a string, else null. */
    String string();

    /** The value if it's a boolean, else null. */
    Boolean bool();

    /** JavaScript truthiness. */
    boolean truthy();

    /** The value if the engine holds it as a small (32-bit) integer, else null. */
    Integer smallInteger();

    /** The value if it's any number, else null. */
    Double number();

    /** The value if it's a BigInt that fits in a long, else null. */
    Long bigint();

    /**
     * Bytes: a typed array's own view (its offset and length, not its whole buffer), a string as UTF-8, or anything else
     * as its string form.
     */
    byte[] bytes();

    /** A JSON-compatible Java value (JsonObject, JsonArray, String, Number, Boolean) or null for undefined, null and functions. */
    Object json();

    /** An options object; anything else counts as no options. */
    default JsonObject options() {
        return json() instanceof JsonObject object ? object : new JsonObject();
    }

    /** A list; anything else counts as an empty one. */
    default JsonArray list() {
        return json() instanceof JsonArray array ? array : new JsonArray();
    }
}
