package automately.runtime.javet;

import automately.core.runtime.api.JobsApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;

/** The script's own job ({@code ctx.setCallback}) in JavaScript. */
public final class JobsBinding {

    private final HostBridge bridge;
    private final JsValues js;
    private final JobsApi jobs;

    JobsBinding(HostBridge bridge, JobsApi jobs) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.jobs = jobs;
    }

    /** Where this job's result goes when it ends (D-062). */
    @V8Function(name = "setCallback")
    public V8ValuePromise setCallback(V8Value url, V8Value data) throws JavetException {
        return bridge.promise(() -> jobs.setCallback(js.string(url), js.toJava(data)).map(v -> JsValues.UNDEFINED));
    }
}
