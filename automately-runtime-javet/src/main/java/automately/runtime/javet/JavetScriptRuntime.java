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
    static final List<String> PRELUDE_FILES = List.of("prelude.js", "console.js", "timers.js", "listeners.js", "kv.js", "bus.js", "jobs.js", "secrets.js",
            "encoding.js", "url.js", "globals.js", "streams.js", "crypto.js", "subtle.js", "zlib.js", "fetch.js", "files.js", "fs.js", "serve.js", "context.js", "entry.js");

    /**
     * Runs before the job's module: the prelude's files in one closure. It captures the host object, removes it from the
     * global scope, installs the APIs frozen, and returns the functions the event loop calls ({@link ScriptEntryPoints}).
     * None of those become visible to the script.
     */
    static final String PRELUDE = loadPrelude();

    /** What {@code fs} exports by name, for {@code import { readFile } from 'fs'}. */
    private static final String FS_NAMES = "readFile, writeFile, appendFile, mkdir, readdir, stat, lstat, access, rename, copyFile, rm, rmdir, unlink, "
            + "readFileSync, writeFileSync, appendFileSync, mkdirSync, readdirSync, statSync, lstatSync, accessSync, existsSync, renameSync, "
            + "copyFileSync, rmSync, rmdirSync, unlinkSync, open, read, write, close, openSync, readSync, writeSync, fstatSync, ftruncateSync, "
            + "closeSync, lock, promises, constants";
    private static final String FS_PROMISES_NAMES = "readFile, writeFile, appendFile, mkdir, readdir, stat, lstat, access, rename, copyFile, rm, rmdir, unlink, open";

    /**
     * Modules the isolate provides itself, by every name they're imported as (D-079, D-090), mapped to one module name
     * each, so {@code events} and {@code node:events} are the same module and {@code EventEmitter} the same class. The
     * names are {@link automately.core.packages.Imports#BUILT_INS}.
     */
    static final java.util.Map<String, String> BUILT_IN_MODULES = java.util.Map.ofEntries(
            java.util.Map.entry("fs", "node:fs"), java.util.Map.entry("node:fs", "node:fs"), java.util.Map.entry("automately:fs", "node:fs"),
            java.util.Map.entry("fs/promises", "node:fs/promises"), java.util.Map.entry("node:fs/promises", "node:fs/promises"),
            java.util.Map.entry("path", "node:path"), java.util.Map.entry("node:path", "node:path"),
            java.util.Map.entry("path/posix", "node:path"), java.util.Map.entry("node:path/posix", "node:path"),
            java.util.Map.entry("events", "node:events"), java.util.Map.entry("node:events", "node:events"),
            java.util.Map.entry("buffer", "node:buffer"), java.util.Map.entry("node:buffer", "node:buffer"),
            java.util.Map.entry("url", "node:url"), java.util.Map.entry("node:url", "node:url"),
            java.util.Map.entry("util", "node:util"), java.util.Map.entry("node:util", "node:util"),
            java.util.Map.entry("process", "node:process"), java.util.Map.entry("node:process", "node:process"),
            java.util.Map.entry("crypto", "node:crypto"), java.util.Map.entry("node:crypto", "node:crypto"),
            java.util.Map.entry("stream", "node:stream"), java.util.Map.entry("node:stream", "node:stream"),
            java.util.Map.entry("stream/promises", "node:stream/promises"), java.util.Map.entry("node:stream/promises", "node:stream/promises"),
            java.util.Map.entry("string_decoder", "node:string_decoder"), java.util.Map.entry("node:string_decoder", "node:string_decoder"),
            java.util.Map.entry("zlib", "node:zlib"), java.util.Map.entry("node:zlib", "node:zlib"),
            java.util.Map.entry("assert", "node:assert"), java.util.Map.entry("node:assert", "node:assert"),
            java.util.Map.entry("assert/strict", "node:assert/strict"), java.util.Map.entry("node:assert/strict", "node:assert/strict"),
            java.util.Map.entry("querystring", "node:querystring"), java.util.Map.entry("node:querystring", "node:querystring"),
            java.util.Map.entry("timers", "node:timers"), java.util.Map.entry("node:timers", "node:timers"),
            java.util.Map.entry("timers/promises", "node:timers/promises"), java.util.Map.entry("node:timers/promises", "node:timers/promises"),
            java.util.Map.entry("os", "node:os"), java.util.Map.entry("node:os", "node:os"),
            java.util.Map.entry("http", "node:http"), java.util.Map.entry("node:http", "node:http"),
            java.util.Map.entry("https", "node:https"), java.util.Map.entry("node:https", "node:https"),
            java.util.Map.entry("net", "node:net"), java.util.Map.entry("node:net", "node:net"),
            java.util.Map.entry("tls", "node:tls"), java.util.Map.entry("node:tls", "node:tls"),
            java.util.Map.entry("http2", "node:http2"), java.util.Map.entry("node:http2", "node:http2"),
            java.util.Map.entry("tty", "node:tty"), java.util.Map.entry("node:tty", "node:tty"),
            // The host APIs as modules (D-091), so packages can import them rather than rely on globals.
            java.util.Map.entry("automately:kv", "automately:kv"), java.util.Map.entry("automately:bus", "automately:bus"),
            java.util.Map.entry("automately:jobs", "automately:jobs"), java.util.Map.entry("automately:files", "automately:files"),
            java.util.Map.entry("automately:secrets", "automately:secrets"), java.util.Map.entry("automately:fetch", "automately:fetch"),
            java.util.Map.entry("automately:log", "automately:log"));

    /** Each built-in module's source: fs is the global plus its streams; the others are stand-ins for Node's (js/node/). */
    static final java.util.Map<String, String> BUILT_IN_SOURCES = java.util.Map.ofEntries(
            // The global fs as a module, plus createReadStream and createWriteStream (D-109).
            java.util.Map.entry("node:fs", resource("node/fs.js").replace("/* automately:fs-names */", FS_NAMES)),
            java.util.Map.entry("node:fs/promises", "export default globalThis.fs.promises; export const { " + FS_PROMISES_NAMES
                    + " } = globalThis.fs.promises;"),
            java.util.Map.entry("node:path", resource("node/path.js")),
            java.util.Map.entry("node:events", resource("node/events.js")),
            java.util.Map.entry("node:buffer", resource("node/buffer.js")),
            java.util.Map.entry("node:url", resource("node/url.js")),
            java.util.Map.entry("node:util", resource("node/util.js")),
            java.util.Map.entry("node:process", resource("node/process.js")),
            java.util.Map.entry("node:crypto", resource("node/crypto.js")),
            // Vendored readable-stream and string_decoder (D-100); rebuilt by scripts/vendor-stream.
            java.util.Map.entry("node:stream", resource("node/stream.js")),
            java.util.Map.entry("node:stream/promises", resource("node/stream_promises.js")),
            java.util.Map.entry("node:string_decoder", resource("node/string_decoder.js")),
            // Step 4 of D-095 (D-103).
            java.util.Map.entry("node:zlib", resource("node/zlib.js")),
            java.util.Map.entry("node:assert", resource("node/assert.js")),
            java.util.Map.entry("node:assert/strict", resource("node/assert_strict.js")),
            java.util.Map.entry("node:querystring", resource("node/querystring.js")),
            java.util.Map.entry("node:timers", resource("node/timers.js")),
            java.util.Map.entry("node:timers/promises", resource("node/timers_promises.js")),
            java.util.Map.entry("node:os", resource("node/os.js")),
            // Phase B (D-109): the HTTP client on a host request through the egress gate.
            java.util.Map.entry("node:http", resource("node/http.js")),
            java.util.Map.entry("node:https", resource("node/https.js")),
            // Pure parts only: connecting throws (net and tls until phase B5; http2 and tty always).
            java.util.Map.entry("node:net", resource("node/net.js")),
            java.util.Map.entry("node:tls", resource("node/tls.js")),
            java.util.Map.entry("node:http2", resource("node/http2.js")),
            java.util.Map.entry("node:tty", resource("node/tty.js")),
            java.util.Map.entry("automately:kv", hostModule("kv")),
            java.util.Map.entry("automately:bus", hostModule("bus")),
            java.util.Map.entry("automately:jobs", hostModule("jobs")),
            java.util.Map.entry("automately:files", hostModule("files")),
            java.util.Map.entry("automately:secrets", hostModule("secrets")),
            java.util.Map.entry("automately:fetch", hostModule("fetch")),
            java.util.Map.entry("automately:log", hostModule("log")));

    /**
     * The built-ins with this job's values in them: {@code process.env} in the process stand-in (D-095), as plain JSON
     * so nothing runs, and the isolate's heap limit as {@code os.totalmem()} (D-103).
     */
    static java.util.Map<String, String> forJob(java.util.Map<String, String> env, long heapBytes) {
        java.util.Map<String, String> sources = new java.util.HashMap<>(BUILT_IN_SOURCES);
        String heapMarker = "/* automately:heap */ 0";
        String os = sources.get("node:os");
        if (!os.contains(heapMarker)) {
            throw new IllegalStateException("os.js has lost its heap marker");
        }
        sources.put("node:os", os.replace(heapMarker, Long.toString(heapBytes)));
        String marker = "/* automately:env */ { NODE_ENV: 'production' }";
        String process = sources.get("node:process");
        if (!process.contains(marker)) {
            throw new IllegalStateException("process.js has lost its env marker");
        }
        sources.put("node:process", process.replace(marker, new JsonObject(new java.util.TreeMap<>(env)).encode()));
        return sources;
    }

    /** {@code import kv from 'automately:kv'} or {@code import { kv } from 'automately:kv'}: the same object as the global. */
    private static String hostModule(String name) {
        return "const api = globalThis." + name + "; export default api; export { api as " + name + " };";
    }

    private static String resource(String file) {
        try (java.io.InputStream in = JavetScriptRuntime.class.getResourceAsStream("js/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing js/" + file);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

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
    /** The heap limit each isolate gets, as os.totalmem() reports it. */
    private final long heapBytes;
    /** V8's compiled prelude, from the first isolate that ran it (D-106); null until then. */
    private volatile byte[] preludeCache;
    /** V8's compiled built-in modules, by module name (D-106). */
    private final java.util.Map<String, byte[]> moduleCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final WorkerExecutor isolates;

    /**
     * @param maxHeapMb      per-isolate heap limit. V8 applies this flag process-wide and only before the
     *                       first isolate is created, so the first runtime constructed in a JVM decides it.
     * @param isolateThreads how many isolates can run at once; use the node's capacity
     */
    public JavetScriptRuntime(Vertx vertx, int maxHeapMb, int isolateThreads) {
        this.vertx = vertx;
        this.heapBytes = maxHeapMb * 1024L * 1024L;
        configureHeapLimit(maxHeapMb);
        // A service's isolate holds its thread for as long as it runs, so no execute-time warnings.
        this.isolates = vertx.createSharedWorkerExecutor("automately-isolates", isolateThreads, 365, TimeUnit.DAYS);
    }

    private static synchronized void configureHeapLimit(int maxHeapMb) {
        V8Flags flags = V8RuntimeOptions.V8_FLAGS;
        if (!flags.isSealed()) {
            flags.setMaxHeapSize(maxHeapMb);
            flags.setIcuDataFile(icuDataFile());
        }
    }

    /** ICU's data, which V8's i18n build needs for Intl and Unicode regular expressions (D-093): the runtime's version. */
    static final String ICU_DATA = "icudt78l.dat";

    /**
     * Writes the ICU data shipped with the runtime to a file V8 can map, once per process. V8 needs a path, and the
     * data is in the jar, so it goes to a temporary file that's removed when the JVM exits.
     */
    private static String icuDataFile() {
        try (java.io.InputStream in = JavetScriptRuntime.class.getResourceAsStream("icu/" + ICU_DATA)) {
            if (in == null) {
                throw new IllegalStateException("Missing ICU data (icu/" + ICU_DATA + "): the build downloads it; run ./mvnw package");
            }
            java.nio.file.Path file = java.nio.file.Files.createTempFile("automately-" + ICU_DATA.replace(".dat", "-"), ".dat");
            file.toFile().deleteOnExit();
            java.nio.file.Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return file.toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
        try (V8Runtime v8 = V8Host.getV8I18nInstance().createV8Runtime()) {
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
                bindApi(v8, hostObject, "secrets", new SecretsBinding(bridge, host.secrets()), callbacks);
                bindApi(v8, hostObject, "fs", new FsBinding(bridge, host.fs()), callbacks);
                bindApi(v8, hostObject, "crypto", new CryptoBinding(bridge, host.crypto()), callbacks);
                bindApi(v8, hostObject, "zlib", new ZlibBinding(bridge, host.zlib()), callbacks);
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

    /**
     * Runs the prelude, from V8's code cache once there is one (D-106). Parsing the prelude is most of what an isolate
     * costs to start, and it's the same source every time; the first isolate compiles it, and its compiled code, taken
     * after it ran so the functions it used are in it, is handed to every later one. V8 checks the cache against the
     * source, its flags and its version, and compiles afresh if anything differs. The cache only ever comes from this
     * process, from our own prelude.
     */
    private V8ValueObject runPrelude(V8Runtime v8) throws JavetException {
        byte[] cache = preludeCache;
        try (com.caoccao.javet.values.reference.V8Script script = (cache == null ? v8.getExecutor(PRELUDE) : v8.getExecutor(PRELUDE, cache))
                .setResourceName("automately:prelude").compileV8Script()) {
            V8ValueObject prelude = script.execute(true);
            if (cache == null) {
                preludeCache = script.getCachedData();
            }
            return prelude;
        }
    }

    private ExecutionResult invokeModule(V8Runtime v8, Execution execution, Control control) throws JavetException {
        // Built-ins (D-079) and the job's packages (D-089), all already in memory.
        ModuleResolver resolver = new ModuleResolver(execution.packages(), BUILT_IN_MODULES, forJob(execution.env(), heapBytes), moduleCache);
        v8.setV8ModuleResolver(resolver);
        try (V8ValueObject prelude = runPrelude(v8);
             ScriptEntryPoints js = new ScriptEntryPoints(v8, prelude);
             V8Module module = v8.getExecutor(automately.core.packages.Imports.rewrite(execution.script(), ModuleResolver.JOB_URL))
                     .setResourceName(ModuleResolver.JOB).compileV8Module()) {
            defineProcess(v8, resolver);
            v8.setPromiseRejectCallback((event, promise, reason) -> {
                try {
                    js.rejection(event, promise, reason);
                } catch (JavetException e) {
                    control.loop.reportUncaught("Could not track a promise rejection: " + e.getMessage());
                }
            });
            try {
                return evaluate(v8, module, js, execution, control);
            } finally {
                // The entry points close with this block; nothing after it, such as the bridge closing, is the script's.
                v8.setPromiseRejectCallback((event, promise, reason) -> { });
            }
        }
    }

    private ExecutionResult evaluate(V8Runtime v8, V8Module module, ScriptEntryPoints js, Execution execution, Control control)
            throws JavetException {
        // Evaluating a module answers a promise: it rejects if the module (or one it imports) throws, and settles
        // later if one awaits at the top level.
        try (V8Value evaluated = module.execute(true)) {
            if (evaluated instanceof V8ValuePromise loading) {
                ExecutionResult early = runLoop(v8, loading, js, execution, control);
                if (early != null) {
                    return early;
                }
                if (loading.isRejected()) {
                    try (V8Value error = loading.getResult()) {
                        return ExecutionResult.failed(describe(error));
                    }
                }
            }
        }
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
                if (!envelope.getBoolean("ok")) {
                    return ExecutionResult.failed(envelope.getString("error"));
                }
                // A rejection left unhandled in the turn that finished the script still fails it, as it would end
                // Node before the process exits (D-110).
                drainAll(v8, control);
                js.checkRejections();
                if (control.killedWith() != null) {
                    return control.killedWith();
                }
                return control.loop.uncaught() != null
                        ? ExecutionResult.failed(control.loop.uncaught())
                        : ExecutionResult.succeeded(envelope.getValue("output"));
            }
        }
    }

    /**
     * {@code process} as a global (D-096), as in Node: the same object {@code import process from 'node:process'} gives,
     * with the job's declared env. Writable, like Node's, since browser shims assign to it.
     */
    private static void defineProcess(V8Runtime v8, ModuleResolver resolver) throws JavetException {
        com.caoccao.javet.values.reference.IV8Module module = resolver.resolve(v8, "node:process", null);
        try (V8Value evaluated = module.execute(true);
             V8ValueObject namespace = (V8ValueObject) module.getNamespace();
             V8Value process = namespace.get("default")) {
            v8.getGlobalObject().set("process", process);
        }
    }

    /** An error as the script would print it: its stack if it has one (which names the module and line), else its text. */
    private static String describe(V8Value error) throws JavetException {
        if (error instanceof com.caoccao.javet.values.reference.V8ValueError e && e.getStack() != null) {
            return e.getStack();
        }
        return error == null ? "The script failed to load" : error.toString();
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
            checkRejections(v8, promise, js, control);
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
                checkRejections(v8, promise, js, control);
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
    private static void checkRejections(V8Runtime v8, V8ValuePromise promise, ScriptEntryPoints js, Control control)
            throws JavetException {
        if (promise.isPending() && control.killedWith() == null) {
            js.checkRejections();
            drainMicrotasks(v8, promise, control);
        }
    }

    /** Runs every queued microtask, even once the script's promise settled. */
    private static void drainAll(V8Runtime v8, Control control) throws JavetException {
        while (control.killedWith() == null && v8.await()) {
            // Each call runs the queue; it answers whether more work remains.
        }
    }

    private static void drainMicrotasks(V8Runtime v8, V8ValuePromise promise, Control control) throws JavetException {
        boolean more;
        do {
            more = v8.await();
        } while (more && promise.isPending() && control.killedWith() == null);
    }
}
