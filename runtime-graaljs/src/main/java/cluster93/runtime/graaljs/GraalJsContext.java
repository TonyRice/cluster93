package cluster93.runtime.graaljs;

import cluster93.runtime.js.EntryPoints;
import cluster93.runtime.js.HostFunction;
import cluster93.runtime.js.JsArg;
import cluster93.runtime.js.JsContext;
import cluster93.runtime.js.JsEngineException;
import cluster93.runtime.js.JsError;
import cluster93.runtime.js.JsHandle;
import cluster93.runtime.js.JsModule;
import cluster93.runtime.js.JsPromiseResolver;
import cluster93.runtime.js.JsPromiseView;
import cluster93.runtime.js.ModuleSources;
import io.vertx.core.json.Json;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One run's GraalJS context (D-126): the host object built from the {@link HostFunction}s as proxies, the prelude, the
 * job's module through {@link GraalJsModules}, promises, and termination. In the default mode its guest runs in the
 * engine's own polyglot isolate, with a heap limit; reaching it ends the run, not the JVM.
 */
final class GraalJsContext implements JsContext {

    private final GraalJsEngine engine;
    private final Context context;
    private final Runnable memoryLimitReached;
    private final GraalJsValues values;
    /** The context's file system, which serves the run's modules once {@link #useModules} names them. */
    private final GraalJsFileSystem files = new GraalJsFileSystem();
    private GraalJsModules modules;
    private GraalJsEntryPoints entryPoints;
    private Value rejectionHandler;

    GraalJsContext(GraalJsEngine engine, Runnable memoryLimitReached) throws JsEngineException {
        this.engine = engine;
        this.memoryLimitReached = memoryLimitReached;
        this.context = engine.newContext(files);
        try {
            this.values = new GraalJsValues(context.eval(engine.helpers()), engine.mode() == GraalJsEngine.Mode.ISOLATED);
        } catch (PolyglotException e) {
            close();
            throw wrap(e);
        }
    }

    // --- failures ---

    @FunctionalInterface
    interface Act {
        void run();
    }

    @FunctionalInterface
    interface Call<T> {
        T run();
    }

    void guard(Act act) throws JsEngineException {
        guardGet(() -> {
            act.run();
            return null;
        });
    }

    /** Runs a call into the context, turning GraalJS's failures into the engine-neutral one. */
    <T> T guardGet(Call<T> call) throws JsEngineException {
        try {
            return call.run();
        } catch (PolyglotException e) {
            throw wrap(e);
        } catch (IllegalStateException e) {
            // The context was closed: cancelled from another thread.
            throw new JsEngineException(e.getMessage(), e);
        }
    }

    private JsEngineException wrap(PolyglotException e) {
        if (e.isResourceExhausted()) {
            memoryLimitReached.run();
        }
        return new JsEngineException(e.getMessage(), e);
    }

    // --- host functions ---

    @Override
    public void bindHost(List<Object> root, Map<String, Object> apis) throws JsEngineException {
        Map<String, Object> host = new HashMap<>();
        for (Object binding : root) {
            host.putAll(functions(binding));
        }
        for (Map.Entry<String, Object> api : apis.entrySet()) {
            host.put(api.getKey(), ProxyObject.fromMap(functions(api.getValue())));
        }
        guard(() -> context.getBindings("js").putMember("__c93", ProxyObject.fromMap(host)));
    }

    /**
     * Each {@link HostFunction} of {@code binding} as a proxy function: arguments arrive as GraalJS values and are handed
     * over as their declared types, as Javet's adapter does. A Java exception is thrown into the script as an Error.
     */
    private Map<String, Object> functions(Object binding) {
        Map<String, Object> functions = new HashMap<>();
        for (Method method : binding.getClass().getMethods()) {
            HostFunction function = method.getAnnotation(HostFunction.class);
            if (function == null) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            boolean returns = method.getReturnType() != void.class;
            functions.put(function.name(), (ProxyExecutable) args -> {
                Object result = call(method, binding, types, args);
                return returns ? values.toJs(result) : values.undefined();
            });
        }
        return functions;
    }

    private Object call(Method method, Object binding, Class<?>[] types, Value[] args) {
        Object[] converted = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            converted[i] = argument(types[i], args != null && i < args.length ? args[i] : null);
        }
        try {
            return method.invoke(binding, converted);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof JsEngineException engineFailure && engineFailure.getCause() instanceof PolyglotException polyglot) {
                throw polyglot;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw values.error(JsError.of(cause)).throwException();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object argument(Class<?> type, Value value) {
        if (type == JsArg.class) {
            return new GraalJsArg(value, values);
        }
        boolean number = value != null && value.isNumber() && !values.isBigInt(value);
        if (type == int.class) {
            return number ? (value.fitsInInt() ? value.asInt() : (int) value.asDouble()) : 0;
        }
        if (type == double.class) {
            return number ? value.asDouble() : 0.0;
        }
        if (type == boolean.class) {
            return value != null && (value.isBoolean() ? value.asBoolean() : !value.isNull() && values.truthy(value));
        }
        if (type == String.class) {
            return value == null || value.isNull() ? null : value.isString() ? value.asString() : values.string(value);
        }
        throw new IllegalStateException("A host function can't take a " + type.getName());
    }

    @Override
    public void releaseHost() {
        // The proxies go with the context.
    }

    // --- prelude and modules ---

    @Override
    public void useModules(ModuleSources sources) {
        this.modules = new GraalJsModules(sources, engine.sharedSources());
        files.serve(modules);
    }

    @Override
    public EntryPoints runPrelude(String source) throws JsEngineException {
        Value prelude = guardGet(() -> context.eval(engine.prelude(source)));
        entryPoints = new GraalJsEntryPoints(this, prelude, values);
        return entryPoints;
    }

    @Override
    public JsModule compileModule(String source, String resourceName) {
        return new Module(modules.job(source));
    }

    /** The job's module, by its path; loaded when evaluated. */
    private static final class Module implements JsModule {
        final String path;
        Value namespace;

        Module(String path) {
            this.path = path;
        }

        @Override
        public void close() {
        }
    }

    @Override
    public void defineGlobal(String name, String module) throws JsEngineException {
        String path = modules.builtIn(module);
        String code = "import value from " + Json.encode(path) + "; globalThis[" + Json.encode(name) + "] = value;";
        guard(() -> context.eval(Source.newBuilder("js", code, "/@define/" + name + ".mjs").mimeType("application/javascript+module").buildLiteral()));
    }

    @Override
    public void trackRejections(EntryPoints js, Consumer<String> failure) {
        try {
            Value set = values.helper("trackRejections").execute(((GraalJsEntryPoints) js).rejected);
            rejectionHandler = set.isNull() ? null : set;
        } catch (PolyglotException e) {
            failure.accept(e.getMessage());
        }
    }

    @Override
    public void stopTrackingRejections() {
        if (rejectionHandler != null) {
            try {
                rejectionHandler.execute(values.helper("truthy"));
            } catch (PolyglotException | IllegalStateException e) {
                // The context is going away.
            }
        }
    }

    @Override
    public JsPromiseView evaluate(JsModule module) throws JsEngineException {
        Module job = (Module) module;
        Value seen = guardGet(() -> values.helper("watch").execute(values.helper("load").execute(job.path)));
        return new Promise(this, seen) {
            @Override
            public boolean isPending() throws JsEngineException {
                boolean pending = super.isPending();
                if (!pending && job.namespace == null && "fulfilled".equals(state())) {
                    job.namespace = value();
                }
                return pending;
            }
        };
    }

    @Override
    public JsPromiseView invokeDefault(JsModule module, String inputJson, String contextJson) throws JsEngineException {
        Module job = (Module) module;
        return guardGet(() -> {
            Value entry = job.namespace == null ? values.undefined() : job.namespace.getMember("default");
            Value promise = entryPoints.invoke.execute(entry == null ? values.undefined() : entry, inputJson, contextJson);
            return new Promise(this, values.helper("watch").execute(promise));
        });
    }

    /** A failure's text with modules named as V8 names them: {@code job.mjs}, {@code file:///packages/…}. */
    static String asV8Names(String text) {
        return text.replaceAll("(?<=[ (])/job\\.mjs", "job.mjs").replaceAll("(?<=[ (])/packages/", "file:///packages/");
    }

    /** A promise the run waits on, through the helper that records its state. */
    private static class Promise implements JsPromiseView {

        private final GraalJsContext owner;
        private final Value seen;

        Promise(GraalJsContext owner, Value seen) {
            this.owner = owner;
            this.seen = seen;
        }

        String state() throws JsEngineException {
            return owner.guardGet(() -> seen.getMember("state").asString());
        }

        Value value() throws JsEngineException {
            return owner.guardGet(() -> seen.getMember("value"));
        }

        @Override
        public boolean isPending() throws JsEngineException {
            return "pending".equals(state());
        }

        @Override
        public boolean isRejected() throws JsEngineException {
            return "rejected".equals(state());
        }

        @Override
        public String resultString() throws JsEngineException {
            Value value = value();
            return owner.guardGet(() -> value.isString() ? value.asString() : owner.values.string(value));
        }

        @Override
        public String rejectionText() throws JsEngineException {
            Value value = value();
            return owner.guardGet(() -> value == null || value.isNull() ? "The script failed to load"
                    : asV8Names(owner.values.helper("stack").execute(value).asString()));
        }

        @Override
        public void close() {
        }
    }

    // --- the loop's needs ---

    /** GraalJS runs the queued microtasks itself whenever a call into the context returns. */
    @Override
    public boolean drainMicrotasks() {
        return false;
    }

    @Override
    public JsPromiseResolver newResolver() throws JsEngineException {
        Value triple = guardGet(values::newPromise);
        return new JsPromiseResolver() {
            @Override
            public JsHandle promise() {
                return new JsHandle(triple.getArrayElement(0));
            }

            @Override
            public void resolve(Object value) throws JsEngineException {
                guard(() -> triple.getArrayElement(1).executeVoid(values.toJs(value)));
            }

            @Override
            public void reject(JsError error) throws JsEngineException {
                guard(() -> triple.getArrayElement(2).executeVoid(values.error(error)));
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public JsHandle loadWebScript(String name, String source) throws JsEngineException {
        return new JsHandle(guardGet(() -> context.eval(engine.webScript(name, source))));
    }

    /**
     * A built-in's namespace, now: a module that re-exports it, evaluated through the job's own module map, so it's the
     * instance the job's imports get. Called from inside a host function, where an import()'s promise can't settle.
     */
    @Override
    public JsHandle builtinNamespace(String name) throws JsEngineException {
        String path = modules.builtIn(name);
        String code = "export * as namespace from " + Json.encode(path) + ";";
        Value exports = guardGet(() -> context.eval(Source.newBuilder("js", code, "/@namespace/" + Integer.toHexString(path.hashCode()) + ".mjs")
                .mimeType("application/javascript+module").buildLiteral()));
        return new JsHandle(exports.getMember("namespace"));
    }

    @Override
    public void terminate() {
        try {
            context.close(true);
        } catch (RuntimeException e) {
            // Already closed, or closing.
        }
    }

    @Override
    public void close() {
        try {
            context.close(true);
        } catch (RuntimeException e) {
            // A cancelled context reports its cancellation again on closing.
        }
    }
}
