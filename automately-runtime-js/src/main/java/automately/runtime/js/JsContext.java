package automately.runtime.js;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One run's JavaScript world in one engine (D-126). Everything here happens on the run's own thread, except
 * {@link #terminate}. The engine-neutral {@link ScriptRunner} drives it; each engine adapter implements it.
 */
public interface JsContext extends AutoCloseable {

    /**
     * Makes the host object, {@code globalThis.__automately}, which the prelude captures and then removes: the
     * {@link HostFunction}s of each of {@code root} on it directly, and each of {@code apis} as an object under its name.
     */
    void bindHost(List<Object> root, Map<String, Object> apis) throws JsEngineException;

    /** Where the job's imports come from: built-ins and the job's packages (D-079, D-089). */
    void useModules(ModuleSources sources);

    /** Runs the prelude; the functions it returns become the event loop's entry points. */
    EntryPoints runPrelude(String source) throws JsEngineException;

    /** Compiles the job's own module. */
    JsModule compileModule(String source, String resourceName) throws JsEngineException;

    /** {@code globalThis[name]} becomes built-in {@code module}'s default export, as {@code process} is (D-096). */
    void defineGlobal(String name, String module) throws JsEngineException;

    /**
     * From now on, reports each promise rejected with no handler, and each handled later, to the prelude (D-110).
     * {@code failure} hears of an error in doing so.
     */
    void trackRejections(EntryPoints js, Consumer<String> failure);

    void stopTrackingRejections();

    /** Evaluates the module: the promise its evaluation answers, or null if it didn't answer one. */
    JsPromiseView evaluate(JsModule module) throws JsEngineException;

    /** Calls the prelude's {@code invoke} with the module's default export, the input and the context, both as JSON. */
    JsPromiseView invokeDefault(JsModule module, String inputJson, String contextJson) throws JsEngineException;

    /** Runs the queued microtasks; whether more work remains. */
    boolean drainMicrotasks() throws JsEngineException;

    /** A promise the host will settle later. */
    JsPromiseResolver newResolver() throws JsEngineException;

    /** Evaluates one of the WHATWG globals' scripts (D-124) and answers what it evaluates to. */
    JsHandle loadWebScript(String name, String source) throws JsEngineException;

    /** A built-in module's namespace, evaluated if it wasn't yet: the same instance the job's own imports get (D-125). */
    JsHandle builtinNamespace(String name) throws JsEngineException;

    /** Stops the script as soon as possible. Any thread. */
    void terminate();

    /** Releases the host functions once the run is over; the prelude's closures hold them until then. */
    void releaseHost();

    @Override
    void close();
}
