package automately.runtime.js;

import automately.core.runtime.api.SecretsApi;

/** {@code secrets.get(name)} in JavaScript (D-068). */
public final class SecretsBinding {

    private final HostBridge bridge;
    private final SecretsApi secrets;

    SecretsBinding(HostBridge bridge, SecretsApi secrets) {
        this.bridge = bridge;
        this.secrets = secrets;
    }

    @HostFunction(name = "get")
    public JsHandle get(JsArg name) throws JsEngineException {
        return bridge.promise(() -> secrets.get(name.string()));
    }
}
