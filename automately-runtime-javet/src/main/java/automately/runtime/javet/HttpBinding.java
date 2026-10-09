package automately.runtime.javet;

import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/** {@code ctx.serve} in JavaScript: {@code serve.js} turns the handler's return value into a streamed response. */
public final class HttpBinding {

    private final HostBridge bridge;

    HttpBinding(HostBridge bridge) {
        this.bridge = bridge;
    }

    @V8Function(name = "serve")
    public V8ValuePromise serve(int listenerId) throws JavetException {
        return bridge.serve(listenerId);
    }

    /** A Node server on {@code port} (D-123). */
    @V8Function(name = "listen")
    public V8ValuePromise listen(int listenerId, int port) throws JavetException {
        return bridge.listen(listenerId, port);
    }

    /** Status, reason phrase (null for the standard one) and headers as name/value pairs, repeats kept (D-123). */
    @V8Function(name = "respondRaw")
    public void respondRaw(int exchangeId, int status, V8Value message, V8Value rawHeaders) throws JavetException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        if (exchange != null) {
            Object text = bridge.values().toJava(message);
            exchange.respond(status, text == null ? null : String.valueOf(text), bridge.values().list(rawHeaders));
        }
    }

    /** Trailers for the end of a chunked response (D-123). */
    @V8Function(name = "trailers")
    public void trailers(int exchangeId, V8Value rawTrailers) throws JavetException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        if (exchange != null) {
            exchange.trailers(bridge.values().list(rawTrailers));
        }
    }

    /** Resolves with true if the client leaves before the response ends, false once it ends (D-123). */
    @V8Function(name = "closed")
    public V8ValuePromise closed(int exchangeId) throws JavetException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        return bridge.promise(() -> exchange == null ? Future.succeededFuture(false) : exchange.closed());
    }

    /**
     * Takes a request that asks to upgrade as a socket, for a Node server's {@code 'upgrade'} listener (D-125): JSON
     * {@code {id, info, streamId}}, the socket then driven as a {@code net} socket's; or null if it can't be upgraded.
     * The exchange is the socket's from then on, closed with the run like any socket.
     */
    @V8Function(name = "upgrade")
    public String upgrade(int exchangeId) {
        HttpExchange exchange = bridge.exchange(exchangeId);
        automately.core.runtime.OutgoingSocket socket = exchange == null ? null : exchange.upgrade();
        if (socket == null) {
            return null;
        }
        bridge.finish(exchangeId);
        int id = bridge.addSocket(socket);
        return new io.vertx.core.json.JsonObject().put("id", id).put("info", socket.info())
                .put("streamId", bridge.openStream(socket.data())).encode();
    }

    @V8Function(name = "respond")
    public void respond(int exchangeId, int status, V8Value headers) {
        HttpExchange exchange = bridge.exchange(exchangeId);
        if (exchange != null) {
            exchange.respond(status, bridge.values().options(headers));
        }
    }

    /** Writes a response chunk; the promise settles when the client has taken it. */
    @V8Function(name = "write")
    public V8ValuePromise write(int exchangeId, V8Value data) throws JavetException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        byte[] bytes = HostBridge.bytes(data);
        return bridge.promise(() -> exchange == null
                ? Future.failedFuture(new HostException("Error", "closed", "The response is already finished"))
                : exchange.write(Buffer.buffer(bytes)).map(v -> JsValues.UNDEFINED)
                        .recover(err -> Future.failedFuture(new HostException("Error", "client_disconnected", err.getMessage()))));
    }

    @V8Function(name = "end")
    public void end(int exchangeId) {
        HttpExchange exchange = bridge.finish(exchangeId);
        if (exchange != null) {
            exchange.end();
        }
    }

    @V8Function(name = "fail")
    public void fail(int exchangeId, int status, String message) {
        HttpExchange exchange = bridge.finish(exchangeId);
        if (exchange != null) {
            exchange.fail(status, message);
        }
    }
}
