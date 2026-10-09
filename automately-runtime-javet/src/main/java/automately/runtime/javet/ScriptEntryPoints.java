package automately.runtime.javet;

import com.caoccao.javet.enums.JavetPromiseRejectEvent;
import com.caoccao.javet.enums.V8ValueType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;
import com.caoccao.javet.values.reference.V8ValueTypedArray;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;

/** The prelude's functions the event loop calls into. Only used on the isolate's thread. */
final class ScriptEntryPoints implements AutoCloseable {

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

    ScriptEntryPoints(V8Runtime runtime, V8ValueObject prelude) throws JavetException {
        this.runtime = runtime;
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

    /** Ends the job, or tells process listeners, for each rejection still unhandled after this turn of the loop. */
    void checkRejections() throws JavetException {
        checkRejections.callVoid(null);
    }

    void fireTimer(int id) throws JavetException {
        fire.callVoid(null, id);
    }

    /** Begins a check phase: how many immediates it runs (those queued before it). */
    int startImmediates() throws JavetException {
        return startImmediates.callInteger(null);
    }

    void runImmediate() throws JavetException {
        runImmediate.callVoid(null);
    }

    void requestStop(String reason) throws JavetException {
        requestStop.callVoid(null, reason);
    }

    /** Hands an inbound event to listener {@code listenerId}; {@code replyId} is 0 if no answer is expected. */
    void deliver(int listenerId, String payloadJson, int replyId) throws JavetException {
        deliver.callVoid(null, listenerId, payloadJson, replyId);
    }

    /** An HTTP request for serve listener {@code listenerId}; its body arrives on stream {@code bodyId}. */
    void request(int listenerId, int exchangeId, String requestJson, int bodyId) throws JavetException {
        request.callVoid(null, listenerId, exchangeId, requestJson, bodyId);
    }

    /** The next chunk of stream {@code streamId} as a {@code Uint8Array}, or the end of the stream if null. */
    void chunk(int streamId, byte[] bytes) throws JavetException {
        if (bytes == null) {
            chunk.callVoid(null, streamId, null);
            return;
        }
        try (V8ValueTypedArray array = runtime.createV8ValueTypedArray(V8ValueType.Uint8Array, bytes.length)) {
            array.fromBytes(bytes);
            chunk.callVoid(null, streamId, array);
        }
    }

    void streamError(int streamId, String message) throws JavetException {
        streamError.callVoid(null, streamId, message);
    }

    @Override
    public void close() throws JavetException {
        request.close();
        chunk.close();
        streamError.close();
        invoke.close();
        fire.close();
        startImmediates.close();
        runImmediate.close();
        requestStop.close();
        deliver.close();
        rejected.close();
        handled.close();
        checkRejections.close();
    }
}
