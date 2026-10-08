package automately.core.identity;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A durable collection of JSON records by key, with equality lookups on a few indexed string fields. Identity's logic
 * is written once against this (D-070); the in-memory and Ignite implementations pass one contract.
 */
public interface Records {

    /** A collection's name and the fields it can be looked up by. */
    record Kind(String name, List<String> indexed) {
    }

    /** Opens a collection. */
    interface Factory {
        Records open(Kind kind);
    }

    /** Null if there's none. */
    Future<JsonObject> get(String key);

    Future<Void> put(String key, JsonObject value);

    /** Completes with whether it was stored (false if the key existed). */
    Future<Boolean> putIfAbsent(String key, JsonObject value);

    /** Completes with what was removed, or null. */
    Future<JsonObject> remove(String key);

    /**
     * Compare-and-set: applies {@code change} to the current record and writes the result only if nobody changed it
     * meanwhile, retrying otherwise. Empty if there's no record or the change returns empty.
     */
    Future<Optional<JsonObject>> update(String key, Function<JsonObject, Optional<JsonObject>> change);

    /** Every record whose indexed {@code field} equals {@code value}. */
    Future<List<JsonObject>> where(String field, String value);

    Future<List<JsonObject>> all();
}
