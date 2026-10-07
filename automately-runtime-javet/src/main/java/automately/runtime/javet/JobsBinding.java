package automately.runtime.javet;

import automately.core.runtime.api.JobsApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;

/** The script's own job ({@code ctx.setCallback}) and the jobs it starts ({@code jobs}, D-063) in JavaScript; {@code jobs.js} wraps it. */
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

    /** {@code options}: as on {@code POST /v1/jobs}. Resolves with the job's summary. */
    @V8Function(name = "run")
    public V8ValuePromise run(V8Value script, V8Value input, V8Value options) throws JavetException {
        return bridge.promise(() -> {
            Object in = js.toJava(input);
            if (in != null && !(in instanceof io.vertx.core.json.JsonObject)) {
                throw automately.core.runtime.HostException.invalid("A job's input must be an object");
            }
            return jobs.run(js.string(script), (io.vertx.core.json.JsonObject) in, js.options(options));
        });
    }

    @V8Function(name = "get")
    public V8ValuePromise get(V8Value id) throws JavetException {
        return bridge.promise(() -> jobs.get(js.string(id)));
    }

    @V8Function(name = "result")
    public V8ValuePromise result(V8Value id) throws JavetException {
        return bridge.promise(() -> jobs.result(js.string(id)));
    }

    @V8Function(name = "stop")
    public V8ValuePromise stop(V8Value id, V8Value reason) throws JavetException {
        return bridge.promise(() -> jobs.stop(js.string(id), js.string(reason)));
    }

    @V8Function(name = "events")
    public V8ValuePromise events(V8Value id, int listenerId) throws JavetException {
        String jobId = js.string(id);
        return bridge.listen(listenerId, listener -> jobs.events(jobId, listener));
    }
}
