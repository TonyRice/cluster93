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
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs each attempt in its own V8 isolate. A script is an ES module whose default export receives the
 * job input and the job context, and returns (or resolves to) a JSON value:
 *
 * <pre>{@code export default async (input, ctx) => ({ hello: input.name, attempt: ctx.attempt })}</pre>
 *
 * <p>The isolate is created for the attempt and closed afterwards, so nothing a script does can be seen by
 * the next one. The only host objects are {@code console} and {@code ctx} (docs/scripting/job-context.md).
 * Three things terminate a script: a wall-clock timeout fired from the event loop, a per-isolate heap
 * limit enforced through V8's near-heap-limit callback (without it, an out-of-memory isolate aborts the
 * whole JVM), and {@link RunningExecution#cancel}.
 */
public final class JavetScriptRuntime implements ScriptRuntime {

    // Runs before the job's module. It captures the host object, removes it from the global scope and
    // returns the invoke wrapper, which never becomes visible to the script.
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

              class SupersededError extends Error { constructor(message) { super(message); this.name = 'SupersededError'; } }
              class StateLimitError extends Error { constructor(message) { super(message); this.name = 'StateLimitError'; } }
              for (const [name, value] of [['SupersededError', SupersededError], ['StateLimitError', StateLimitError]]) {
                Object.freeze(value.prototype);
                Object.defineProperty(globalThis, name, { value: Object.freeze(value), writable: false, configurable: false });
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

              return async (fn, inputJson, contextJson) => {
                try {
                  const output = await fn(JSON.parse(inputJson), makeContext(JSON.parse(contextJson)));
                  return JSON.stringify({ ok: true, output: output === undefined ? null : output });
                } catch (e) {
                  return JSON.stringify({ ok: false, error: String(e && e.stack ? e.stack : e) });
                }
              };
            })()
            """;

    /** Extra heap granted once the limit is hit, so V8 can unwind the terminated script instead of aborting. */
    private static final long HEAP_HEADROOM_BYTES = 32L * 1024 * 1024;

    private final Vertx vertx;

    /**
     * @param maxHeapMb per-isolate heap limit. V8 applies this flag process-wide and only before the
     *                  first isolate is created, so the first runtime constructed in a JVM decides it.
     */
    public JavetScriptRuntime(Vertx vertx, int maxHeapMb) {
        this.vertx = vertx;
        configureHeapLimit(maxHeapMb);
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
        Future<ExecutionResult> result = vertx.executeBlocking(() -> run(execution, logs, state, control), false);
        return new RunningExecution() {
            @Override
            public Future<ExecutionResult> result() {
                return result;
            }

            @Override
            public void cancel(String reason) {
                control.kill(ExecutionResult.failed("Cancelled: " + reason));
            }
        };
    }

    /**
     * Holds the first reason a script was stopped, and the isolate while it is alive. Termination and
     * closing are synchronised so an isolate is never terminated after it was closed.
     */
    private static final class Control {

        private final AtomicReference<ExecutionResult> killed = new AtomicReference<>();
        private V8Runtime runtime;

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
        }

        ExecutionResult killedWith() {
            return killed.get();
        }
    }

    private ExecutionResult run(Execution execution, LogSink logs, StateSink state, Control control) throws JavetException {
        if (control.killedWith() != null) {
            return control.killedWith();
        }
        try (V8Runtime v8 = V8Host.getV8Instance().createV8Runtime()) {
            control.attach(v8);
            long timer = vertx.setTimer(execution.timeout().toMillis(),
                    id -> control.kill(ExecutionResult.timedOut("Terminated after " + execution.timeout().toMillis() + " ms")));
            v8.setNearHeapLimitCallback((currentLimit, initialLimit) -> {
                control.kill(ExecutionResult.failed("Terminated: memory limit exceeded"));
                return currentLimit + HEAP_HEADROOM_BYTES;
            });
            try {
                ExecutionResult result = invoke(v8, execution, logs, state, control);
                return control.killedWith() != null ? control.killedWith() : result;
            } catch (JavetException e) {
                return control.killedWith() != null ? control.killedWith() : ExecutionResult.failed(e.getMessage());
            } finally {
                vertx.cancelTimer(timer);
                control.detach();
            }
        }
    }

    private ExecutionResult invoke(V8Runtime v8, Execution execution, LogSink logs, StateSink state, Control control) throws JavetException {
        List<JavetCallbackContext> callbacks = new ArrayList<>();
        try (V8ValueObject host = v8.createV8ValueObject()) {
            callbacks.addAll(host.bind(new HostConsole(logs)));
            callbacks.addAll(host.bind(new HostState(state)));
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
        try (V8ValueFunction invoker = v8.getExecutor(PRELUDE).setResourceName("automately:prelude").execute();
             V8Module module = v8.getExecutor(execution.script()).setResourceName("job.mjs").compileV8Module()) {
            module.executeVoid();
            try (V8ValueObject namespace = (V8ValueObject) module.getNamespace();
                 V8Value entry = namespace.get("default")) {
                if (!(entry instanceof V8ValueFunction fn)) {
                    return ExecutionResult.failed("The script must export a default function");
                }
                String context = execution.context().toScriptJson().encode();
                try (V8ValuePromise promise = invoker.call(null, fn, execution.input().encode(), context)) {
                    while (promise.isPending()) {
                        if (control.killedWith() != null) {
                            return control.killedWith();
                        }
                        if (!v8.await() && promise.isPending()) {
                            return ExecutionResult.failed("The script returned a promise that never settles");
                        }
                    }
                    JsonObject envelope = new JsonObject(promise.getResultString());
                    return envelope.getBoolean("ok")
                            ? ExecutionResult.succeeded(envelope.getValue("output"))
                            : ExecutionResult.failed(envelope.getString("error"));
                }
            }
        }
    }
}
