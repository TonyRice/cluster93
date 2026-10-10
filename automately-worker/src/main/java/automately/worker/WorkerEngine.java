package automately.worker;

import automately.core.runtime.ScriptRuntime;
import io.vertx.core.Vertx;

/** The engine a worker runs (D-126): each worker build includes exactly one, found through {@link java.util.ServiceLoader}. */
public interface WorkerEngine {

    String name();

    ScriptRuntime runtime(Vertx vertx, int maxHeapMb, int isolateThreads);
}
