package automately.worker.javet;

import automately.core.runtime.ScriptRuntime;
import automately.runtime.javet.JavetScriptRuntime;
import automately.worker.WorkerEngine;
import io.vertx.core.Vertx;

/** V8, through Javet. */
public final class JavetWorkerEngine implements WorkerEngine {

    @Override
    public String name() {
        return "javet";
    }

    @Override
    public ScriptRuntime runtime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        return new JavetScriptRuntime(vertx, maxHeapMb, isolateThreads);
    }
}
