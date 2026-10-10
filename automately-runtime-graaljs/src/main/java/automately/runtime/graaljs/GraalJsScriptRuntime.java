package automately.runtime.graaljs;

import automately.runtime.js.ScriptRunner;
import io.vertx.core.Vertx;

/**
 * Runs each attempt in its own GraalJS context: the engine-neutral {@link ScriptRunner} over {@link GraalJsEngine}
 * (D-126). By default the guests run in the engine's own polyglot isolate, with a heap limit per run.
 */
public final class GraalJsScriptRuntime extends ScriptRunner {

    /** In the engine's own isolate, with a heap limit per run. */
    public GraalJsScriptRuntime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        this(vertx, maxHeapMb, isolateThreads, GraalJsEngine.Mode.ISOLATED);
    }

    public GraalJsScriptRuntime(Vertx vertx, int maxHeapMb, int isolateThreads, GraalJsEngine.Mode mode) {
        super(vertx, new GraalJsEngine(mode, maxHeapMb, isolateThreads), maxHeapMb * 1024L * 1024L, isolateThreads);
    }
}
