package automately.runtime.javet;

import automately.core.runtime.api.FetchApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;

/** {@code fetch} in JavaScript; {@code fetch.js} builds the request and the Response around it. */
public final class FetchBinding {

    private final HostBridge bridge;
    private final FetchApi fetch;

    FetchBinding(HostBridge bridge, FetchApi fetch) {
        this.bridge = bridge;
        this.fetch = fetch;
    }

    /** {@code request}: {@code {url, method, headers, body, timeoutMs}}. Resolves with the status and headers, and the body's stream id. */
    @V8Function(name = "fetch")
    public V8ValuePromise fetch(V8Value request) throws JavetException {
        return bridge.promise(() -> fetch.fetch(bridge.values().options(request)),
                response -> response.meta().copy().put("streamId", bridge.openStream(response.body())));
    }
}
