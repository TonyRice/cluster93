package automately.runtime.javet;

import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.api.HostApis;
import automately.core.runtime.LogSink;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.ScriptRuntime;
import automately.core.runtime.StateSink;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.options.V8Flags;
import com.caoccao.javet.interop.options.V8RuntimeOptions;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8Module;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import com.caoccao.javet.values.reference.V8ValuePromise;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs each attempt in its own V8 isolate. A script is an ES module whose default export receives the
 * job input and the job context, and returns (or resolves to) a JSON value:
 *
 * <pre>{@code export default async (input, ctx) => ({ hello: input.name, attempt: ctx.attempt })}</pre>
 *
 * <p>The isolate is created for the attempt and closed afterwards, so nothing a script does can be seen by
 * the next one. The only host objects are {@code console}, timers and {@code ctx}
 * (docs/scripting/job-context.md, docs/scripting/services.md).
 *
 * <p>Each isolate runs an event loop on its own thread from a dedicated executor: timers fire, posted
 * tasks (such as a stop request) run, and an idle long-running script waits. Four things end a script
 * early: the wall-clock timeout, the per-isolate heap limit (enforced through V8's near-heap-limit
 * callback; without it an out-of-memory isolate aborts the whole JVM), {@link RunningExecution#cancel},
 * and a {@link RunningExecution#stop} whose grace period runs out.
 */
public final class JavetScriptRuntime implements ScriptRuntime {

    /** The prelude's files (src/main/resources/automately/runtime/javet/js), in order; each sees what the earlier ones define. */
    static final List<String> PRELUDE_FILES = List.of("prelude.js", "console.js", "timers.js", "listeners.js", "kv.js", "bus.js",
            "encoding.js", "streams.js", "fetch.js", "files.js", "serve.js", "context.js", "entry.js");

    /**
     * Runs before the job's module: the prelude's files in one closure. It captures the host object, removes it from the
     * global scope, installs the APIs frozen, and returns the functions the event loop calls ({@link ScriptEntryPoints}).
     * None of those become visible to the script.
     */
    static final String PRELUDE = loadPrelude();

    private static String loadPrelude() {
        StringBuilder prelude = new StringBuilder("(() => {\n");
        for (String file : PRELUDE_FILES) {
            try (java.io.InputStream in = JavetScriptRuntime.class.getResourceAsStream("js/" + file)) {
                if (in == null) {
                    throw new IllegalStateException("Missing prelude file js/" + file);
                }
                prelude.append("// --- ").append(file).append(" ---\n")
                        .append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).append('\n');
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return prelude.append("})()").toString();
    }

    /** Extra heap granted once the limit is hit, so V8 can unwind the terminated script instead of aborting. */
    private static final long HEAP_HEADROOM_BYTES = 32L * 1024 * 1024;

    private final Vertx vertx;
    private final WorkerExecutor isolates;

    /**
     * @param maxHeapMb      per-isolate heap limit. V8 applies this flag process-wide and only before the
     *                       first isolate is created, so the first runtime constructed in a JVM decides it.
     * @param isolateThreads how many isolates can run at once; use the node's capacity
     */
    public JavetScriptRuntime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        this.vertx = vertx;
        configureHeapLimit(maxHeapMb);
        // A service's isolate holds its thread for as long as it runs, so no execute-time warnings.
        this.isolates = vertx.createSharedWorkerExecutor("automately-isolates", isolateThreads, 365, TimeUnit.DAYS);
    }

    private static synchronized void configureHeapLimit(int maxHeapMb) {
        V8Flags flags = V8RuntimeOptions.V8_FLAGS;
        if (!flags.isSealed()) {
            flags.setMaxHeapSize(maxHeapMb);
        }
    }

    @Override
    public RunningExecution execute(Execution execution, LogSink logs, StateSink state, HostApis host) {
        Control control = new Control();
        Future<ExecutionResult> result = isolates.executeBlocking(() -> run(execution, logs, state, host, control), false);
        return new RunningExecution() {
            @Override
            public Future<ExecutionResult> result() {
                return result;
            }

            @Override
            public void stop(String reason, Duration grace) {
                if (control.requestStop(reason)) {
                    vertx.setTimer(Math.max(1, grace.toMillis()), id -> control.kill(ExecutionResult.cancelled(
                            "Stopped: " + reason + " (terminated after a " + grace.toMillis() + " ms grace period)")));
                }
            }

            @Override
            public void cancel(String reason) {
                control.kill(ExecutionResult.cancelled("Cancelled: " + reason));
            }
        };
    }

    /**
     * Holds the first reason a script was killed, any stop request, and the isolate while it's alive.
     * Termination and closing are synchronised so an isolate is never terminated after it was closed.
     */
    private static final class Control {

        private final AtomicReference<ExecutionResult> killed = new AtomicReference<>();
        private final EventLoop loop = new EventLoop();
        private V8Runtime runtime;
        private String stopReason;

        synchronized void attach(V8Runtime runtime) {
            this.runtime = runtime;
            if (killed.get() != null) {
                runtime.terminateExecution();
            }
        }

        synchronized void detach() {
            runtime = null;
        }

        synchronized void kill(ExecutionResult reason) {
            killed.compareAndSet(null, reason);
            if (runtime != null) {
                runtime.terminateExecution();
            }
            loop.wake();
        }

        /** Returns false if a stop was already requested. */
        synchronized boolean requestStop(String reason) {
            if (stopReason != null) {
                return false;
            }
            stopReason = reason;
            loop.post(js -> js.requestStop(reason));
            return true;
        }

        synchronized String stopReason() {
            return stopReason;
        }

        ExecutionResult killedWith() {
            return killed.get();
        }
    }

    private ExecutionResult run(Execution execution, LogSink logs, StateSink state, HostApis host, Control control) throws JavetException {
        if (control.killedWith() != null) {
            return control.killedWith();
        }
        long creating = System.nanoTime();
        try (V8Runtime v8 = V8Host.getV8Instance().createV8Runtime()) {
            automately.core.metrics.Metrics.of(vertx).isolateStarted(java.time.Duration.ofNanos(System.nanoTime() - creating));
            control.attach(v8);
            long timer = execution.hasTimeout()
                    ? vertx.setTimer(execution.timeout().toMillis(),
                    id -> control.kill(ExecutionResult.timedOut("Terminated after " + execution.timeout().toMillis() + " ms")))
                    : -1;
            v8.setNearHeapLimitCallback((currentLimit, initialLimit) -> {
                control.kill(ExecutionResult.failed("Terminated: memory limit exceeded"));
                return currentLimit + HEAP_HEADROOM_BYTES;
            });
            try {
                ExecutionResult result = invoke(v8, execution, logs, state, host, control);
                if (control.killedWith() != null) {
                    return control.killedWith();
                }
                return control.stopReason() != null ? result.asCancelled("Stopped: " + control.stopReason()) : result;
            } catch (JavetException e) {
                return control.killedWith() != null ? control.killedWith() : ExecutionResult.failed(e.getMessage());
            } finally {
                if (timer >= 0) {
                    vertx.cancelTimer(timer);
                }
                control.detach();
            }
        }
    }

    /**
     * Binds the host APIs (D-060): one Java object per API ({@code host.kv}, {@code host.bus}, …) next to the plumbing
     * (console, timers, state, streams and listeners). Javet's {@code bind} exposes only {@code @V8Function} methods,
     * never Java itself; the prelude wraps and freezes them and then deletes the host object.
     */
    private ExecutionResult invoke(V8Runtime v8, Execution execution, LogSink logs, StateSink state, HostApis host, Control control)
            throws JavetException {
        List<JavetCallbackContext> callbacks = new ArrayList<>();
        try (JsValues values = new JsValues(v8)) {
            HostBridge bridge = new HostBridge(host, control.loop, values);
            try (V8ValueObject hostObject = v8.createV8ValueObject()) {
                callbacks.addAll(hostObject.bind(new HostConsole(logs)));
                callbacks.addAll(hostObject.bind(new HostState(state)));
                callbacks.addAll(hostObject.bind(new HostTimers(control.loop)));
                callbacks.addAll(hostObject.bind(bridge));
                bindApi(v8, hostObject, "kv", new KvBinding(bridge, host.kv()), callbacks);
                bindApi(v8, hostObject, "bus", new BusBinding(bridge, host.bus()), callbacks);
                bindApi(v8, hostObject, "queue", new QueueBinding(bridge, host.queue()), callbacks);
                bindApi(v8, hostObject, "jobs", new JobsBinding(bridge, host.jobs()), callbacks);
                bindApi(v8, hostObject, "files", new FilesBinding(bridge, host.files()), callbacks);
                bindApi(v8, hostObject, "fetch", new FetchBinding(bridge, host.fetch()), callbacks);
                bindApi(v8, hostObject, "http", new HttpBinding(bridge), callbacks);
                v8.getGlobalObject().set("__automately", hostObject);
            }
            try {
                return invokeModule(v8, execution, control);
            } finally {
                // Stop listeners and answer pending requests before the isolate goes away.
                bridge.close();
                // The prelude's closures still reference these; release them only once the script is done.
                callbacks.forEach(callback -> v8.removeCallbackContext(callback.getHandle()));
            }
        }
    }

    private static void bindApi(V8Runtime v8, V8ValueObject hostObject, String name, Object binding, List<JavetCallbackContext> callbacks)
            throws JavetException {
        try (V8ValueObject api = v8.createV8ValueObject()) {
            callbacks.addAll(api.bind(binding));
            hostObject.set(name, api);
        }
    }

    private ExecutionResult invokeModule(V8Runtime v8, Execution execution, Control control) throws JavetException {
        try (V8ValueObject prelude = v8.getExecutor(PRELUDE).setResourceName("automately:prelude").execute();
             ScriptEntryPoints js = new ScriptEntryPoints(v8, prelude);
             V8Module module = v8.getExecutor(execution.script()).setResourceName("job.mjs").compileV8Module()) {
            module.executeVoid();
            try (V8ValueObject namespace = (V8ValueObject) module.getNamespace();
                 V8Value entry = namespace.get("default")) {
                if (!(entry instanceof V8ValueFunction fn)) {
                    return ExecutionResult.failed("The script must export a default function");
                }
                String context = execution.context().toScriptJson().encode();
                try (V8ValuePromise promise = js.invoke.call(null, fn, execution.input().encode(), context)) {
                    ExecutionResult early = runLoop(v8, promise, js, execution, control);
                    if (early != null) {
                        return early;
                    }
                    JsonObject envelope = new JsonObject(promise.getResultString());
                    return envelope.getBoolean("ok")
                            ? ExecutionResult.succeeded(envelope.getValue("output"))
                            : ExecutionResult.failed(envelope.getString("error"));
                }
            }
        }
    }

    /**
     * Pumps microtasks, posted tasks and timers until the script's promise settles. Returns a result if
     * the run has to end before that, or null once the promise has settled.
     */
    private static ExecutionResult runLoop(V8Runtime v8, V8ValuePromise promise, ScriptEntryPoints js, Execution execution,
                                           Control control) throws JavetException {
        EventLoop loop = control.loop;
        while (true) {
            if (control.killedWith() != null) {
                return control.killedWith();
            }
            drainMicrotasks(v8, promise, control);
            if (loop.uncaught() != null) {
                return ExecutionResult.failed(loop.uncaught());
            }
            if (!promise.isPending()) {
                return null;
            }

            boolean worked = false;
            for (EventLoop.Task task = loop.pollTask(); task != null; task = loop.pollTask()) {
                task.run(js);
                worked = true;
            }
            for (int id = loop.pollDue(); id >= 0; id = loop.pollDue()) {
                js.fireTimer(id);
                worked = true;
                drainMicrotasks(v8, promise, control);
            }
            if (worked) {
                continue;
            }

            long wait = loop.nanosUntilNextTimer();
            // Nothing scheduled: a job fails fast unless something outside it can still wake it (a host call
            // in flight, a listener, or a stop it's waiting for; its timeout still bounds that). A service waits.
            if (wait < 0 && !execution.longRunning() && !loop.canBeWoken()) {
                return ExecutionResult.failed("The script returned a promise that never settles");
            }
            EventLoop.Task task;
            try {
                task = loop.awaitTask(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ExecutionResult.cancelled("Cancelled: the isolate thread was interrupted");
            }
            if (task != null) {
                task.run(js);
            }
        }
    }

    private static void drainMicrotasks(V8Runtime v8, V8ValuePromise promise, Control control) throws JavetException {
        boolean more;
        do {
            more = v8.await();
        } while (more && promise.isPending() && control.killedWith() == null);
    }
}
