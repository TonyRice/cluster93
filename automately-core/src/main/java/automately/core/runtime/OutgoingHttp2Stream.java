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
     * The response's headers, {@code :status} included, and {@code flags}: the HEADERS frame's own, as Node reports them;
     * fails with
     * {@link HostException} coded {@code ERR_HTTP2_STREAM_ERROR} (and an {@code rstCode} in its message) if the stream is
     * reset.
     */
    Future<JsonObject> response();

    /** The response's body, paused. */
    BodyStream body();

    /** The trailers ({@code rawTrailers}) and their frame's {@code flags}, once the body has ended. */
    Future<JsonObject> trailers();

    /** Ends the request with these trailers (a HEADERS frame), as Node's {@code sendTrailers} (D-118). */
    Future<Void> sendTrailers(JsonObject trailers);

    /** Node's {@code stream.state}, or {} once the stream is gone (D-118). */
    JsonObject state();

    /** Resets the stream (RST_STREAM) with this code. Safe to call more than once. */
    void reset(long code);

    /**
     * The network read the body's next chunk came in, counted per connection, in the order chunks are delivered; -1 if
     * unknown. The isolate hands a new read to the script on a later turn, as Node handles each read (D-121).
     */
    default long nextChunkRead() {
        return -1;
    }
}
