package automately.core.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/**
 * An HTTP request whose body a script writes in chunks ({@code node:http}, D-109). It's already through the egress gate
 * and connected; its headers go with the first chunk or the end.
 */
public interface OutgoingRequest {

    /** Sends a chunk; completes once it's handed to the connection, which is the backpressure. */
    Future<Void> write(Buffer chunk);

    /** Ends the body. */
    Future<Void> end();

    /** The response's status and headers, with its body as a paused stream. It may arrive before the body ends. */
    Future<FetchedResponse> response();

    /** Abandons the request and its response. Safe to call more than once, and after it ended. */
    void abort();
}
