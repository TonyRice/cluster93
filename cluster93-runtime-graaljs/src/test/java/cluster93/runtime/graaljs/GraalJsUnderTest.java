package cluster93.runtime.graaljs;

import cluster93.core.runtime.ScriptRuntime;
import cluster93.runtime.js.EngineUnderTest;
import io.vertx.core.Vertx;

/** Runs the engine-neutral conformance suite on GraalJS (D-126), in its default isolated mode. */
public final class GraalJsUnderTest implements EngineUnderTest {

    @Override
    public ScriptRuntime runtime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        return new GraalJsScriptRuntime(vertx, maxHeapMb, isolateThreads);
    }

    /** GraalJS's rejection handler hears only rejections still unhandled at the end of a turn. */
    @Override
    public boolean reportsRejectionsHandledLater() {
        return false;
    }
}
