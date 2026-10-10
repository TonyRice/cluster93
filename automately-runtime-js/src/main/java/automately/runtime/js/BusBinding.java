package automately.runtime.js;

import automately.core.runtime.api.BusApi;
import automately.core.runtime.api.HostArgs;

/** {@code bus} in JavaScript; {@code bus.js} keeps the handlers and passes their ids. */
public final class BusBinding {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final HostBridge bridge;
    private final BusApi bus;

    BusBinding(HostBridge bridge, BusApi bus) {
        this.bridge = bridge;
        this.bus = bus;
    }

    @HostFunction(name = "publish")
    public JsHandle publish(JsArg topic, JsArg message) throws JsEngineException {
        return bridge.promise(() -> bus.publish(topic.string(), message.json()).map(v -> Js.UNDEFINED));
    }

    /** {@code options}: {@code {timeoutMs = 10000}}, capped at 30 s. */
    @HostFunction(name = "request")
    public JsHandle request(JsArg topic, JsArg message, JsArg options) throws JsEngineException {
        return bridge.promise(() -> {
            Long timeout = HostArgs.positiveMillis(options.options().getValue("timeoutMs"), "timeoutMs");
            return bus.request(topic.string(), message.json(), timeout == null ? DEFAULT_TIMEOUT_MS : timeout);
        });
    }

    @HostFunction(name = "subscribe")
    public JsHandle subscribe(JsArg topic, int listenerId) throws JsEngineException {
        String name = topic.string();
        return bridge.listen(listenerId, listener -> bus.subscribe(name, listener));
    }

    @HostFunction(name = "handle")
    public JsHandle handle(JsArg topic, int listenerId) throws JsEngineException {
        String name = topic.string();
        return bridge.listen(listenerId, listener -> bus.handle(name, listener));
    }
}
