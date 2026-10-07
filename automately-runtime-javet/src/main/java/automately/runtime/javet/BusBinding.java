package automately.runtime.javet;

import automately.core.runtime.api.BusApi;
import automately.core.runtime.api.HostArgs;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;

/** {@code bus} in JavaScript; {@code bus.js} keeps the handlers and passes their ids. */
public final class BusBinding {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final HostBridge bridge;
    private final JsValues js;
    private final BusApi bus;

    BusBinding(HostBridge bridge, BusApi bus) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.bus = bus;
    }

    @V8Function(name = "publish")
    public V8ValuePromise publish(V8Value topic, V8Value message) throws JavetException {
        return bridge.promise(() -> bus.publish(js.string(topic), js.toJava(message)).map(v -> JsValues.UNDEFINED));
    }

    /** {@code options}: {@code {timeoutMs = 10000}}, capped at 30 s. */
    @V8Function(name = "request")
    public V8ValuePromise request(V8Value topic, V8Value message, V8Value options) throws JavetException {
        return bridge.promise(() -> {
            Long timeout = HostArgs.positiveMillis(js.options(options).getValue("timeoutMs"), "timeoutMs");
            return bus.request(js.string(topic), js.toJava(message), timeout == null ? DEFAULT_TIMEOUT_MS : timeout);
        });
    }

    @V8Function(name = "subscribe")
    public V8ValuePromise subscribe(V8Value topic, int listenerId) throws JavetException {
        String name = js.string(topic);
        return bridge.listen(listenerId, listener -> bus.subscribe(name, listener));
    }

    @V8Function(name = "handle")
    public V8ValuePromise handle(V8Value topic, int listenerId) throws JavetException {
        String name = js.string(topic);
        return bridge.listen(listenerId, listener -> bus.handle(name, listener));
    }
}
