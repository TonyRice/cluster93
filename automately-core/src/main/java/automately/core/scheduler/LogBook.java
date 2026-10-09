package automately.core.scheduler;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A running attempt's last {@link NodeVerticle#MAX_LOG_LINES} lines, each numbered. The numbers let a client that
 * connects mid-run take the recent lines, then the live ones, without repeating any (phase 4, milestone 3).
 *
 * <p>The book lives on the node's Java heap, outside the isolate's limit, so it's bounded in characters too (D-107):
 * a line is cut at {@link #MAX_LINE_CHARS}, and the oldest lines go once the book holds more than
 * {@link #MAX_CHARS}.
 */
final class LogBook {

    /** Longest line kept, in characters; the rest is replaced by a marker that says how much was cut. */
    static final int MAX_LINE_CHARS = 16 * 1024;
    /** Room for a marker added before the line reached the host (the prelude clips too), so it isn't cut twice. */
    static final int MARKER_ROOM = 64;
    /** Most characters kept for one attempt, which is also what its job record stores. */
    static final int MAX_CHARS = 1024 * 1024;

    private final Deque<String> lines = new ArrayDeque<>();
    private long chars;
    private long lastSeq;

    /** A line no longer than {@link #MAX_LINE_CHARS} plus its marker. */
    static String clip(String line) {
        if (line.length() <= MAX_LINE_CHARS + MARKER_ROOM) {
            return line;
        }
        int end = Character.isHighSurrogate(line.charAt(MAX_LINE_CHARS - 1)) ? MAX_LINE_CHARS - 1 : MAX_LINE_CHARS;
        return line.substring(0, end) + " … [" + (line.length() - end) + " more characters]";
    }

    /** Adds a line, already {@link #clip clipped}; returns its number (1, 2, ...). */
    synchronized long add(String line) {
        lines.addLast(line);
        chars += line.length();
        while (lines.size() > NodeVerticle.MAX_LOG_LINES || (chars > MAX_CHARS && lines.size() > 1)) {
            chars -= lines.removeFirst().length();
        }
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
