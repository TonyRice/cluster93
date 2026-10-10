package automately.worker.graaljs;

import automately.core.runtime.ScriptRuntime;
import automately.runtime.graaljs.GraalJsEngine;
import automately.runtime.graaljs.GraalJsScriptRuntime;
import automately.worker.WorkerEngine;
import io.vertx.core.Vertx;

/** Embedded GraalJS: in its own polyglot isolate by default; {@code -Dautomately.graaljs=in-process} for the JVM's heap. */
public final class GraalJsWorkerEngine implements WorkerEngine {

    private static GraalJsEngine.Mode mode() {
        return "in-process".equals(System.getProperty("automately.graaljs")) ? GraalJsEngine.Mode.IN_PROCESS : GraalJsEngine.Mode.ISOLATED;
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
