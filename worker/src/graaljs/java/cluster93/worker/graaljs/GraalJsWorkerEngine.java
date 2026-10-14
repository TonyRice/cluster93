package cluster93.worker.graaljs;

import cluster93.core.runtime.ScriptRuntime;
import cluster93.runtime.graaljs.GraalJsEngine;
import cluster93.runtime.graaljs.GraalJsScriptRuntime;
import cluster93.worker.WorkerEngine;
import io.vertx.core.Vertx;

/** Embedded GraalJS: in its own polyglot isolate by default; {@code -Dcluster93.graaljs=in-process} for the JVM's heap. */
public final class GraalJsWorkerEngine implements WorkerEngine {

    private static GraalJsEngine.Mode mode() {
        return "in-process".equals(System.getProperty("cluster93.graaljs")) ? GraalJsEngine.Mode.IN_PROCESS : GraalJsEngine.Mode.ISOLATED;
    }

    @Override
    public String name() {
        return mode() == GraalJsEngine.Mode.ISOLATED ? "graaljs" : "graaljs-in-process";
    }

    @Override
    public ScriptRuntime runtime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        return new GraalJsScriptRuntime(vertx, maxHeapMb, isolateThreads, mode());
    }
}
