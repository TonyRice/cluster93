package automately.core.scheduler;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A running attempt's last {@link NodeVerticle#MAX_LOG_LINES} lines, each numbered. The numbers let a client that
 * connects mid-run take the recent lines, then the live ones, without repeating any (phase 4, milestone 3).
 */
final class LogBook {

    private final Deque<String> lines = new ArrayDeque<>();
    private long lastSeq;

    /** Adds a line; returns its number (1, 2, ...). */
    synchronized long add(String line) {
        if (lines.size() >= NodeVerticle.MAX_LOG_LINES) {
            lines.removeFirst();
        }
        lines.addLast(line);
        return ++lastSeq;
    }

    synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    /** {@code {"lastSeq": n, "lines": [...]}}: the lines kept, the last of which is number n. */
    synchronized JsonObject tail() {
        return new JsonObject().put("lastSeq", lastSeq).put("lines", new JsonArray(List.copyOf(lines)));
    }
}
