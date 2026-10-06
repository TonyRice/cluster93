package automately.runtime.javet;

import com.caoccao.javet.enums.V8ValueType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.reference.V8ValueTypedArray;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;

/** The prelude's functions the event loop calls into. Only used on the isolate's thread. */
final class ScriptEntryPoints implements AutoCloseable {

    final V8ValueFunction invoke;
    private final V8ValueFunction fire;
    private final V8ValueFunction requestStop;
    private final V8ValueFunction settle;
    private final V8ValueFunction deliver;
    private final V8ValueFunction request;
    private final V8ValueFunction chunk;
    private final V8ValueFunction streamError;
    private final V8Runtime runtime;

    ScriptEntryPoints(V8Runtime runtime, V8ValueObject prelude) throws JavetException {
        this.runtime = runtime;
        this.request = prelude.get("request");
        this.chunk = prelude.get("chunk");
        this.streamError = prelude.get("streamError");
        this.invoke = prelude.get("invoke");
        this.fire = prelude.get("fire");
        this.requestStop = prelude.get("requestStop");
        this.settle = prelude.get("settle");
        this.deliver = prelude.get("deliver");
    }

    void fireTimer(int id) throws JavetException {
        fire.callVoid(null, id);
    }

    void requestStop(String reason) throws JavetException {
        requestStop.callVoid(null, reason);
    }

    /** Resolves or rejects the promise of host call {@code callId}; {@code json} is the value or the error. */
    void settle(int callId, boolean ok, String json) throws JavetException {
        settle.callVoid(null, callId, ok, json);
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
        requestStop.close();
        settle.close();
        deliver.close();
    }
}
