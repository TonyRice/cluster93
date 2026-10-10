package automately.runtime.js;

import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/** {@code ctx.serve} in JavaScript: {@code serve.js} turns the handler's return value into a streamed response. */
public final class HttpBinding {

    private final HostBridge bridge;

    HttpBinding(HostBridge bridge) {
        this.bridge = bridge;
    }

    @HostFunction(name = "serve")
    public JsHandle serve(int listenerId) throws JsEngineException {
        return bridge.serve(listenerId);
    }

    /** A Node server on {@code port} (D-123). */
    @HostFunction(name = "listen")
    public JsHandle listen(int listenerId, int port) throws JsEngineException {
        return bridge.listen(listenerId, port);
    }

    /** Status, reason phrase (null for the standard one) and headers as name/value pairs, repeats kept (D-123). */
    @HostFunction(name = "respondRaw")
    public void respondRaw(int exchangeId, int status, JsArg message, JsArg rawHeaders) throws JsEngineException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        if (exchange != null) {
            Object text = message.json();
            exchange.respond(status, text == null ? null : String.valueOf(text), rawHeaders.list());
        }
    }

    /** Trailers for the end of a chunked response (D-123). */
    @HostFunction(name = "trailers")
    public void trailers(int exchangeId, JsArg rawTrailers) throws JsEngineException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        if (exchange != null) {
            exchange.trailers(rawTrailers.list());
        }
    }

    /** Resolves with true if the client leaves before the response ends, false once it ends (D-123). */
    @HostFunction(name = "closed")
    public JsHandle closed(int exchangeId) throws JsEngineException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        return bridge.promise(() -> exchange == null ? Future.succeededFuture(false) : exchange.closed());
    }

    /**
     * Takes a request that asks to upgrade as a socket, for a Node server's {@code 'upgrade'} listener (D-125): JSON
     * {@code {id, info, streamId}}, the socket then driven as a {@code net} socket's; or null if it can't be upgraded.
     * The exchange is the socket's from then on, closed with the run like any socket.
     */
    @HostFunction(name = "upgrade")
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

    @HostFunction(name = "respond")
    public void respond(int exchangeId, int status, JsArg headers) {
        HttpExchange exchange = bridge.exchange(exchangeId);
        if (exchange != null) {
            exchange.respond(status, headers.options());
        }
    }

    /** Writes a response chunk; the promise settles when the client has taken it. */
    @HostFunction(name = "write")
    public JsHandle write(int exchangeId, JsArg data) throws JsEngineException {
        HttpExchange exchange = bridge.exchange(exchangeId);
        byte[] bytes = data.bytes();
        return bridge.promise(() -> exchange == null
                ? Future.failedFuture(new HostException("Error", "closed", "The response is already finished"))
                : exchange.write(Buffer.buffer(bytes)).map(v -> Js.UNDEFINED)
                        .recover(err -> Future.failedFuture(new HostException("Error", "client_disconnected", err.getMessage()))));
    }

    @HostFunction(name = "end")
    public void end(int exchangeId) {
        HttpExchange exchange = bridge.finish(exchangeId);
        if (exchange != null) {
            exchange.end();
        }
    }

    @HostFunction(name = "fail")
    public void fail(int exchangeId, int status, String message) {
        HttpExchange exchange = bridge.finish(exchangeId);
        if (exchange != null) {
            exchange.fail(status, message);
        }
    }
}
