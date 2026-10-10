package automately.runtime.js;

/**
 * A JavaScript engine for runs (D-126): Javet's V8, or GraalJS. One per process; it keeps what's shared between runs,
 * such as compiled code, and makes a {@link JsContext} for each run.
 */
public interface JsEngine {

    /**
     * A fresh context for one run. {@code memoryLimitReached} is called, on any thread, when the run must end because it
     * reached its memory limit; the context then terminates the script itself.
     */
    JsContext newContext(Runnable memoryLimitReached) throws JsEngineException;
}
