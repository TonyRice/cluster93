package cluster93.runtime.javet;

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
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.callback.IJavetDirectCallable;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.JavetCallbackType;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueBoolean;
import com.caoccao.javet.values.primitive.V8ValueDouble;
import com.caoccao.javet.values.primitive.V8ValueInteger;
import com.caoccao.javet.values.primitive.V8ValueLong;
import com.caoccao.javet.values.primitive.V8ValueString;
import com.caoccao.javet.values.reference.IV8Module;
import com.caoccao.javet.values.reference.V8Module;
import com.caoccao.javet.values.reference.V8ValueError;
import com.caoccao.javet.values.reference.V8ValueObject;
import com.caoccao.javet.values.reference.V8ValuePromise;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One run's V8 isolate (D-126): the host object bound from the {@link HostFunction}s, the prelude and the job's module,
 * promises, and termination. Its heap is limited process-wide ({@link JavetEngine}); reaching the limit ends the run
 * through V8's near-heap-limit callback, so the node never aborts.
 */
final class JavetContext implements JsContext {

    /** Extra heap granted once the limit is hit, so V8 can unwind the terminated script instead of aborting. */
    private static final long HEAP_HEADROOM_BYTES = 32L * 1024 * 1024;

    private final JavetEngine engine;
    private final V8Runtime v8;
    private final JavetValues values;
    private final List<JavetCallbackContext> callbacks = new ArrayList<>();
    private JavetModuleResolver resolver;
    private JavetEntryPoints entryPoints;

    JavetContext(JavetEngine engine, Runnable memoryLimitReached) throws JsEngineException {
        this.engine = engine;
        try {
            this.v8 = V8Host.getV8I18nInstance().createV8Runtime();
        } catch (JavetException e) {
            throw wrap(e);
        }
        try {
            v8.setNearHeapLimitCallback((currentLimit, initialLimit) -> {
                memoryLimitReached.run();
                return currentLimit + HEAP_HEADROOM_BYTES;
            });
            this.values = new JavetValues(v8);
        } catch (JavetException e) {
            closeQuietly();
            throw wrap(e);
        }
    }

    static JsEngineException wrap(JavetException e) {
        return new JsEngineException(e.getMessage(), e);
    }

    // --- host functions ---

    @Override
    public void bindHost(List<Object> root, Map<String, Object> apis) throws JsEngineException {
        try (V8ValueObject host = v8.createV8ValueObject()) {
            for (Object binding : root) {
                bindFunctions(host, binding);
            }
            for (Map.Entry<String, Object> api : apis.entrySet()) {
                try (V8ValueObject object = v8.createV8ValueObject()) {
                    bindFunctions(object, api.getValue());
                    host.set(api.getKey(), object);
                }
            }
            v8.getGlobalObject().set("__c93", host);
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    /**
     * Binds each {@link HostFunction} of {@code binding} on {@code target} as a direct V8 callback: arguments arrive as
     * V8 values and are handed over as their declared types, as Javet's own {@code @V8Function} binding did. Missing
     * arguments are Javet's defaults (0, false, null).
     */
    private void bindFunctions(V8ValueObject target, Object binding) throws JavetException {
        for (Method method : binding.getClass().getMethods()) {
            HostFunction function = method.getAnnotation(HostFunction.class);
            if (function == null) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            JavetCallbackContext context = method.getReturnType() == void.class
                    ? new JavetCallbackContext(function.name(), JavetCallbackType.DirectCallNoThisAndNoResult,
                    (IJavetDirectCallable.NoThisAndNoResult<Exception>) args -> call(method, binding, types, args))
                    : new JavetCallbackContext(function.name(), JavetCallbackType.DirectCallNoThisAndResult,
                    (IJavetDirectCallable.NoThisAndResult<Exception>) args -> values.toJs(call(method, binding, types, args)));
            target.bindFunction(context);
            callbacks.add(context);
        }
    }

    private Object call(Method method, Object binding, Class<?>[] types, V8Value[] args) throws Exception {
        Object[] converted = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            converted[i] = argument(types[i], args != null && i < args.length ? args[i] : null);
        }
        try {
            return method.invoke(binding, converted);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            // An engine failure inside the binding (a termination, say) goes back to V8 as itself.
            if (cause instanceof JsEngineException engineFailure && engineFailure.getCause() instanceof JavetException javet) {
                throw javet;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw (Error) cause;
        }
    }

    private Object argument(Class<?> type, V8Value value) throws JavetException {
        if (type == JsArg.class) {
            return new JavetArg(value, values);
        }
        if (type == int.class) {
            return value instanceof V8ValueInteger i ? i.getValue()
                    : value instanceof V8ValueDouble d ? (int) (double) d.getValue()
                    : value instanceof V8ValueLong l ? (int) (long) l.getValue()
                    : 0;
        }
        if (type == double.class) {
            return value instanceof V8ValueInteger i ? (double) i.getValue()
                    : value instanceof V8ValueDouble d ? d.getValue()
                    : value instanceof V8ValueLong l ? (double) l.getValue()
                    : 0.0;
        }
        if (type == boolean.class) {
            return value instanceof V8ValueBoolean b ? b.getValue() : value != null && !value.isNullOrUndefined() && value.asBoolean();
        }
        if (type == String.class) {
            return value == null || value.isNullOrUndefined() ? null : value instanceof V8ValueString s ? s.getValue() : value.toString();
        }
        throw new IllegalStateException("A host function can't take a " + type.getName());
    }

    @Override
    public void releaseHost() {
        callbacks.forEach(callback -> v8.removeCallbackContext(callback.getHandle()));
        callbacks.clear();
    }

    // --- prelude and modules ---

    @Override
    public void useModules(ModuleSources sources) {
        this.resolver = new JavetModuleResolver(sources, engine.moduleCache());
        v8.setV8ModuleResolver(resolver);
    }

    /**
     * Runs the prelude, from V8's code cache once there is one (D-106). Parsing the prelude is most of what an isolate
     * costs to start, and it's the same source every time; the first isolate compiles it, and its compiled code, taken
     * after it ran so the functions it used are in it, is handed to every later one. V8 checks the cache against the
     * source, its flags and its version, and compiles afresh if anything differs. The cache only ever comes from this
     * process, from our own prelude.
     */
    @Override
    public EntryPoints runPrelude(String source) throws JsEngineException {
        byte[] cache = engine.preludeCache();
        try (com.caoccao.javet.values.reference.V8Script script = (cache == null ? v8.getExecutor(source) : v8.getExecutor(source, cache))
                .setResourceName("c93:prelude").compileV8Script()) {
            V8ValueObject prelude = script.execute(true);
            if (cache == null) {
                engine.preludeCache(script.getCachedData());
            }
            entryPoints = new JavetEntryPoints(v8, prelude);
            return entryPoints;
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    @Override
    public JsModule compileModule(String source, String resourceName) throws JsEngineException {
        try {
            V8Module module = v8.getExecutor(source).setResourceName(resourceName).compileV8Module();
            return new Module(module);
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    private record Module(V8Module module) implements JsModule {
        @Override
        public void close() {
            try {
                module.close();
            } catch (JavetException e) {
                // The isolate is going away anyway.
            }
        }
    }

    @Override
    public void defineGlobal(String name, String module) throws JsEngineException {
        try {
            IV8Module builtIn = resolver.resolve(v8, module, null);
            try (V8Value evaluated = builtIn.execute(true);
                 V8ValueObject namespace = (V8ValueObject) builtIn.getNamespace();
                 V8Value value = namespace.get("default")) {
                v8.getGlobalObject().set(name, value);
            }
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    @Override
    public void trackRejections(EntryPoints js, Consumer<String> failure) {
        JavetEntryPoints points = (JavetEntryPoints) js;
        v8.setPromiseRejectCallback((event, promise, reason) -> {
            try {
                points.rejection(event, promise, reason);
            } catch (JavetException e) {
                failure.accept(e.getMessage());
            }
        });
    }

    @Override
    public void stopTrackingRejections() {
        v8.setPromiseRejectCallback((event, promise, reason) -> { });
    }

    @Override
    public JsPromiseView evaluate(JsModule module) throws JsEngineException {
        try {
            V8Value evaluated = ((Module) module).module().execute(true);
            if (evaluated instanceof V8ValuePromise promise) {
                return new Promise(promise);
            }
            if (evaluated != null) {
                evaluated.close();
            }
            return null;
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    @Override
    public JsPromiseView invokeDefault(JsModule module, String inputJson, String contextJson) throws JsEngineException {
        try {
            V8ValueObject namespace = (V8ValueObject) ((Module) module).module().getNamespace();
            V8Value entry = namespace.get("default");
            V8ValuePromise promise = entryPoints.invoke.call(null, entry, inputJson, contextJson);
            return new Promise(promise, entry, namespace);
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    /** A promise the run waits on; closes the values it was made from with itself. */
    private static final class Promise implements JsPromiseView {

        private final V8ValuePromise promise;
        private final V8Value[] alongside;

        Promise(V8ValuePromise promise, V8Value... alongside) {
            this.promise = promise;
            this.alongside = alongside;
        }

        @Override
        public boolean isPending() throws JsEngineException {
            try {
                return promise.isPending();
            } catch (JavetException e) {
                throw wrap(e);
            }
        }

        @Override
        public boolean isRejected() throws JsEngineException {
            try {
                return promise.isRejected();
            } catch (JavetException e) {
                throw wrap(e);
            }
        }

        @Override
        public String resultString() throws JsEngineException {
            try {
                return promise.getResultString();
            } catch (JavetException e) {
                throw wrap(e);
            }
        }

        /** An error as the script would print it: its stack if it has one (which names the module and line), else its text. */
        @Override
        public String rejectionText() throws JsEngineException {
            try (V8Value error = promise.getResult()) {
                if (error instanceof V8ValueError e && e.getStack() != null) {
                    return e.getStack();
                }
                return error == null ? "The script failed to load" : error.toString();
            } catch (JavetException e) {
                throw wrap(e);
            }
        }

        @Override
        public void close() {
            try {
                promise.close();
                for (V8Value value : alongside) {
                    value.close();
                }
            } catch (JavetException e) {
                // The isolate is going away anyway.
            }
        }
    }

    // --- the loop's needs ---

    @Override
    public boolean drainMicrotasks() {
        return v8.await();
    }

    @Override
    public JsPromiseResolver newResolver() throws JsEngineException {
        try {
            return new Resolver(v8.createV8ValuePromise(), values);
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    /** A promise settled by the host: V8's resolver, kept until it settles. */
    private record Resolver(V8ValuePromise resolver, JavetValues values) implements JsPromiseResolver {

        @Override
        public JsHandle promise() throws JsEngineException {
            try {
                return new JsHandle(resolver.getPromise());
            } catch (JavetException e) {
                throw wrap(e);
            }
        }

        @Override
        public void resolve(Object value) throws JsEngineException {
            try (V8Value resolved = values.toJs(value)) {
                resolver.resolve(resolved);
            } catch (JavetException e) {
                throw wrap(e);
            }
        }

        @Override
        public void reject(JsError error) throws JsEngineException {
            try (V8ValueError rejected = values.error(error)) {
                resolver.reject(rejected);
            } catch (JavetException e) {
                throw wrap(e);
            }
        }

        @Override
        public void close() {
            try {
                resolver.close();
            } catch (JavetException e) {
                // The isolate is going away anyway.
            }
        }
    }

    /** Each compiled from V8's code cache once one exists, as the prelude is (D-106). */
    @Override
    public JsHandle loadWebScript(String name, String source) throws JsEngineException {
        byte[] cache = engine.webCaches().get(name);
        try (com.caoccao.javet.values.reference.V8Script script = (cache == null ? v8.getExecutor(source) : v8.getExecutor(source, cache))
                .setResourceName("c93:web/" + name).compileV8Script()) {
            V8Value value = script.execute(true);
            if (cache == null) {
                engine.webCaches().putIfAbsent(name, script.getCachedData());
            }
            return new JsHandle(value);
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    @Override
    public JsHandle builtinNamespace(String name) throws JsEngineException {
        try {
            IV8Module module = resolver.resolve(v8, name, null);
            try (V8Value evaluated = module.execute(true)) {
                return new JsHandle(module.getNamespace());
            }
        } catch (JavetException e) {
            throw wrap(e);
        }
    }

    @Override
    public void terminate() {
        v8.terminateExecution();
    }

    @Override
    public void close() {
        try {
            values.close();
        } catch (JavetException e) {
            // Closing the isolate below releases them anyway.
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            v8.close();
        } catch (JavetException e) {
            // Nothing more to release.
        }
    }
}
