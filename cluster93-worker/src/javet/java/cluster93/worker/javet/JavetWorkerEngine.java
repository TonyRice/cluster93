package cluster93.worker.javet;

import cluster93.core.runtime.ScriptRuntime;
import cluster93.runtime.javet.JavetScriptRuntime;
import cluster93.worker.WorkerEngine;
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
