package cluster93.runtime.graaljs;

import cluster93.runtime.js.EntryPoints;
import cluster93.runtime.js.JsEngineException;
import org.graalvm.polyglot.Value;


/** The prelude's functions the event loop calls into, as GraalJS values. Only used on the run's thread (D-126). */
final class GraalJsEntryPoints implements EntryPoints {

    final Value invoke;
    final Value rejected;
    private final Value fire;
    private final Value startImmediates;
    private final Value runImmediate;
    private final Value requestStop;
    private final Value deliver;
    private final Value request;
    private final Value chunk;
    private final Value streamError;
    private final Value checkRejections;
    private final GraalJsValues values;
    private final GraalJsContext context;

    GraalJsEntryPoints(GraalJsContext context, Value prelude, GraalJsValues values) {
        this.context = context;
        this.values = values;
        this.invoke = prelude.getMember("invoke");
        this.rejected = prelude.getMember("rejected");
        this.fire = prelude.getMember("fire");
        this.startImmediates = prelude.getMember("startImmediates");
        this.runImmediate = prelude.getMember("runImmediate");
        this.requestStop = prelude.getMember("requestStop");
        this.deliver = prelude.getMember("deliver");
        this.request = prelude.getMember("request");
        this.chunk = prelude.getMember("chunk");
        this.streamError = prelude.getMember("streamError");
        this.checkRejections = prelude.getMember("checkRejections");
    }

    @Override
    public void checkRejections() throws JsEngineException {
        context.guard(() -> checkRejections.executeVoid());
    }

    @Override
    public void fireTimer(int id) throws JsEngineException {
        context.guard(() -> fire.executeVoid(id));
    }

    @Override
    public int startImmediates() throws JsEngineException {
        return context.guardGet(() -> startImmediates.execute().asInt());
    }

    @Override
    public void runImmediate() throws JsEngineException {
        context.guard(() -> runImmediate.executeVoid());
    }

    @Override
    public void requestStop(String reason) throws JsEngineException {
        context.guard(() -> requestStop.executeVoid(reason));
    }

    @Override
    public void deliver(int listenerId, String payloadJson, int replyId) throws JsEngineException {
        context.guard(() -> deliver.executeVoid(listenerId, payloadJson, replyId));
    }

    @Override
    public void request(int listenerId, int exchangeId, String requestJson, int bodyId) throws JsEngineException {
        context.guard(() -> request.executeVoid(listenerId, exchangeId, requestJson, bodyId));
    }

    @Override
    public void chunk(int streamId, byte[] data) throws JsEngineException {
        context.guard(() -> chunk.executeVoid(streamId, data == null ? null : values.toUint8Array(data)));
    }

    @Override
    public void streamError(int streamId, String message) throws JsEngineException {
        context.guard(() -> streamError.executeVoid(streamId, message));
    }

    @Override
    public void close() {
        // GraalJS values are released with the context.
    }
}
