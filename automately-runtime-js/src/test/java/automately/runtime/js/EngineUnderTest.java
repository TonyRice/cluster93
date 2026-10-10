package automately.runtime.js;

import automately.core.runtime.ScriptRuntime;
import io.vertx.core.Vertx;

import java.util.ServiceLoader;

/**
 * The engine the conformance suite runs on (D-126): each engine module provides one through {@link ServiceLoader}, and
 * runs this module's tests against it.
 */
public interface EngineUnderTest {

    ScriptRuntime runtime(Vertx vertx, int maxHeapMb, int isolateThreads);

    /** The engine's runtime: as {@code new JavetScriptRuntime(vertx, 64, 2)} was before the suite was shared. */
    static ScriptRuntime create(Vertx vertx, int maxHeapMb, int isolateThreads) {
        EngineUnderTest engine = ServiceLoader.load(EngineUnderTest.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("No engine to run the conformance suite on: an engine module provides "
                        + EngineUnderTest.class.getName() + " through META-INF/services"));
        return engine.runtime(vertx, maxHeapMb, isolateThreads);
    }
}
