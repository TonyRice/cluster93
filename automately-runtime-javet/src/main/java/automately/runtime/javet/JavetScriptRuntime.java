package automately.runtime.javet;

import automately.runtime.js.ScriptRunner;
import io.vertx.core.Vertx;

/**
 * Runs each attempt in its own V8 isolate (D-002): the engine-neutral {@link ScriptRunner} over {@link JavetEngine}
 * (D-126). An isolate's heap is capped; when it runs out, a near-heap-limit callback terminates the script instead of
 * letting V8 abort the whole JVM.
 */
public final class JavetScriptRuntime extends ScriptRunner {

    /**
     * @param maxHeapMb      per-isolate heap limit. V8 applies this flag process-wide and only before the
     *                       first isolate is created, so the first runtime constructed in a JVM decides it.
     * @param isolateThreads how many isolates can run at once; use the node's capacity
     */
    public JavetScriptRuntime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        super(vertx, new JavetEngine(maxHeapMb), maxHeapMb * 1024L * 1024L, isolateThreads);
    }
}
