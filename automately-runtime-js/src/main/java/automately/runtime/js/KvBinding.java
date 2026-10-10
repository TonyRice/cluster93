package automately.runtime.js;

import automately.core.runtime.api.HostArgs;
import automately.core.runtime.api.KvApi;
import io.vertx.core.json.JsonObject;

/** {@code kv} in JavaScript: {@code kv.js} wraps it per namespace (docs/scripting/host-apis.md). */
public final class KvBinding {

    private final HostBridge bridge;
    private final KvApi kv;

    KvBinding(HostBridge bridge, KvApi kv) {
        this.bridge = bridge;
        this.kv = kv;
    }

    @HostFunction(name = "get")
    public JsHandle get(JsArg ns, JsArg key) throws JsEngineException {
        return bridge.promise(() -> kv.get(ns.string(), key.string()));
    }

    /** {@code options}: {@code {ttlMs}}. */
    @HostFunction(name = "set")
    public JsHandle set(JsArg ns, JsArg key, JsArg value, JsArg options) throws JsEngineException {
        return bridge.promise(() -> {
            Object json = value.json();
            Long ttl = HostArgs.positiveMillis(options.options().getValue("ttlMs"), "ttlMs");
            return kv.set(ns.string(), key.string(), json, ttl).map(v -> Js.UNDEFINED);
        });
    }

    @HostFunction(name = "delete")
    public JsHandle delete(JsArg ns, JsArg key) throws JsEngineException {
        return bridge.promise(() -> kv.delete(ns.string(), key.string()));
    }

    /** {@code options}: {@code {prefix = '', limit = 100, cursor}}. */
    @HostFunction(name = "list")
    public JsHandle list(JsArg ns, JsArg options) throws JsEngineException {
        return bridge.promise(() -> {
            JsonObject o = options.options();
            return kv.list(ns.string(), HostArgs.string(o.getValue("prefix", "")),
                    HostArgs.intBetween(o.getValue("limit", 100), "limit", 1, KvApi.MAX_LIST_LIMIT), HostArgs.string(o.getValue("cursor")));
        });
    }

    @HostFunction(name = "increment")
    public JsHandle increment(JsArg ns, JsArg key, JsArg by) throws JsEngineException {
        return bridge.promise(() -> {
            Object amount = by.json();
            return kv.increment(ns.string(), key.string(), HostArgs.integer(amount == null ? 1 : amount, "by"));
        });
    }
}
