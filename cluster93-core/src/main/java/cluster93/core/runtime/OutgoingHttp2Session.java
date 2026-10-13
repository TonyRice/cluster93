package cluster93.core.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * An HTTP/2 connection a script opened with {@code http2.connect} (D-117), through the egress gate and verified. Each
 * request on it is an {@link OutgoingHttp2Stream}.
 */
public interface OutgoingHttp2Session {

    /** Addresses and TLS details, as {@link cluster93.core.host.ConnectionInfo} describes them. */
    JsonObject info();

    /** The peer's SETTINGS, in Node's names ({@code maxConcurrentStreams}, …). */
    JsonObject remoteSettings();

    /**
     * What happens to the connection, one JSON event per chunk: {@code {"type": "remoteSettings", "settings": …}},
     * {@code {"type": "goaway", "errorCode", "lastStreamID", "opaqueData"}} (base64), {@code {"type": "error", "message"}}.
     * It ends when the connection closes.
     */
    BodyStream events();

    /**
     * A stream: {@code headers} carries the pseudo-headers ({@code :method}, {@code :path}, {@code :scheme},
     * {@code :authority}) and the rest. With {@code endStream} the request has no body.
     */
    Future<OutgoingHttp2Stream> request(JsonObject headers, boolean endStream);

    /** A PING with these 8 bytes; completes with {@code {duration (ms), payload (base64)}} when it's acknowledged. */
    Future<JsonObject> ping(Buffer payload);

    /** Sends SETTINGS, in Node's names; completes when they're acknowledged. */
    Future<Void> settings(JsonObject settings);

    void goaway(long errorCode, int lastStreamId, Buffer opaqueData);

    /** Node's {@code session.state}: windows, stream ids, queue and HPACK table sizes (D-118). */
    JsonObject state();

    /** Closes once open streams are done. */
    Future<Void> close();

    /** Closes at once. Safe to call more than once. */
    void destroy();
}
