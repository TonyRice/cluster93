package automately.core.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * One HTTP request to a service's {@code ctx.serve} handler, streamed in both directions
 * (docs/design/durability-and-streaming.md). Methods may be called from any thread.
 */
public interface HttpExchange {

    /**
     * {@code method, url, path, host, query, headers}; the body comes from {@link #body()}. Behind the ingress also, as
     * Node's servers need them (D-123): {@code rawHeaders} (the client's own names and values in order, then the
     * proxy's {@code X-Forwarded-*}), {@code httpVersion}, {@code remoteAddress}, {@code remotePort}, {@code encrypted}
     * and {@code port}, the server's declared port (null for the service's main server).
     */
    JsonObject request();

    BodyStream body();

    /** Sends the status and headers. Called once, before any {@link #write}. */
    void respond(int status, JsonObject headers);

    /** Writes a chunk; completes when the client has taken it (backpressure), or fails if the client left. */
    Future<Void> write(Buffer chunk);

    void end();

    /**
     * Sends the status, its reason phrase (null for the standard one) and the headers, in order, as name/value pairs in
     * one array; a name may repeat ({@code Set-Cookie}). A {@code Content-Length} is kept; without one the body is
     * chunked (D-123).
     */
    default void respond(int status, String statusMessage, io.vertx.core.json.JsonArray rawHeaders) {
        JsonObject headers = new JsonObject();
        for (int i = 0; i + 1 < rawHeaders.size(); i += 2) {
            String name = rawHeaders.getString(i).toLowerCase(java.util.Locale.ROOT);
            headers.put(name, headers.containsKey(name) ? headers.getString(name) + ", " + rawHeaders.getString(i + 1) : rawHeaders.getString(i + 1));
        }
        respond(status, headers);
    }

    /** Trailers to send after the body, as name/value pairs; before {@link #end}, on a chunked response. */
    default void trailers(io.vertx.core.json.JsonArray rawTrailers) {
    }

    /** Completes with true if the client disconnected before the response ended, or false once it ended. */
    default Future<Boolean> closed() {
        return Future.succeededFuture(false);
    }

    /** Before {@link #respond}: an error response with this status. After: the connection is reset. */
    void fail(int status, String message);
}
