package automately.core.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/** One request on an {@link OutgoingHttp2Session} (D-117). */
public interface OutgoingHttp2Stream {

    /** The stream's identifier on the connection, once its headers are sent. */
    int id();

    Future<Void> write(Buffer chunk);

    Future<Void> end();

    /**
     * The response's headers, {@code :status} included, with {@code endStream} if it has no body; fails with
     * {@link HostException} coded {@code ERR_HTTP2_STREAM_ERROR} (and an {@code rstCode} in its message) if the stream is
     * reset.
     */
    Future<JsonObject> response();

    /** The response's body, paused. */
    BodyStream body();

    /** The trailers, once the body has ended ({} if there were none). */
    Future<JsonObject> trailers();

    /** Resets the stream (RST_STREAM) with this code. Safe to call more than once. */
    void reset(long code);
}
