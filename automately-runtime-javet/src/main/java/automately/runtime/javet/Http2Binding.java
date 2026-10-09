package automately.runtime.javet;

import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingHttp2Session;
import automately.core.runtime.OutgoingHttp2Stream;
import automately.core.runtime.api.SocketApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;
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
    @V8Function(name = "connect")
    public V8ValuePromise connect(V8Value request) throws JavetException {
        return bridge.promise(() -> sockets.http2(bridge.values().options(request)), session -> new JsonObject()
                .put("id", bridge.addSession(session)).put("info", session.info()).put("settings", session.remoteSettings())
                .put("eventsId", bridge.openStream(session.events())));
    }

    /** Opens a stream; resolves with its {@code id} here and its {@code streamId} on the connection, once its headers went. */
    @V8Function(name = "request")
    public V8ValuePromise request(int sessionId, V8Value headers, boolean endStream) throws JavetException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        JsonObject head = bridge.values().options(headers);
        return bridge.promise(() -> session == null ? closed() : session.request(head, endStream),
                stream -> new JsonObject().put("id", bridge.addHttp2Stream(stream)).put("streamId", stream.id()));
    }

    @V8Function(name = "write")
    public V8ValuePromise write(int id, V8Value data) throws JavetException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        byte[] bytes = HostBridge.bytes(data);
        return bridge.promise(() -> stream == null ? closed() : stream.write(Buffer.buffer(bytes)).map(v -> JsValues.UNDEFINED));
    }

    @V8Function(name = "end")
    public V8ValuePromise end(int id) throws JavetException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return bridge.promise(() -> stream == null ? closed() : stream.end().map(v -> JsValues.UNDEFINED));
    }

    /** Ends the stream with trailers, as Node's sendTrailers. */
    @V8Function(name = "sendTrailers")
    public V8ValuePromise sendTrailers(int id, V8Value trailers) throws JavetException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        JsonObject headers = bridge.values().options(trailers);
        return bridge.promise(() -> stream == null ? closed() : stream.sendTrailers(headers).map(v -> JsValues.UNDEFINED));
    }

    /** Node's {@code stream.state}; {} once the stream is done with. */
    @V8Function(name = "streamState")
    public V8Value streamState(int id) throws JavetException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return bridge.values().toJs(stream == null ? new JsonObject() : stream.state());
    }

    /** Node's {@code session.state}; {} once the session is closed. */
    @V8Function(name = "sessionState")
    public V8Value sessionState(int sessionId) throws JavetException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        return bridge.values().toJs(session == null ? new JsonObject() : session.state());
    }

    /** Resolves with the response's {@code rawHeaders} and {@code status}, and the {@code bodyId} its body arrives on. */
    @V8Function(name = "response")
    public V8ValuePromise response(int id) throws JavetException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return bridge.promise(() -> stream == null ? closed() : stream.response(),
                head -> head.copy().put("bodyId", bridge.openStream(stream.body())));
    }

    /** Resolves with {@code rawTrailers} once the body has ended; the stream is done with then. */
    @V8Function(name = "trailers")
    public V8ValuePromise trailers(int id) throws JavetException {
        OutgoingHttp2Stream stream = bridge.http2Stream(id);
        return bridge.promise(() -> stream == null ? closed() : stream.trailers().onComplete(done -> bridge.removeHttp2Stream(id)));
    }

    @V8Function(name = "reset")
    public void reset(int id, double code) {
        OutgoingHttp2Stream stream = bridge.removeHttp2Stream(id);
        if (stream != null) {
            stream.reset((long) code);
        }
    }

    /** Resolves with {@code duration} (ms) and the acknowledged {@code payload} (base64). */
    @V8Function(name = "ping")
    public V8ValuePromise ping(int sessionId, V8Value payload) throws JavetException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        byte[] bytes = HostBridge.bytes(payload);
        return bridge.promise(() -> session == null ? closed() : session.ping(Buffer.buffer(bytes)));
    }

    @V8Function(name = "settings")
    public V8ValuePromise settings(int sessionId, V8Value settings) throws JavetException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        JsonObject update = bridge.values().options(settings);
        return bridge.promise(() -> session == null ? closed() : session.settings(update).map(v -> JsValues.UNDEFINED));
    }

    @V8Function(name = "goaway")
    public void goaway(int sessionId, double code, int lastStreamId, V8Value data) throws JavetException {
        OutgoingHttp2Session session = bridge.session(sessionId);
        byte[] bytes = data == null || data.isNullOrUndefined() ? new byte[0] : HostBridge.bytes(data);
        if (session != null) {
            session.goaway((long) code, lastStreamId, Buffer.buffer(bytes));
        }
    }

    /** Closes once open streams are done. */
    @V8Function(name = "close")
    public V8ValuePromise close(int sessionId) throws JavetException {
        OutgoingHttp2Session session = bridge.removeSession(sessionId);
        return bridge.promise(() -> session == null ? Future.succeededFuture() : session.close().map(v -> JsValues.UNDEFINED));
    }

    @V8Function(name = "destroy")
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
