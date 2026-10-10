package automately.runtime.javet;

import automately.core.runtime.ScriptRuntime;
import automately.runtime.js.EngineUnderTest;
import io.vertx.core.Vertx;

/** Runs the engine-neutral conformance suite on V8 (D-126). */
public final class JavetUnderTest implements EngineUnderTest {

    @Override
    public ScriptRuntime runtime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        return new JavetScriptRuntime(vertx, maxHeapMb, isolateThreads);
    }
}
