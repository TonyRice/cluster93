package cluster93.runtime.js;

import cluster93.core.runtime.api.FetchApi;

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
    @HostFunction(name = "fetch")
    public JsHandle fetch(JsArg request, JsArg body) throws JsEngineException {
        byte[] bytes = body == null || body.isNullish() ? null : body.bytes();
        return bridge.promise(() -> fetch.fetch(request.options(), bytes),
                response -> response.meta().copy().put("streamId", bridge.openStream(response.body())));
    }

    // --- requests whose body streams (node:http, D-109) ---

    /**
     * {@code request}: {@code {url, method, headers, chunked, timeoutMs, tls}}. Resolves once connected with the request's
     * {@code id} and its {@code connection} (D-114), which the socket stand-in reports.
     */
    @HostFunction(name = "open")
    public JsHandle open(JsArg request) throws JsEngineException {
        return bridge.promise(() -> fetch.open(request.options()),
                opened -> new io.vertx.core.json.JsonObject().put("id", bridge.addOutgoing(opened)).put("connection", opened.connection()));
    }

    /** Writes a body chunk; settles once the connection has taken it. */
    @HostFunction(name = "write")
    public JsHandle write(int id, JsArg data) throws JsEngineException {
        cluster93.core.runtime.OutgoingRequest request = bridge.outgoing(id);
        byte[] bytes = data.bytes();
        return bridge.promise(() -> request == null ? closed()
                : request.write(io.vertx.core.buffer.Buffer.buffer(bytes)).map(v -> Js.UNDEFINED));
    }

    /** Ends the body. */
    @HostFunction(name = "end")
    public JsHandle end(int id) throws JsEngineException {
        cluster93.core.runtime.OutgoingRequest request = bridge.outgoing(id);
        // Once the body has ended and the response has settled, the request is done with, and leaves the registry.
        return bridge.promise(() -> request == null ? closed()
                : request.end().onComplete(ended -> request.response().onComplete(answered -> bridge.removeOutgoing(id))).map(v -> Js.UNDEFINED));
    }

    /** Resolves with the response's status and headers, and its body's stream id. */
    @HostFunction(name = "response")
    public JsHandle response(int id) throws JsEngineException {
        cluster93.core.runtime.OutgoingRequest request = bridge.outgoing(id);
        return bridge.promise(() -> request == null ? closed() : request.response(),
                response -> response.meta().copy().put("streamId", bridge.openStream(response.body())));
    }

    @HostFunction(name = "abort")
    public void abort(int id) {
        cluster93.core.runtime.OutgoingRequest request = bridge.removeOutgoing(id);
        if (request != null) {
            request.abort();
        }
    }

    private static <T> io.vertx.core.Future<T> closed() {
        return io.vertx.core.Future.failedFuture(new cluster93.core.runtime.HostException("Error", "closed", "The request is already finished"));
    }
}
