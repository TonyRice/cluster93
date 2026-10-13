package cluster93.runtime.js;

import cluster93.core.runtime.HostException;
import cluster93.core.runtime.OutgoingHttp2Session;
import cluster93.core.runtime.OutgoingHttp2Stream;
import cluster93.core.runtime.api.SocketApi;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/** {@code http2} sessions and their streams (D-117); js/node/http2.js builds Node's classes around them. */
public final class Http2Binding {

    private final HostBridge bridge;
    private final SocketApi sockets;

    Http2Binding(HostBridge bridge, SocketApi sockets) {
        this.bridge = bridge;
        this.sockets = sockets;
    }

    /** Resolves once connected with the session's {@code id}, {@code info}, {@code settings} and its {@code eventsId} stream. */
    @HostFunction(name = "connect")
    public JsHandle connect(JsArg request) throws JsEngineException {
        return bridge.promise(() -> sockets.http2(request.options()), session -> new JsonObject()
                .put("id", bridge.addSession(session)).put("info", session.info()).put("settings", session.remoteSettings())
                .put("eventsId", bridge.openStream(session.events())));
    }

    /** Opens a stream; resolves with its {@code id} here and its {@code streamId} on the connection, once its headers went. */
    @HostFunction(name = "request")
    public JsHandle request(int sessionId, JsArg headers, boolean endStream) throws JsEngineException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        JsonObject head = headers.options();
        return bridge.promise(() -> session == null ? closed() : session.request(head, endStream),
                stream -> new JsonObject().put("id", bridge.addHttp2Stream(stream)).put("streamId", stream.id()));
    }

    @HostFunction(name = "write")
    public JsHandle write(int id, JsArg data) throws JsEngineException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        byte[] bytes = data.bytes();
        return bridge.promise(() -> stream == null ? closed() : stream.write(Buffer.buffer(bytes)).map(v -> Js.UNDEFINED));
    }

    @HostFunction(name = "end")
    public JsHandle end(int id) throws JsEngineException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return bridge.promise(() -> stream == null ? closed() : stream.end().map(v -> Js.UNDEFINED));
    }

    /** Ends the stream with trailers, as Node's sendTrailers. */
    @HostFunction(name = "sendTrailers")
    public JsHandle sendTrailers(int id, JsArg trailers) throws JsEngineException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        JsonObject headers = trailers.options();
        return bridge.promise(() -> stream == null ? closed() : stream.sendTrailers(headers).map(v -> Js.UNDEFINED));
    }

    /** Node's {@code session.state}; {} once the session is closed. */
    @HostFunction(name = "sessionState")
    public Object sessionState(int sessionId) {
        OutgoingHttp2Session session = bridge.session(sessionId);
        return session == null ? new JsonObject() : session.state();
    }

    /** Resolves with the response's {@code rawHeaders} and {@code status}, and the {@code bodyId} its body arrives on. */
    @HostFunction(name = "response")
    public JsHandle response(int id) throws JsEngineException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return bridge.promise(() -> stream == null ? closed() : stream.response(),
                head -> head.copy().put("bodyId", bridge.openStream(stream.body())));
    }

    /** Resolves with {@code rawTrailers} once the body has ended. Our side may still be open: release() lets it go. */
    @HostFunction(name = "trailers")
    public JsHandle trailers(int id) throws JsEngineException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return bridge.promise(() -> stream == null ? closed() : stream.trailers());
    }

    /** The network read the stream's next body chunk came in (D-121); -1 if unknown. */
    @HostFunction(name = "chunkRead")
    public double chunkRead(int id) {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return stream == null ? -1 : stream.nextChunkRead();
    }

    /** The script's stream has closed. */
    @HostFunction(name = "release")
    public void release(int id) {
        bridge.removeHttp2Stream(id);
    }

    @HostFunction(name = "reset")
    public void reset(int id, double code) {
        OutgoingHttp2Stream stream = bridge.removeHttp2Stream(id);
        if (stream != null) {
            stream.reset((long) code);
        }
    }

    /** Resolves with {@code duration} (ms) and the acknowledged {@code payload} (base64). */
    @HostFunction(name = "ping")
    public JsHandle ping(int sessionId, JsArg payload) throws JsEngineException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        byte[] bytes = payload.bytes();
        return bridge.promise(() -> session == null ? closed() : session.ping(Buffer.buffer(bytes)));
    }

    @HostFunction(name = "settings")
    public JsHandle settings(int sessionId, JsArg settings) throws JsEngineException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        JsonObject update = settings.options();
        return bridge.promise(() -> session == null ? closed() : session.settings(update).map(v -> Js.UNDEFINED));
    }

    @HostFunction(name = "goaway")
    public void goaway(int sessionId, double code, int lastStreamId, JsArg data) throws JsEngineException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        byte[] bytes = data == null || data.isNullish() ? new byte[0] : data.bytes();
        if (session != null) {
            session.goaway((long) code, lastStreamId, Buffer.buffer(bytes));
        }
    }

    /** Closes once open streams are done. */
    @HostFunction(name = "close")
    public JsHandle close(int sessionId) throws JsEngineException {
        OutgoingHttp2Session session = bridge.removeSession(sessionId);
        return bridge.promise(() -> session == null ? Future.succeededFuture() : session.close().map(v -> Js.UNDEFINED));
    }

    @HostFunction(name = "destroy")
    public void destroy(int sessionId) {
        OutgoingHttp2Session session = bridge.removeSession(sessionId);
        if (session != null) {
            session.destroy();
        }
    }

    private static <T> Future<T> closed() {
        return Future.failedFuture(new HostException("Error", "ERR_HTTP2_INVALID_SESSION", "The session has been destroyed"));
    }
}
