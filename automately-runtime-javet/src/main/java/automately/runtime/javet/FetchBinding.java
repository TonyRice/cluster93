package automately.runtime.javet;

import automately.core.runtime.api.FetchApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;

/** {@code fetch} in JavaScript; {@code fetch.js} builds the request and the Response around it. */
public final class FetchBinding {

    private final HostBridge bridge;
    private final FetchApi fetch;

    FetchBinding(HostBridge bridge, FetchApi fetch) {
        this.bridge = bridge;
        this.fetch = fetch;
    }

    /**
     * {@code request}: {@code {url, method, headers, timeoutMs}}; {@code body}: bytes, a string, or null/undefined.
     * Resolves with the status and headers, and the body's stream id.
     */
    @V8Function(name = "fetch")
    public V8ValuePromise fetch(V8Value request, V8Value body) throws JavetException {
        byte[] bytes = body == null || body.isNullOrUndefined() ? null : HostBridge.bytes(body);
        return bridge.promise(() -> fetch.fetch(bridge.values().options(request), bytes),
                response -> response.meta().copy().put("streamId", bridge.openStream(response.body())));
    }

    // --- requests whose body streams (node:http, D-109) ---

    /** {@code request}: {@code {url, method, headers, chunked, timeoutMs, tls}}. Resolves with the request's id once connected. */
    @V8Function(name = "open")
    public V8ValuePromise open(V8Value request) throws JavetException {
        return bridge.promise(() -> fetch.open(bridge.values().options(request)), bridge::addOutgoing);
    }

    /** Writes a body chunk; settles once the connection has taken it. */
    @V8Function(name = "write")
    public V8ValuePromise write(int id, V8Value data) throws JavetException {
        automately.core.runtime.OutgoingRequest request = bridge.outgoing(id);
        byte[] bytes = HostBridge.bytes(data);
        return bridge.promise(() -> request == null ? closed()
                : request.write(io.vertx.core.buffer.Buffer.buffer(bytes)).map(v -> JsValues.UNDEFINED));
    }

    /** Ends the body. */
    @V8Function(name = "end")
    public V8ValuePromise end(int id) throws JavetException {
        automately.core.runtime.OutgoingRequest request = bridge.outgoing(id);
        // Once the body has ended and the response has settled, the request is done with, and leaves the registry.
        return bridge.promise(() -> request == null ? closed()
                : request.end().onComplete(ended -> request.response().onComplete(answered -> bridge.removeOutgoing(id))).map(v -> JsValues.UNDEFINED));
    }

    /** Resolves with the response's status and headers, and its body's stream id. */
    @V8Function(name = "response")
    public V8ValuePromise response(int id) throws JavetException {
        automately.core.runtime.OutgoingRequest request = bridge.outgoing(id);
        return bridge.promise(() -> request == null ? closed() : request.response(),
                response -> response.meta().copy().put("streamId", bridge.openStream(response.body())));
    }

    @V8Function(name = "abort")
    public void abort(int id) {
        automately.core.runtime.OutgoingRequest request = bridge.removeOutgoing(id);
        if (request != null) {
            request.abort();
        }
    }

    private static <T> io.vertx.core.Future<T> closed() {
        return io.vertx.core.Future.failedFuture(new automately.core.runtime.HostException("Error", "closed", "The request is already finished"));
    }
}
