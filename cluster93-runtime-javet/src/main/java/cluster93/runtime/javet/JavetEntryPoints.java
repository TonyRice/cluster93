package cluster93.runtime.javet;

import cluster93.runtime.js.EntryPoints;
import cluster93.runtime.js.JsEngineException;
import com.caoccao.javet.enums.JavetPromiseRejectEvent;
import com.caoccao.javet.enums.V8ValueType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import com.caoccao.javet.values.reference.V8ValuePromise;
import com.caoccao.javet.values.reference.V8ValueTypedArray;

/** The prelude's functions the event loop calls into, as V8 functions. Only used on the isolate's thread. */
final class JavetEntryPoints implements EntryPoints {

    private final V8ValueObject prelude;
    final V8ValueFunction invoke;
    private final V8ValueFunction fire;
    private final V8ValueFunction startImmediates;
    private final V8ValueFunction runImmediate;
    private final V8ValueFunction requestStop;
    private final V8ValueFunction deliver;
    private final V8ValueFunction request;
    private final V8ValueFunction chunk;
    private final V8ValueFunction streamError;
    private final V8ValueFunction rejected;
    private final V8ValueFunction handled;
    private final V8ValueFunction checkRejections;
    private final V8Runtime runtime;

    JavetEntryPoints(V8Runtime runtime, V8ValueObject prelude) throws JavetException {
        this.runtime = runtime;
        this.prelude = prelude;
        this.request = prelude.get("request");
        this.chunk = prelude.get("chunk");
        this.streamError = prelude.get("streamError");
        this.invoke = prelude.get("invoke");
        this.fire = prelude.get("fire");
        this.startImmediates = prelude.get("startImmediates");
        this.runImmediate = prelude.get("runImmediate");
        this.requestStop = prelude.get("requestStop");
        this.deliver = prelude.get("deliver");
        this.rejected = prelude.get("rejected");
        this.handled = prelude.get("handled");
        this.checkRejections = prelude.get("checkRejections");
    }

    /**
     * V8's report of a promise rejected with no handler, or of a handler added to one later (D-110). The prelude keeps
     * them; Javet closes both values once this returns.
     */
    void rejection(JavetPromiseRejectEvent event, V8ValuePromise promise, V8Value reason) throws JavetException {
        switch (event) {
            case PromiseRejectWithNoHandler -> rejected.callVoid(null, promise, reason);
            case PromiseHandlerAddedAfterReject -> handled.callVoid(null, promise);
            default -> { }
        }
    }

    @Override
    public void checkRejections() throws JsEngineException {
        try {
            checkRejections.callVoid(null);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void fireTimer(int id) throws JsEngineException {
        try {
            fire.callVoid(null, id);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public int startImmediates() throws JsEngineException {
        try {
            return startImmediates.callInteger(null);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void runImmediate() throws JsEngineException {
        try {
            runImmediate.callVoid(null);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void requestStop(String reason) throws JsEngineException {
        try {
            requestStop.callVoid(null, reason);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void deliver(int listenerId, String payloadJson, int replyId) throws JsEngineException {
        try {
            deliver.callVoid(null, listenerId, payloadJson, replyId);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void request(int listenerId, int exchangeId, String requestJson, int bodyId) throws JsEngineException {
        try {
            request.callVoid(null, listenerId, exchangeId, requestJson, bodyId);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void chunk(int streamId, byte[] bytes) throws JsEngineException {
        try {
            if (bytes == null) {
                chunk.callVoid(null, streamId, null);
                return;
            }
            try (V8ValueTypedArray array = runtime.createV8ValueTypedArray(V8ValueType.Uint8Array, bytes.length)) {
                array.fromBytes(bytes);
                chunk.callVoid(null, streamId, array);
            }
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void streamError(int streamId, String message) throws JsEngineException {
        try {
            streamError.callVoid(null, streamId, message);
        } catch (JavetException e) {
            throw JavetContext.wrap(e);
        }
    }

    @Override
    public void close() {
        for (V8Value value : new V8Value[]{request, chunk, streamError, invoke, fire, startImmediates, runImmediate, requestStop,
                deliver, rejected, handled, checkRejections, prelude}) {
            try {
                value.close();
            } catch (JavetException e) {
                // The isolate is going away anyway.
            }
        }
    }
}
