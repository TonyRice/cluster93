package automately.runtime.js;

import automately.core.runtime.api.JobsApi;

/** The script's own job ({@code ctx.setCallback}) and the jobs it starts ({@code jobs}, D-063) in JavaScript; {@code jobs.js} wraps it. */
public final class JobsBinding {

    private final HostBridge bridge;
    private final JobsApi jobs;

    JobsBinding(HostBridge bridge, JobsApi jobs) {
        this.bridge = bridge;
        this.jobs = jobs;
    }

    /** Where this job's result goes when it ends (D-062). */
    @HostFunction(name = "setCallback")
    public JsHandle setCallback(JsArg url, JsArg data) throws JsEngineException {
        return bridge.promise(() -> jobs.setCallback(url.string(), data.json()).map(v -> Js.UNDEFINED));
    }

    /** {@code options}: as on {@code POST /v1/jobs}. Resolves with the job's summary. */
    @HostFunction(name = "run")
    public JsHandle run(JsArg script, JsArg input, JsArg options) throws JsEngineException {
        return bridge.promise(() -> {
            Object in = input.json();
            if (in != null && !(in instanceof io.vertx.core.json.JsonObject)) {
                throw automately.core.runtime.HostException.invalid("A job's input must be an object");
            }
            return jobs.run(script.string(), (io.vertx.core.json.JsonObject) in, options.options());
        });
    }

    @HostFunction(name = "get")
    public JsHandle get(JsArg id) throws JsEngineException {
        return bridge.promise(() -> jobs.get(id.string()));
    }

    @HostFunction(name = "result")
    public JsHandle result(JsArg id) throws JsEngineException {
        return bridge.promise(() -> jobs.result(id.string()));
    }

    @HostFunction(name = "stop")
    public JsHandle stop(JsArg id, JsArg reason) throws JsEngineException {
        return bridge.promise(() -> jobs.stop(id.string(), reason.string()));
    }

    @HostFunction(name = "events")
    public JsHandle events(JsArg id, int listenerId) throws JsEngineException {
        String jobId = id.string();
        return bridge.listen(listenerId, listener -> jobs.events(jobId, listener));
    }
}
