package automately.core.http;

import io.vertx.core.http.HttpServerRequest;

/**
 * For answering a request before its body was read (a refused upload, a 413). The rest of the body is read and
 * discarded, so the client finishes sending and sees the answer instead of a reset connection (D-039).
 *
 * <p>A body small enough ({@link #MAX_DRAIN_BYTES}) is drained and the connection kept. A bigger one, or one of unknown
 * size that grows past it, gets {@code Connection: close} and a <em>lingering close</em>: the server keeps discarding
 * until the body ends or {@link #LINGER} passes, then closes. Closing at once, with unread bytes still arriving, makes
 * the kernel reset the connection, and the client can lose the answer it was sent.
 */
public final class EarlyAnswer {

    public static final long MAX_DRAIN_BYTES = 64L * 1024 * 1024;
    public static final java.time.Duration LINGER = java.time.Duration.ofSeconds(5);

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
        Linger linger = new Linger(request);
        if (tooBig) {
            linger.start();
        }
        long[] drained = {0};
        try {
            request.endHandler(v -> linger.bodyEnded());
            request.handler(chunk -> {
                drained[0] += chunk.length();
                if (drained[0] > MAX_DRAIN_BYTES) {
                    linger.start();
                }
            });
            request.exceptionHandler(err -> { });
        } catch (IllegalStateException alreadyRead) {
            // The body ended since isEnded() was asked: nothing left to drain. Throwing here would leave the answer
            // unsent and the client waiting (D-099).
            linger.bodyEnded();
            return tooBig;
        }
        request.resume();
        return tooBig;
    }

    /** Closes the connection once the body has ended, or after {@link #LINGER}, whichever comes first. */
    private static final class Linger {

        private final HttpServerRequest request;
        private boolean started;
        private boolean closed;
        private long timer = -1;

        Linger(HttpServerRequest request) {
            this.request = request;
        }

        void start() {
            if (started) {
                return;
            }
            started = true;
            io.vertx.core.Context context = io.vertx.core.Vertx.currentContext();
            if (context == null) {
                close();
                return;
            }
            timer = context.owner().setTimer(LINGER.toMillis(), id -> close());
        }

        void bodyEnded() {
            if (started) {
                close();
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            io.vertx.core.Context context = io.vertx.core.Vertx.currentContext();
            if (timer >= 0 && context != null) {
                context.owner().cancelTimer(timer);
            }
            // Only once the answer is out; closing earlier would cut it off.
            if (request.response().ended()) {
                request.connection().close();
            } else {
                request.response().endHandler(v -> request.connection().close());
            }
        }
    }
}
