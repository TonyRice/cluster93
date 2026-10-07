package automately.runtime.javet;

import automately.core.runtime.api.HostArgs;
import automately.core.runtime.api.KvApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;
import io.vertx.core.json.JsonObject;

/** {@code kv} in JavaScript: {@code kv.js} wraps it per namespace (docs/scripting/host-apis.md). */
public final class KvBinding {

    private final HostBridge bridge;
    private final JsValues js;
    private final KvApi kv;

    KvBinding(HostBridge bridge, KvApi kv) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.kv = kv;
    }

    @V8Function(name = "get")
    public V8ValuePromise get(V8Value ns, V8Value key) throws JavetException {
        return bridge.promise(() -> kv.get(js.string(ns), js.string(key)));
    }

    /** {@code options}: {@code {ttlMs}}. */
    @V8Function(name = "set")
    public V8ValuePromise set(V8Value ns, V8Value key, V8Value value, V8Value options) throws JavetException {
        return bridge.promise(() -> {
            Object json = js.toJava(value);
            Long ttl = HostArgs.positiveMillis(js.options(options).getValue("ttlMs"), "ttlMs");
            return kv.set(js.string(ns), js.string(key), json, ttl).map(v -> JsValues.UNDEFINED);
        });
    }

    @V8Function(name = "delete")
    public V8ValuePromise delete(V8Value ns, V8Value key) throws JavetException {
        return bridge.promise(() -> kv.delete(js.string(ns), js.string(key)));
    }

    /** {@code options}: {@code {prefix = '', limit = 100, cursor}}. */
    @V8Function(name = "list")
    public V8ValuePromise list(V8Value ns, V8Value options) throws JavetException {
        return bridge.promise(() -> {
            JsonObject o = js.options(options);
            return kv.list(js.string(ns), HostArgs.string(o.getValue("prefix", "")),
                    HostArgs.intBetween(o.getValue("limit", 100), "limit", 1, KvApi.MAX_LIST_LIMIT), HostArgs.string(o.getValue("cursor")));
        });
    }

    @V8Function(name = "increment")
    public V8ValuePromise increment(V8Value ns, V8Value key, V8Value by) throws JavetException {
        return bridge.promise(() -> {
            Object amount = js.toJava(by);
            return kv.increment(js.string(ns), js.string(key), HostArgs.integer(amount == null ? 1 : amount, "by"));
        });
    }
}
