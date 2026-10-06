package automately.runtime.javet;

import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
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

    // Runs before the job's module. It captures the host object, removes it from the global scope, installs
    // console and timers, and returns the functions the event loop calls. None of those become visible to
    // the script.
    private static final String PRELUDE = """
            (() => {
              const host = globalThis.__automately;
              delete globalThis.__automately;
              const show = (value) => {
                if (typeof value === 'string') return value;
                try { return JSON.stringify(value); } catch { return String(value); }
              };
              const write = (level) => (...args) => host.log(level, args.map(show).join(' '));
              globalThis.console = Object.freeze({
                log: write('info'), info: write('info'), warn: write('warn'), error: write('error'),
              });

              const timers = new Map();
              let nextTimerId = 1;
              const schedule = (fn, delay, args, repeat) => {
                if (typeof fn !== 'function') throw new TypeError('A timer callback must be a function');
                if (timers.size >= 10000) throw new RangeError('Too many pending timers (limit 10000)');
                const id = nextTimerId++;
                const ms = Math.max(1, Number(delay) || 0);
                timers.set(id, { fn, args, repeat, ms });
                host.timer(id, ms);
                return id;
              };
              const clear = (id) => { if (timers.delete(id)) host.clearTimer(id); };
              const define = (name, value) => Object.defineProperty(globalThis, name, { value, writable: false, configurable: false });
              define('setTimeout', (fn, delay, ...args) => schedule(fn, delay, args, false));
              define('setInterval', (fn, delay, ...args) => schedule(fn, delay, args, true));
              define('clearTimeout', clear);
              define('clearInterval', clear);
              const fire = (id) => {
                const timer = timers.get(id);
                if (!timer) return;
                if (timer.repeat) host.timer(id, timer.ms); else timers.delete(id);
                try {
                  const result = timer.fn(...timer.args);
                  if (result && typeof result.then === 'function') {
                    result.then(undefined, (e) => host.uncaught(`Uncaught error in timer callback: ${String(e && e.stack ? e.stack : e)}`));
                  }
                } catch (e) {
                  host.uncaught(`Uncaught error in timer callback: ${String(e && e.stack ? e.stack : e)}`);
                }
              };

              class SupersededError extends Error { constructor(message) { super(message); this.name = 'SupersededError'; } }
              class StateLimitError extends Error { constructor(message) { super(message); this.name = 'StateLimitError'; } }
              for (const [name, value] of [['SupersededError', SupersededError], ['StateLimitError', StateLimitError]]) {
                Object.freeze(value.prototype);
                define(name, Object.freeze(value));
              }
              const store = (method, ...args) => {
                const reply = JSON.parse(host[method](...args));
                if (reply.ok) return;
                if (reply.kind === 'SUPERSEDED') throw new SupersededError(reply.message);
                if (reply.kind === 'LIMIT') throw new StateLimitError(reply.message);
                throw new Error(reply.message);
              };
              const toJson = (value, what) => {
                const json = JSON.stringify(value === undefined ? null : value);
                if (json === undefined) throw new TypeError(`${what} must be JSON-serialisable`);
                return json;
              };

              let resolveStop;
              const stopRequested = new Promise((resolve) => { resolveStop = resolve; });
              const requestStop = (reason) => resolveStop({ reason });

              const deepFreeze = (value) => {
                if (value && typeof value === 'object') { Object.values(value).forEach(deepFreeze); Object.freeze(value); }
                return value;
              };

              const makeContext = (c) => {
                const done = new Map(Object.entries(c.steps));
                const seen = new Set();
                const resuming = c.attempt > 1 && c.policy === 'resume';
                const stepKey = (name) => `${c.jobId}:${name}`;
                return Object.freeze({
                  jobId: c.jobId,
                  attempt: c.attempt,
                  policy: c.policy,
                  isRetry: c.attempt > 1 && c.policy === 'retry',
                  isResume: resuming,
                  previous: c.previous ? Object.freeze(c.previous) : null,
                  checkpoint: c.checkpoint ?? null,
                  completedSteps: Object.freeze([...done.keys()]),
                  service: c.service ? deepFreeze(c.service) : null,
                  get stopRequested() { host.watchStop(); return stopRequested; },
                  sleep: (ms) => new Promise((resolve) => schedule(resolve, ms, [], false)),
                  stepKey,
                  async saveCheckpoint(value) {
                    store('saveCheckpoint', toJson(value, 'A checkpoint'));
                  },
                  async step(name, fn) {
                    if (typeof name !== 'string' || name.length === 0) throw new TypeError('A step name must be a non-empty string');
                    if (typeof fn !== 'function') throw new TypeError(`Step "${name}" needs a function`);
                    if (seen.has(name)) throw new Error(`Step "${name}" was already used in this run; step names must be unique`);
                    seen.add(name);
                    if (done.has(name)) return JSON.parse(JSON.stringify(done.get(name)));
                    const result = await fn(Object.freeze({ key: stepKey(name), attempt: c.attempt }));
                    const json = toJson(result, `The result of step "${name}"`);
                    store('completeStep', name, json);
                    return JSON.parse(json);
                  },
                });
              };

              const invoke = async (fn, inputJson, contextJson) => {
                try {
                  const output = await fn(JSON.parse(inputJson), makeContext(JSON.parse(contextJson)));
                  return JSON.stringify({ ok: true, output: output === undefined ? null : output });
                } catch (e) {
                  return JSON.stringify({ ok: false, error: String(e && e.stack ? e.stack : e) });
                }
              };
              return { invoke, fire, requestStop };
            })()
            """;

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
    public RunningExecution execute(Execution execution, LogSink logs, StateSink state) {
        Control control = new Control();
        Future<ExecutionResult> result = isolates.executeBlocking(() -> run(execution, logs, state, control), false);
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
            loop.post(StopRequest.INSTANCE);
            return true;
        }

        synchronized String stopReason() {
            return stopReason;
        }

        ExecutionResult killedWith() {
            return killed.get();
        }
    }

    /** Marker task: the loop resolves {@code ctx.stopRequested} when it sees this. */
    private enum StopRequest implements Runnable {
        INSTANCE;

        @Override
        public void run() {
        }
    }

    private ExecutionResult run(Execution execution, LogSink logs, StateSink state, Control control) throws JavetException {
        if (control.killedWith() != null) {
            return control.killedWith();
        }
        try (V8Runtime v8 = V8Host.getV8Instance().createV8Runtime()) {
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
                ExecutionResult result = invoke(v8, execution, logs, state, control);
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

    private ExecutionResult invoke(V8Runtime v8, Execution execution, LogSink logs, StateSink state, Control control) throws JavetException {
        List<JavetCallbackContext> callbacks = new ArrayList<>();
        try (V8ValueObject host = v8.createV8ValueObject()) {
            callbacks.addAll(host.bind(new HostConsole(logs)));
            callbacks.addAll(host.bind(new HostState(state)));
            callbacks.addAll(host.bind(new HostTimers(control.loop)));
            v8.getGlobalObject().set("__automately", host);
        }
        try {
            return invokeModule(v8, execution, control);
        } finally {
            // The prelude's closures still reference these; release them only once the script is done.
            callbacks.forEach(callback -> v8.removeCallbackContext(callback.getHandle()));
        }
    }

    private ExecutionResult invokeModule(V8Runtime v8, Execution execution, Control control) throws JavetException {
        try (V8ValueObject prelude = v8.getExecutor(PRELUDE).setResourceName("automately:prelude").execute();
             V8ValueFunction invoker = prelude.get("invoke");
             V8ValueFunction fire = prelude.get("fire");
             V8ValueFunction requestStop = prelude.get("requestStop");
             V8Module module = v8.getExecutor(execution.script()).setResourceName("job.mjs").compileV8Module()) {
            module.executeVoid();
            try (V8ValueObject namespace = (V8ValueObject) module.getNamespace();
                 V8Value entry = namespace.get("default")) {
                if (!(entry instanceof V8ValueFunction fn)) {
                    return ExecutionResult.failed("The script must export a default function");
                }
                String context = execution.context().toScriptJson().encode();
                try (V8ValuePromise promise = invoker.call(null, fn, execution.input().encode(), context)) {
                    ExecutionResult early = runLoop(v8, promise, fire, requestStop, execution, control);
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
    private static ExecutionResult runLoop(V8Runtime v8, V8ValuePromise promise, V8ValueFunction fire, V8ValueFunction requestStop,
                                           Execution execution, Control control) throws JavetException {
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
            for (Runnable task = loop.pollTask(); task != null; task = loop.pollTask()) {
                worked |= runTask(task, requestStop, control);
            }
            for (int id = loop.pollDue(); id >= 0; id = loop.pollDue()) {
                fire.callVoid(null, id);
                worked = true;
                drainMicrotasks(v8, promise, control);
            }
            if (worked) {
                continue;
            }

            long wait = loop.nanosUntilNextTimer();
            // Nothing scheduled: a job fails fast, unless it is waiting for a stop (which a cancel can deliver,
            // and its timeout still bounds). A service always waits.
            if (wait < 0 && !execution.longRunning() && !loop.isStopWatched()) {
                return ExecutionResult.failed("The script returned a promise that never settles");
            }
            Runnable task;
            try {
                task = loop.awaitTask(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ExecutionResult.cancelled("Cancelled: the isolate thread was interrupted");
            }
            if (task != null) {
                runTask(task, requestStop, control);
            }
        }
    }

    private static boolean runTask(Runnable task, V8ValueFunction requestStop, Control control) throws JavetException {
        if (task == StopRequest.INSTANCE) {
            requestStop.callVoid(null, control.stopReason());
            return true;
        }
        task.run();
        return false;
    }

    private static void drainMicrotasks(V8Runtime v8, V8ValuePromise promise, Control control) throws JavetException {
        boolean more;
        do {
            more = v8.await();
        } while (more && promise.isPending() && control.killedWith() == null);
    }
}
