package cluster93.core.runtime;

import io.vertx.core.json.JsonObject;

/** Receives output from a running script. May be called from any thread. */
@FunctionalInterface
public interface LogSink {

    void log(String level, String line);

    /** A structured line ({@code log.info(message, fields)}); by default the fields are appended as JSON. */
    default void log(String level, String message, JsonObject fields) {
        log(level, fields == null || fields.isEmpty() ? message : message + " " + fields.encode());
    }
}
