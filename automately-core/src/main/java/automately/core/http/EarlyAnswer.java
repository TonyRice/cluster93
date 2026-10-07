package automately.core.http;

import io.vertx.core.http.HttpServerRequest;

/**
 * For answering a request before its body was read (a refused upload, a 413). The body is read and discarded
 * ("lingering close"), so the client finishes sending and actually sees the answer instead of a reset connection;
 * past {@link #MAX_DRAIN_BYTES} the connection is closed rather than spending bandwidth on it (D-039).
 */
public final class EarlyAnswer {

    public static final long MAX_DRAIN_BYTES = 64L * 1024 * 1024;

    private EarlyAnswer() {
    }

    /** Headers describing a body that an error answer replaces; left in place, a stale Content-Length breaks the connection. */
    public static void dropEntityHeaders(io.vertx.core.http.HttpServerResponse response) {
        if (response.headWritten()) {
            return;
        }
        for (String name : new String[] {"Content-Length", "Content-Range", "Content-Type", "ETag", "Accept-Ranges", "Content-Encoding",
                "Transfer-Encoding"}) {
            response.headers().remove(name);
        }
        response.setChunked(false);
    }

    /** Call before writing the answer. Returns true if the connection will be closed (send {@code Connection: close}). */
    public static boolean prepare(HttpServerRequest request) {
        if (request.isEnded()) {
            return false;
        }
        String length = request.getHeader("Content-Length");
        boolean tooBig;
        try {
            tooBig = length != null && Long.parseLong(length.trim()) > MAX_DRAIN_BYTES;
        } catch (NumberFormatException e) {
            tooBig = true;
        }
        if (tooBig) {
            return true;
        }
        long[] drained = {0};
        request.handler(chunk -> {
            drained[0] += chunk.length();
            if (drained[0] > MAX_DRAIN_BYTES) {
                request.handler(null);
                request.connection().close();
            }
        });
        request.exceptionHandler(err -> { });
        request.resume();
        return false;
    }
}
