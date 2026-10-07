package automately.runtime.javet;

import automately.core.runtime.api.SecretsApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;

/** {@code secrets.get(name)} in JavaScript (D-068). */
public final class SecretsBinding {

    private final HostBridge bridge;
    private final JsValues js;
    private final SecretsApi secrets;

    SecretsBinding(HostBridge bridge, SecretsApi secrets) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.secrets = secrets;
    }

    @V8Function(name = "get")
    public V8ValuePromise get(V8Value name) throws JavetException {
        return bridge.promise(() -> secrets.get(js.string(name)));
    }
}
