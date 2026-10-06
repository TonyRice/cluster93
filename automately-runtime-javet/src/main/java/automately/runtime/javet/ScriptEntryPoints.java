package automately.runtime.javet;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;

/** The prelude's functions the event loop calls into. Only used on the isolate's thread. */
final class ScriptEntryPoints implements AutoCloseable {

    final V8ValueFunction invoke;
    private final V8ValueFunction fire;
    private final V8ValueFunction requestStop;
    private final V8ValueFunction settle;
    private final V8ValueFunction deliver;

    ScriptEntryPoints(V8ValueObject prelude) throws JavetException {
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

    @Override
    public void close() throws JavetException {
        invoke.close();
        fire.close();
        requestStop.close();
        settle.close();
        deliver.close();
    }
}
