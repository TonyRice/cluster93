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
