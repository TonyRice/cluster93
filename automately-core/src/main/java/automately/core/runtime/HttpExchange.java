package automately.core.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * One HTTP request to a service's {@code ctx.serve} handler, streamed in both directions
 * (docs/design/durability-and-streaming.md). Methods may be called from any thread.
 */
public interface HttpExchange {

    /** {@code method, url, path, host, query, headers}; the body comes from {@link #body()}. */
    JsonObject request();

    BodyStream body();

    /** Sends the status and headers. Called once, before any {@link #write}. */
    void respond(int status, JsonObject headers);

    /** Writes a chunk; completes when the client has taken it (backpressure), or fails if the client left. */
    Future<Void> write(Buffer chunk);

    void end();

    /** Before {@link #respond}: an error response with this status. After: the connection is reset. */
    void fail(int status, String message);
}
