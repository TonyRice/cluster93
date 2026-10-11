package automately.runtime.js;

import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * What every engine runs (D-126): the prelude, assembled from js/*.js, and the built-in modules by every name they're
 * imported as, with their sources. Resources live under {@value #ROOT}.
 */
public final class Builtins {

    /** Where the JavaScript lives on the classpath. */
    public static final String ROOT = "/automately/runtime/js/";

    private Builtins() {
    }

    /** The prelude's files (src/main/resources/automately/runtime/javet/js), in order; each sees what the earlier ones define. */
    public static final List<String> PRELUDE_FILES = List.of("prelude.js", "console.js", "timers.js", "listeners.js", "kv.js", "bus.js", "jobs.js", "secrets.js",
            "encoding.js", "url.js", "globals.js", "streams.js", "crypto.js", "subtle.js", "zlib.js", "fetch.js", "files.js", "fs.js", "serve.js", "context.js", "entry.js");

    /**
     * Runs before the job's module: the prelude's files in one closure. It captures the host object, removes it from the
     * global scope, installs the APIs frozen, and returns the functions the event loop calls ({@link EntryPoints}).
     * None of those become visible to the script.
     */
    public static final String PRELUDE = loadPrelude();

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
    public static final java.util.Map<String, String> BUILT_IN_MODULES = java.util.Map.ofEntries(
            java.util.Map.entry("fs", "node:fs"), java.util.Map.entry("node:fs", "node:fs"), java.util.Map.entry("automately:fs", "node:fs"),
            java.util.Map.entry("fs/promises", "node:fs/promises"), java.util.Map.entry("node:fs/promises", "node:fs/promises"),
            java.util.Map.entry("path", "node:path"), java.util.Map.entry("node:path", "node:path"),
            java.util.Map.entry("path/posix", "node:path"), java.util.Map.entry("node:path/posix", "node:path"),
            java.util.Map.entry("events", "node:events"), java.util.Map.entry("node:events", "node:events"),
            java.util.Map.entry("buffer", "node:buffer"), java.util.Map.entry("node:buffer", "node:buffer"),
            java.util.Map.entry("url", "node:url"), java.util.Map.entry("node:url", "node:url"),
            java.util.Map.entry("util", "node:util"), java.util.Map.entry("node:util", "node:util"),
            java.util.Map.entry("util/types", "node:util/types"), java.util.Map.entry("node:util/types", "node:util/types"),
            java.util.Map.entry("child_process", "node:child_process"), java.util.Map.entry("node:child_process", "node:child_process"),
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
            java.util.Map.entry("async_hooks", "node:async_hooks"), java.util.Map.entry("node:async_hooks", "node:async_hooks"),
            java.util.Map.entry("perf_hooks", "node:perf_hooks"), java.util.Map.entry("node:perf_hooks", "node:perf_hooks"),
            java.util.Map.entry("worker_threads", "node:worker_threads"), java.util.Map.entry("node:worker_threads", "node:worker_threads"),
            java.util.Map.entry("module", "node:module"), java.util.Map.entry("node:module", "node:module"),
            java.util.Map.entry("v8", "node:v8"), java.util.Map.entry("node:v8", "node:v8"),
            java.util.Map.entry("stream/web", "node:stream/web"), java.util.Map.entry("node:stream/web", "node:stream/web"),
            java.util.Map.entry("timers/promises", "node:timers/promises"), java.util.Map.entry("node:timers/promises", "node:timers/promises"),
            java.util.Map.entry("os", "node:os"), java.util.Map.entry("node:os", "node:os"),
            java.util.Map.entry("http", "node:http"), java.util.Map.entry("node:http", "node:http"),
            java.util.Map.entry("https", "node:https"), java.util.Map.entry("node:https", "node:https"),
            java.util.Map.entry("net", "node:net"), java.util.Map.entry("node:net", "node:net"),
            java.util.Map.entry("tls", "node:tls"), java.util.Map.entry("node:tls", "node:tls"),
            java.util.Map.entry("http2", "node:http2"), java.util.Map.entry("node:http2", "node:http2"),
            java.util.Map.entry("tty", "node:tty"), java.util.Map.entry("node:tty", "node:tty"),
            java.util.Map.entry("diagnostics_channel", "node:diagnostics_channel"),
            java.util.Map.entry("node:diagnostics_channel", "node:diagnostics_channel"),
            java.util.Map.entry("dns", "node:dns"), java.util.Map.entry("node:dns", "node:dns"),
            java.util.Map.entry("dns/promises", "node:dns/promises"), java.util.Map.entry("node:dns/promises", "node:dns/promises"),
            // The host APIs as modules (D-091), so packages can import them rather than rely on globals.
            java.util.Map.entry("automately:kv", "automately:kv"), java.util.Map.entry("automately:bus", "automately:bus"),
            java.util.Map.entry("automately:jobs", "automately:jobs"), java.util.Map.entry("automately:files", "automately:files"),
            java.util.Map.entry("automately:secrets", "automately:secrets"), java.util.Map.entry("automately:fetch", "automately:fetch"),
            java.util.Map.entry("automately:log", "automately:log"));

    /** Each built-in module's source: fs is the global plus its streams; the others are stand-ins for Node's (js/node/). */
    public static final java.util.Map<String, String> BUILT_IN_SOURCES = java.util.Map.ofEntries(
            // The global fs as a module, plus createReadStream and createWriteStream (D-109).
            java.util.Map.entry("node:fs", resource("node/fs.js").replace("/* automately:fs-names */", FS_NAMES)),
            java.util.Map.entry("node:fs/promises", "export default globalThis.fs.promises; export const { " + FS_PROMISES_NAMES
                    + " } = globalThis.fs.promises;"),
            java.util.Map.entry("node:path", resource("node/path.js")),
            java.util.Map.entry("node:events", resource("node/events.js")),
            java.util.Map.entry("node:buffer", resource("node/buffer.js")),
            java.util.Map.entry("node:url", resource("node/url.js")),
            java.util.Map.entry("node:util", resource("node/util.js")),
            java.util.Map.entry("node:util/types", "import { types } from 'node:util'; export const { isDate, isRegExp, isPromise, isMap, isSet, "
                    + "isWeakMap, isWeakSet, isNativeError, isArrayBuffer, isSharedArrayBuffer, isAnyArrayBuffer, isArrayBufferView, isDataView, "
                    + "isTypedArray, isUint8Array, isUint8ClampedArray, isUint16Array, isUint32Array, isInt8Array, isInt16Array, isInt32Array, "
                    + "isFloat32Array, isFloat64Array, isBigInt64Array, isBigUint64Array, isAsyncFunction, isGeneratorFunction, isGeneratorObject, "
                    + "isBoxedPrimitive, isSymbolObject, isProxy } = types; export default types;"),
            java.util.Map.entry("node:child_process", resource("node/child_process.js")),
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
            java.util.Map.entry("node:diagnostics_channel", resource("node/diagnostics_channel.js")),
            java.util.Map.entry("node:dns", resource("node/dns.js")),
            java.util.Map.entry("node:async_hooks", resource("node/async_hooks.js")),
            java.util.Map.entry("node:perf_hooks", resource("node/perf_hooks.js")),
            java.util.Map.entry("node:worker_threads", resource("node/worker_threads.js")),
            java.util.Map.entry("node:module", resource("node/module.js")),
            java.util.Map.entry("node:v8", resource("node/v8.js")),
            // The WHATWG streams (D-124): the globals, loaded when this module is imported.
            java.util.Map.entry("node:stream/web", "const w = globalThis; const names = ['ReadableStream', 'ReadableStreamDefaultReader', "
                    + "'ReadableStreamBYOBReader', 'ReadableStreamBYOBRequest', 'ReadableByteStreamController', 'ReadableStreamDefaultController', "
                    + "'TransformStream', 'TransformStreamDefaultController', 'WritableStream', 'WritableStreamDefaultWriter', "
                    + "'WritableStreamDefaultController', 'ByteLengthQueuingStrategy', 'CountQueuingStrategy', 'TextEncoderStream', "
                    + "'TextDecoderStream', 'CompressionStream', 'DecompressionStream']; const api = Object.fromEntries(names.map((n) => [n, w[n]])); "
                    + "export default api; export const { ReadableStream, ReadableStreamDefaultReader, ReadableStreamBYOBReader, "
                    + "ReadableStreamBYOBRequest, ReadableByteStreamController, ReadableStreamDefaultController, TransformStream, "
                    + "TransformStreamDefaultController, WritableStream, WritableStreamDefaultWriter, WritableStreamDefaultController, "
                    + "ByteLengthQueuingStrategy, CountQueuingStrategy, TextEncoderStream, TextDecoderStream, CompressionStream, "
                    + "DecompressionStream } = api;"),
            java.util.Map.entry("node:dns/promises", "import dns from 'node:dns'; export const { lookup, lookupService, Resolver, getServers, setServers, "
                    + "getDefaultResultOrder, setDefaultResultOrder, resolve, resolve4, resolve6, resolveAny, resolveCaa, resolveCname, resolveMx, "
                    + "resolveNaptr, resolveNs, resolvePtr, resolveSoa, resolveSrv, resolveTlsa, resolveTxt, reverse } = dns.promises; "
                    + "export default dns.promises;"),
            java.util.Map.entry("automately:kv", hostModule("kv")),
            java.util.Map.entry("automately:bus", hostModule("bus")),
            java.util.Map.entry("automately:jobs", hostModule("jobs")),
            java.util.Map.entry("automately:files", hostModule("files")),
            // The host's own fetch: the global is Node's WHATWG one (D-124).
            java.util.Map.entry("automately:fetch", "const api = globalThis[Symbol.for('automately.hostFetch')]; export default api; export { api as fetch };"),
            java.util.Map.entry("automately:secrets", hostModule("secrets")),
            java.util.Map.entry("automately:log", hostModule("log")));

    /**
     * The built-ins with this job's values in them: {@code process.env} in the process stand-in (D-095), as plain JSON
     * so nothing runs, and the isolate's heap limit as {@code os.totalmem()} (D-103).
     */
    public static java.util.Map<String, String> forJob(java.util.Map<String, String> env, long heapBytes) {
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

    static String resource(String file) {
        try (java.io.InputStream in = Builtins.class.getResourceAsStream(ROOT + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + ROOT + file);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String loadPrelude() {
        StringBuilder prelude = new StringBuilder("(() => {\n");
        for (String file : PRELUDE_FILES) {
            try (java.io.InputStream in = Builtins.class.getResourceAsStream(ROOT + file)) {
                if (in == null) {
                    throw new IllegalStateException("Missing prelude file " + ROOT + file);
                }
                prelude.append("// --- ").append(file).append(" ---\n")
                        .append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).append('\n');
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return prelude.append("})()").toString();
    }

}
