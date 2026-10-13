package cluster93.runtime.js;

import cluster93.core.runtime.ScriptRuntime;
import io.vertx.core.Vertx;

import java.util.ServiceLoader;

/**
 * The engine the conformance suite runs on (D-126): each engine module provides one through {@link ServiceLoader}, and
 * runs this module's tests against it.
 */
public interface EngineUnderTest {

    ScriptRuntime runtime(Vertx vertx, int maxHeapMb, int isolateThreads);

    /**
     * Whether the engine reports a rejected promise handled in a later turn, so Node's {@code rejectionHandled} fires
     * (D-110). V8 does; GraalJS reports only rejections still unhandled at the end of a turn.
     */
    default boolean reportsRejectionsHandledLater() {
        return true;
    }

    /** The engine the suite runs on. */
    static EngineUnderTest engine() {
        return ServiceLoader.load(EngineUnderTest.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("No engine to run the conformance suite on: an engine module provides "
                        + EngineUnderTest.class.getName() + " through META-INF/services"));
    }

    /** The engine's runtime: as {@code new JavetScriptRuntime(vertx, 64, 2)} was before the suite was shared. */
    static ScriptRuntime create(Vertx vertx, int maxHeapMb, int isolateThreads) {
        return engine().runtime(vertx, maxHeapMb, isolateThreads);
    }
}
