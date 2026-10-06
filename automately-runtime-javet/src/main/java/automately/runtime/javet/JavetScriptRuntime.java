package automately.runtime.javet;

import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.HostServices;
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

              // --- host calls: a promise per call, settled by the event loop ---
              const pending = new Map();
              let nextCall = 1;
              const hostError = (json) => {
                const e = JSON.parse(json);
                const error = new Error(e.message);
                error.name = e.name || 'Error';
                error.code = e.code;
                return error;
              };
              const callHost = (op, args) => new Promise((resolve, reject) => {
                const id = nextCall++;
                pending.set(id, { resolve, reject });
                host.call(op, JSON.stringify(args ?? {}), id);
              });
              const settle = (id, ok, json) => {
                const p = pending.get(id);
                if (!p) return;
                pending.delete(id);
                if (ok) p.resolve(json === undefined || json === null ? null : JSON.parse(json)); else p.reject(hostError(json));
              };

              // --- listeners: bus subscriptions, bus handlers, HTTP serving ---
              const listeners = new Map();
              let nextListener = 1;
              const listen = (kind, args, handler) => new Promise((resolve, reject) => {
                const listenerId = nextListener++;
                listeners.set(listenerId, handler);
                const callId = nextCall++;
                const stop = () => { if (listeners.delete(listenerId)) host.unlisten(listenerId); };
                pending.set(callId, { resolve: () => resolve(stop), reject: (e) => { listeners.delete(listenerId); reject(e); } });
                host.listen(kind, JSON.stringify(args ?? {}), listenerId, callId);
              });
              const deliver = (listenerId, payloadJson, replyId) => {
                const handler = listeners.get(listenerId);
                const answer = (ok, value) => { if (replyId) host.reply(replyId, ok, JSON.stringify(value === undefined ? null : value)); };
                const failed = (e) => {
                  const message = String(e && e.message ? e.message : e);
                  if (replyId) answer(false, { status: 500, message });
                  else host.log('error', `Uncaught error in listener: ${String(e && e.stack ? e.stack : e)}`);
                };
                if (!handler) { if (replyId) answer(false, { status: 503, message: 'No longer listening' }); return; }
                try {
                  Promise.resolve(handler(JSON.parse(payloadJson))).then((value) => answer(true, value), failed);
                } catch (e) {
                  failed(e);
                }
              };

              // --- host APIs (docs/scripting/host-apis.md) ---
              const fieldsJson = (fields) => {
                if (fields === undefined || fields === null) return 'null';
                try { return JSON.stringify(fields); } catch { return JSON.stringify({ fields: String(fields) }); }
              };
              const structured = (level) => (message, fields) => host.logFields(level, show(message), fieldsJson(fields));
              define('log', Object.freeze({ debug: structured('debug'), info: structured('info'), warn: structured('warn'), error: structured('error') }));

              const kvNamespace = (ns) => Object.freeze({
                get: (key) => callHost('kv.get', { ns, key }),
                set: (key, value, options) => callHost('kv.set', { ns, key, value: value === undefined ? null : value, ttlMs: options?.ttlMs ?? null }).then(() => undefined),
                delete: (key) => callHost('kv.delete', { ns, key }),
                list: (options) => callHost('kv.list', { ns, prefix: options?.prefix ?? '', limit: options?.limit ?? 100, cursor: options?.cursor ?? null }),
                increment: (key, by = 1) => callHost('kv.increment', { ns, key, by }),
              });
              const kv = kvNamespace('default');
              define('kv', Object.freeze({ ...kv, namespace: (name) => kvNamespace(String(name)) }));

              const onMessage = (handler) => {
                if (typeof handler !== 'function') throw new TypeError('A handler must be a function');
                return (payload) => handler(payload.message, Object.freeze({ topic: payload.topic }));
              };
              define('bus', Object.freeze({
                publish: (topic, message) => callHost('bus.publish', { topic, message: message === undefined ? null : message }).then(() => undefined),
                request: (topic, message, options) => callHost('bus.request', { topic, message: message === undefined ? null : message, timeoutMs: options?.timeoutMs ?? 10000 }),
                subscribe: (topic, handler) => listen('bus.subscribe', { topic }, onMessage(handler)),
                handle: (topic, handler) => listen('bus.handle', { topic }, onMessage(handler)),
              }));

              const headerBag = (raw) => {
                const lower = {};
                for (const [k, v] of Object.entries(raw ?? {})) lower[String(k).toLowerCase()] = String(v);
                return Object.freeze({ ...lower, get: (name) => lower[String(name).toLowerCase()] ?? null });
              };

              // --- UTF-8 text <-> bytes (V8 itself has no TextEncoder/TextDecoder) ---
              const utf8Encode = (text) => {
                const out = [];
                for (const ch of String(text)) {
                  let c = ch.codePointAt(0);
                  if (c < 0x80) out.push(c);
                  else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
                  else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
                  else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
                }
                return new Uint8Array(out);
              };
              const utf8Decode = (bytes) => {
                let s = '';
                for (let i = 0; i < bytes.length;) {
                  const b = bytes[i];
                  let c, n;
                  if (b < 0x80) { c = b; n = 1; }
                  else if (b >= 0xf0) { c = ((b & 7) << 18) | ((bytes[i + 1] & 63) << 12) | ((bytes[i + 2] & 63) << 6) | (bytes[i + 3] & 63); n = 4; }
                  else if (b >= 0xe0) { c = ((b & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63); n = 3; }
                  else if (b >= 0xc0) { c = ((b & 31) << 6) | (bytes[i + 1] & 63); n = 2; }
                  else { c = 0xfffd; n = 1; }
                  s += String.fromCodePoint(c);
                  i += n;
                }
                return s;
              };
              define('TextEncoder', class TextEncoder { get encoding() { return 'utf-8'; } encode(text = '') { return utf8Encode(text); } });
              define('TextDecoder', class TextDecoder { get encoding() { return 'utf-8'; } decode(bytes) { return bytes ? utf8Decode(bytes) : ''; } });

              // --- streams: chunks arrive from the host as Uint8Arrays; reading one acknowledges it (backpressure) ---
              const streams = new Map();
              const readable = (id) => {
                const st = { queue: [], waiter: null, done: false, error: null, taken: false };
                streams.set(id, st);
                const finish = () => { if (!st.done) { st.done = true; streams.delete(id); host.cancelStream(id); } };
                const iterator = {
                  next() {
                    if (st.queue.length) { const value = st.queue.shift(); host.ack(id); return Promise.resolve({ value, done: false }); }
                    if (st.error) return Promise.reject(st.error);
                    if (st.done) return Promise.resolve({ value: undefined, done: true });
                    return new Promise((resolve, reject) => { st.waiter = { resolve, reject }; });
                  },
                  return() { finish(); return Promise.resolve({ value: undefined, done: true }); },
                  [Symbol.asyncIterator]() { return this; },
                };
                return Object.freeze({
                  [Symbol.asyncIterator]() {
                    if (st.taken) throw new TypeError('This body has already been read');
                    st.taken = true;
                    return iterator;
                  },
                  cancel: finish,
                });
              };
              const chunk = (id, bytes) => {
                const st = streams.get(id);
                if (!st) return;
                if (bytes === null || bytes === undefined) {
                  st.done = true;
                  streams.delete(id);
                  if (st.waiter) { const w = st.waiter; st.waiter = null; w.resolve({ value: undefined, done: true }); }
                  return;
                }
                if (st.waiter) { const w = st.waiter; st.waiter = null; host.ack(id); w.resolve({ value: bytes, done: false }); }
                else st.queue.push(bytes);
              };
              const streamError = (id, message) => {
                const st = streams.get(id);
                if (!st) return;
                st.error = new Error(message);
                st.done = true;
                streams.delete(id);
                if (st.waiter) { const w = st.waiter; st.waiter = null; w.reject(st.error); }
              };
              const collect = async (body, limit, what) => {
                const parts = [];
                let size = 0;
                for await (const part of body) {
                  size += part.length;
                  if (size > limit) { body.cancel(); throw new RangeError(`${what} is over ${limit} bytes; read it as a stream instead`); }
                  parts.push(part);
                }
                const all = new Uint8Array(size);
                let offset = 0;
                for (const part of parts) { all.set(part, offset); offset += part.length; }
                return all;
              };
              const BUFFER_LIMIT = 32 * 1024 * 1024;
              const FETCH_BUFFER_LIMIT = 5 * 1024 * 1024;
              const toBytes = (part) => {
                if (part instanceof Uint8Array) return part;
                if (part instanceof ArrayBuffer) return new Uint8Array(part);
                if (ArrayBuffer.isView(part)) return new Uint8Array(part.buffer, part.byteOffset, part.byteLength);
                return String(part);
              };

              define('fetch', async (input, init = {}) => {
                const headers = {};
                for (const [k, v] of Object.entries(init.headers ?? {})) headers[String(k).toLowerCase()] = String(v);
                let body = null;
                if (init.body !== undefined && init.body !== null) {
                  if (typeof init.body === 'string') body = init.body;
                  else if (init.body instanceof Uint8Array) body = utf8Decode(init.body);
                  else { body = JSON.stringify(init.body); headers['content-type'] ??= 'application/json'; }
                }
                const args = { url: String(input), method: String(init.method ?? 'GET').toUpperCase(), headers, body, timeoutMs: init.timeoutMs ?? null };
                const r = await new Promise((resolve, reject) => {
                  const id = nextCall++;
                  pending.set(id, { resolve, reject });
                  host.fetch(JSON.stringify(args), id);
                });
                const stream = readable(r.streamId);
                return Object.freeze({
                  status: r.status, statusText: r.statusText, ok: r.status >= 200 && r.status < 300, url: r.url,
                  headers: headerBag(r.headers),
                  body: stream,
                  bytes: () => collect(stream, FETCH_BUFFER_LIMIT, 'The response body'),
                  text: async () => utf8Decode(await collect(stream, FETCH_BUFFER_LIMIT, 'The response body')),
                  json: async () => JSON.parse(utf8Decode(await collect(stream, FETCH_BUFFER_LIMIT, 'The response body'))),
                });
              });

              // --- serving HTTP: a request body streams in, a response streams out ---
              const isIterable = (v) => v && typeof v !== 'string' && !(v instanceof Uint8Array)
                  && (typeof v[Symbol.asyncIterator] === 'function' || typeof v[Symbol.iterator] === 'function');
              const toResponse = (value) => {
                if (value === undefined || value === null) return { status: 204, headers: {}, body: null };
                if (typeof value === 'string') return { status: 200, headers: { 'content-type': 'text/plain; charset=utf-8' }, body: value };
                if (value instanceof Uint8Array || isIterable(value)) return { status: 200, headers: {}, body: value };
                const headers = {};
                for (const [k, v] of Object.entries(value.headers ?? {})) headers[String(k).toLowerCase()] = String(v);
                let body = value.body ?? null;
                if (body !== null && typeof body !== 'string' && !(body instanceof Uint8Array) && !isIterable(body)) {
                  body = JSON.stringify(body);
                  headers['content-type'] ??= 'application/json';
                }
                return { status: Number(value.status ?? 200), headers, body };
              };
              const writeTo = (exchangeId, part) => new Promise((resolve, reject) => {
                const id = nextCall++;
                pending.set(id, { resolve, reject });
                host.write(exchangeId, toBytes(part), id);
              });
              const respond = async (exchangeId, value) => {
                const res = toResponse(value);
                host.respond(exchangeId, res.status, JSON.stringify(res.headers));
                const body = res.body;
                if (body === null) {
                  // nothing
                } else if (typeof body === 'string' || body instanceof Uint8Array) {
                  if (body.length) await writeTo(exchangeId, body);
                } else {
                  for await (const part of body) {
                    if (part === undefined || part === null) continue;
                    await writeTo(exchangeId, part);
                  }
                }
                host.end(exchangeId);
              };
              const servers = new Map();
              const serve = (handler) => new Promise((resolve, reject) => {
                // Same id space as bus listeners: the host keeps both in one registry.
                const listenerId = nextListener++;
                servers.set(listenerId, handler);
                const callId = nextCall++;
                pending.set(callId, { resolve: () => resolve(() => { if (servers.delete(listenerId)) host.unlisten(listenerId); }),
                                      reject: (e) => { servers.delete(listenerId); reject(e); } });
                host.serve(listenerId, callId);
              });
              const request = (listenerId, exchangeId, requestJson, bodyId) => {
                const handler = servers.get(listenerId);
                const r = JSON.parse(requestJson);
                const body = readable(bodyId);
                const req = Object.freeze({
                  method: r.method, url: r.url, path: r.path, host: r.host, query: Object.freeze({ ...r.query }),
                  headers: headerBag(r.headers),
                  body,
                  bytes: () => collect(body, BUFFER_LIMIT, 'The request body'),
                  text: async () => utf8Decode(await collect(body, BUFFER_LIMIT, 'The request body')),
                  json: async () => JSON.parse(utf8Decode(await collect(body, BUFFER_LIMIT, 'The request body'))),
                });
                (async () => {
                  let started = false;
                  try {
                    if (!handler) { host.fail(exchangeId, 503, 'No longer serving'); return; }
                    const value = await handler(req);
                    started = true;
                    await respond(exchangeId, value);
                  } catch (e) {
                    if (!started) host.log('error', `Uncaught error in request handler: ${String(e && e.stack ? e.stack : e)}`);
                    host.fail(exchangeId, 500, String(e && e.message ? e.message : e));
                  } finally {
                    body.cancel();
                  }
                })();
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
                  serve: (handler) => {
                    if (!c.service) throw new Error('Only services can serve HTTP; define this script as a service');
                    if (typeof handler !== 'function') throw new TypeError('ctx.serve needs a handler function');
                    return serve(handler);
                  },
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
              return { invoke, fire, requestStop, settle, deliver, request, chunk, streamError };
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
    public RunningExecution execute(Execution execution, LogSink logs, StateSink state, HostServices host) {
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

    private ExecutionResult run(Execution execution, LogSink logs, StateSink state, HostServices host, Control control) throws JavetException {
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

    private ExecutionResult invoke(V8Runtime v8, Execution execution, LogSink logs, StateSink state, HostServices host, Control control)
            throws JavetException {
        HostBridge bridge = new HostBridge(host, control.loop);
        List<JavetCallbackContext> callbacks = new ArrayList<>();
        try (V8ValueObject hostObject = v8.createV8ValueObject()) {
            callbacks.addAll(hostObject.bind(new HostConsole(logs)));
            callbacks.addAll(hostObject.bind(new HostState(state)));
            callbacks.addAll(hostObject.bind(new HostTimers(control.loop)));
            callbacks.addAll(hostObject.bind(bridge));
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
