package cluster93.runtime.js;

import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.LogSink;
import cluster93.core.runtime.RunningExecution;
import cluster93.core.runtime.ScriptRuntime;
import cluster93.core.runtime.StateSink;
import cluster93.core.runtime.api.HostApis;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs each attempt in its own engine context, on any {@link JsEngine} (D-126). A script is an ES module whose default
 * export receives the job input and the job context, and returns (or resolves to) a JSON value:
 *
 * <pre>{@code export default async (input, ctx) => ({ hello: input.name, attempt: ctx.attempt })}</pre>
 *
 * <p>The context is created for the attempt and closed afterwards, so nothing a script does can be seen by the next one.
 * The script reaches the host only through the frozen APIs the prelude builds on the host object
 * (docs/scripting/job-context.md, docs/scripting/services.md).
 *
 * <p>Each context runs an event loop on its own thread from a dedicated executor: timers fire, posted tasks (such as a
 * stop request) run, and an idle long-running script waits. Four things end a script early: the wall-clock timeout, the
 * memory limit (the engine reports reaching it), {@link RunningExecution#cancel}, and a {@link RunningExecution#stop}
 * whose grace period runs out.
 */
public class ScriptRunner implements ScriptRuntime {

    private final Vertx vertx;
    private final JsEngine engine;
    /** The memory each context gets, as os.totalmem() reports it. */
    private final long heapBytes;
    private final WorkerExecutor isolates;

    /**
     * @param heapBytes      the memory each context may use, as {@code os.totalmem()} reports it
     * @param isolateThreads how many contexts can run at once; use the node's capacity
     */
    public ScriptRunner(Vertx vertx, JsEngine engine, long heapBytes, int isolateThreads) {
        this.vertx = vertx;
        this.engine = engine;
        this.heapBytes = heapBytes;
        // A service's context holds its thread for as long as it runs, so no execute-time warnings.
        this.isolates = vertx.createSharedWorkerExecutor("cluster93-isolates", isolateThreads, 365, TimeUnit.DAYS);
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
     * Holds the first reason a script was killed, any stop request, and the context while it's alive.
     * Termination and closing are synchronised so a context is never terminated after it was closed.
     */
    private static final class Control {

        private final AtomicReference<ExecutionResult> killed = new AtomicReference<>();
        private final EventLoop loop = new EventLoop();
        private JsContext context;
        private String stopReason;

        synchronized void attach(JsContext context) {
            this.context = context;
            if (killed.get() != null) {
                context.terminate();
            }
        }

        synchronized void detach() {
            context = null;
        }

        synchronized void kill(ExecutionResult reason) {
            killed.compareAndSet(null, reason);
            if (context != null) {
                context.terminate();
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

    private ExecutionResult run(Execution execution, LogSink logs, StateSink state, HostApis host, Control control) throws JsEngineException {
        if (control.killedWith() != null) {
            return control.killedWith();
        }
        long creating = System.nanoTime();
        try (JsContext context = engine.newContext(() -> control.kill(ExecutionResult.failed("Terminated: memory limit exceeded")))) {
            cluster93.core.metrics.Metrics.of(vertx).isolateStarted(Duration.ofNanos(System.nanoTime() - creating));
            control.attach(context);
            long timer = execution.hasTimeout()
                    ? vertx.setTimer(execution.timeout().toMillis(),
                    id -> control.kill(ExecutionResult.timedOut("Terminated after " + execution.timeout().toMillis() + " ms")))
                    : -1;
            try {
                ExecutionResult result = invoke(context, execution, logs, state, host, control);
                if (control.killedWith() != null) {
                    return control.killedWith();
                }
                return control.stopReason() != null ? result.asCancelled("Stopped: " + control.stopReason()) : result;
            } catch (JsEngineException e) {
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
     * (console, timers, state, streams and listeners). Only {@link HostFunction} methods are exposed, never Java itself;
     * the prelude wraps and freezes them and then deletes the host object.
     */
    private ExecutionResult invoke(JsContext context, Execution execution, LogSink logs, StateSink state, HostApis host, Control control)
            throws JsEngineException {
        HostBridge bridge = new HostBridge(host, control.loop, context);
        List<Object> root = List.of(new HostConsole(logs), new HostState(state), new HostTimers(control.loop), bridge);
        Map<String, Object> apis = new LinkedHashMap<>();
        apis.put("kv", new KvBinding(bridge, host.kv()));
        apis.put("bus", new BusBinding(bridge, host.bus()));
        apis.put("queue", new QueueBinding(bridge, host.queue()));
        apis.put("jobs", new JobsBinding(bridge, host.jobs()));
        apis.put("files", new FilesBinding(bridge, host.files()));
        apis.put("fetch", new FetchBinding(bridge, host.fetch()));
        apis.put("sockets", new SocketBinding(bridge, host.sockets()));
        apis.put("http2", new Http2Binding(bridge, host.sockets()));
        apis.put("dns", new DnsBinding(bridge, host.dns()));
        apis.put("http", new HttpBinding(bridge));
        apis.put("secrets", new SecretsBinding(bridge, host.secrets()));
        apis.put("fs", new FsBinding(bridge, host.fs()));
        apis.put("crypto", new CryptoBinding(bridge, host.crypto()));
        apis.put("zlib", new ZlibBinding(bridge, host.zlib()));
        apis.put("web", new WebBinding(context));
        context.bindHost(root, apis);
        try {
            return invokeModule(context, execution, control);
        } finally {
            // Stop listeners and answer pending requests before the context goes away.
            bridge.close();
            // The prelude's closures still reference the host functions; release them only once the script is done.
            context.releaseHost();
        }
    }

    private ExecutionResult invokeModule(JsContext context, Execution execution, Control control) throws JsEngineException {
        // Built-ins (D-079) and the job's packages (D-089), all already in memory.
        context.useModules(new ModuleSources(execution.packages(), Builtins.BUILT_IN_MODULES, Builtins.forJob(execution.env(), heapBytes)));
        try (EntryPoints js = context.runPrelude(Builtins.PRELUDE);
             JsModule module = context.compileModule(cluster93.core.packages.Imports.rewrite(execution.script(), ModuleSources.JOB_URL),
                     ModuleSources.JOB)) {
            // process as a global (D-096), as in Node: the same object import process from 'node:process' gives.
            context.defineGlobal("process", "node:process");
            context.trackRejections(js, error -> control.loop.reportUncaught("Could not track a promise rejection: " + error));
            try {
                return evaluate(context, module, js, execution, control);
            } finally {
                // Nothing after this block, such as the bridge closing, is the script's.
                context.stopTrackingRejections();
            }
        }
    }

    private ExecutionResult evaluate(JsContext context, JsModule module, EntryPoints js, Execution execution, Control control)
            throws JsEngineException {
        // Evaluating a module answers a promise: it rejects if the module (or one it imports) throws, and settles
        // later if one awaits at the top level.
        try (JsPromiseView loading = context.evaluate(module)) {
            if (loading != null) {
                ExecutionResult early = runLoop(context, loading, js, execution, control);
                if (early != null) {
                    return early;
                }
                if (loading.isRejected()) {
                    return ExecutionResult.failed(loading.rejectionText());
                }
            }
        }
        // Without a default function, a service may still run its servers (D-123); entry.js decides.
        String jobContext = execution.context().toScriptJson().encode();
        try (JsPromiseView promise = context.invokeDefault(module, execution.input().encode(), jobContext)) {
            ExecutionResult early = runLoop(context, promise, js, execution, control);
            if (early != null) {
                return early;
            }
            JsonObject envelope = new JsonObject(promise.resultString());
            if (!envelope.getBoolean("ok")) {
                return ExecutionResult.failed(envelope.getString("error"));
            }
            // A rejection left unhandled in the turn that finished the script still fails it, as it would end
            // Node before the process exits (D-110).
            drainAll(context, control);
            js.checkRejections();
            if (control.killedWith() != null) {
                return control.killedWith();
            }
            return control.loop.uncaught() != null
                    ? ExecutionResult.failed(control.loop.uncaught())
                    : ExecutionResult.succeeded(envelope.getValue("output"));
        }
    }

    /**
     * Pumps microtasks, posted tasks and timers until the script's promise settles. Returns a result if
     * the run has to end before that, or null once the promise has settled.
     */
    private static ExecutionResult runLoop(JsContext context, JsPromiseView promise, EntryPoints js, Execution execution,
                                           Control control) throws JsEngineException {
        EventLoop loop = control.loop;
        while (true) {
            if (control.killedWith() != null) {
                return control.killedWith();
            }
            drainMicrotasks(context, promise, control);
            checkRejections(context, promise, js, control);
            if (loop.uncaught() != null) {
                return ExecutionResult.failed(loop.uncaught());
            }
            if (!promise.isPending()) {
                return null;
            }

            // As Node's loop (D-121): I/O callbacks (posted tasks), then the check phase (immediates), then timers,
            // with microtasks and the rejection check after each callback.
            boolean worked = false;
            for (int id : loop.takeNextTurn()) {
                js.fireTimer(id);
                worked = true;
                drainMicrotasks(context, promise, control);
                checkRejections(context, promise, js, control);
            }
            for (EventLoop.Task task = loop.pollTask(); task != null; task = loop.pollTask()) {
                task.run(js);
                worked = true;
                drainMicrotasks(context, promise, control);
                checkRejections(context, promise, js, control);
            }
            if (loop.immediatesPending()) {
                for (int i = js.startImmediates(); i > 0 && control.killedWith() == null && loop.uncaught() == null; i--) {
                    js.runImmediate();
                    drainMicrotasks(context, promise, control);
                    checkRejections(context, promise, js, control);
                }
                worked = true;
            }
            for (int id = loop.pollDue(); id >= 0; id = loop.pollDue()) {
                js.fireTimer(id);
                worked = true;
                drainMicrotasks(context, promise, control);
                checkRejections(context, promise, js, control);
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

    /**
     * After a turn of the loop, as Node checks after each macrotask (D-110): a rejection still without a handler ends
     * the job, or goes to the script's {@code unhandledRejection} listeners, whose own microtasks then run. Not once the
     * script's promise settled: that may be the module's own failed load, which is reported as itself.
     */
    private static void checkRejections(JsContext context, JsPromiseView promise, EntryPoints js, Control control)
            throws JsEngineException {
        if (promise.isPending() && control.killedWith() == null) {
            js.checkRejections();
            drainMicrotasks(context, promise, control);
        }
    }

    /** Runs every queued microtask, even once the script's promise settled. */
    private static void drainAll(JsContext context, Control control) throws JsEngineException {
        while (control.killedWith() == null && context.drainMicrotasks()) {
            // Each call runs the queue; it answers whether more work remains.
        }
    }

    private static void drainMicrotasks(JsContext context, JsPromiseView promise, Control control) throws JsEngineException {
        boolean more;
        do {
            more = context.drainMicrotasks();
        } while (more && promise.isPending() && control.killedWith() == null);
    }
}
