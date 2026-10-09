package automately.runtime.javet;

import automately.core.job.JobStatus;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.BodyStream;
import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.api.BusApi;
import automately.core.runtime.api.FetchApi;
import automately.core.runtime.api.FilesApi;
import automately.core.runtime.api.HostApis;
import automately.core.runtime.api.HttpApi;
import automately.core.runtime.api.KvApi;
import automately.core.runtime.api.QueueApi;
import automately.core.runtime.api.Registration;
import automately.core.runtime.Inbound;
import automately.core.runtime.JobContext;
import automately.core.runtime.LogSink;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.StateSink;
import automately.core.state.JobState;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** The async host-call mechanism (docs/design/host-apis.md), against fake {@link HostApis}. */
class HostBridgeTest {

    private static Vertx vertx;
    private static JavetScriptRuntime runtime;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        runtime = new JavetScriptRuntime(vertx, 64, 8);
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Fake host APIs: {@code kv.get} answers through {@link #kvGet} (by default an error), bus listeners and HTTP servers
     * are recorded so the test can deliver to them, and fetch returns {@link #fetchChunks}.
     */
    private static class FakeHost implements HostApis {

        final List<Consumer<Inbound>> listeners = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        volatile java.util.function.Function<String, Future<Object>> kvGet =
                key -> Future.failedFuture(new HostException("Error", "unknown", "no such op kv.get"));

        final List<Consumer<HttpExchange>> servers = new CopyOnWriteArrayList<>();
        /** What fetch returns: a status and the body chunks. */
        volatile List<String> fetchChunks = List.of("hello ", "world");
        final List<JsonObject> fetchesSent = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> fetchesCancelled = new CopyOnWriteArrayList<>();

        private Future<Registration> register(Consumer<Inbound> listener) {
            listeners.add(listener);
            return Future.succeededFuture(() -> {
                listeners.remove(listener);
                return Future.succeededFuture();
            });
        }

        @Override
        public KvApi kv() {
            return new KvApi() {
                @Override
                public Future<Object> get(String namespace, String key) {
                    return kvGet.apply(key);
                }

                @Override
                public Future<Void> set(String namespace, String key, Object value, Long ttlMs) {
                    return HostApis.NONE.kv().set(namespace, key, value, ttlMs);
                }

                @Override
                public Future<Boolean> delete(String namespace, String key) {
                    return HostApis.NONE.kv().delete(namespace, key);
                }

                @Override
                public Future<JsonObject> list(String namespace, String prefix, int limit, String cursor) {
                    return HostApis.NONE.kv().list(namespace, prefix, limit, cursor);
                }

                @Override
                public Future<Long> increment(String namespace, String key, long by) {
                    return HostApis.NONE.kv().increment(namespace, key, by);
                }
            };
        }

        @Override
        public BusApi bus() {
            return new BusApi() {
                @Override
                public Future<Void> publish(String topic, Object message) {
                    return HostApis.NONE.bus().publish(topic, message);
                }

                @Override
                public Future<Object> request(String topic, Object message, long timeoutMs) {
                    return HostApis.NONE.bus().request(topic, message, timeoutMs);
                }

                @Override
                public Future<Registration> subscribe(String topic, Consumer<Inbound> listener) {
                    return register(listener);
                }

                @Override
                public Future<Registration> handle(String topic, Consumer<Inbound> listener) {
                    return register(listener);
                }
            };
        }

        @Override
        public QueueApi queue() {
            return HostApis.NONE.queue();
        }

        @Override
        public automately.core.runtime.api.JobsApi jobs() {
            return HostApis.NONE.jobs();
        }

        @Override
        public HttpApi http() {
            return handler -> {
                servers.add(handler);
                return Future.succeededFuture(() -> {
                    servers.remove(handler);
                    return Future.succeededFuture();
                });
            };
        }

        @Override
        public FetchApi fetch() {
            return (args, body) -> {
                fetchesSent.add(args.copy().put("bytes", body == null ? null : java.util.HexFormat.of().formatHex(body)));
                JsonObject meta = new JsonObject().put("status", 200).put("statusText", "OK").put("url", args.getString("url"))
                        .put("headers", new JsonObject().put("content-type", "text/plain"));
                return Future.succeededFuture(new FetchedResponse(meta, new ListStream(fetchChunks, () -> fetchesCancelled.add(args.getString("url")))));
            };
        }

        @Override
        public FilesApi files() {
            return new FilesApi() {
                @Override
                public Future<JsonObject> stat(String path) {
                    return HostApis.NONE.files().stat(path);
                }

                @Override
                public Future<JsonObject> list(String prefix, int limit, String cursor) {
                    return HostApis.NONE.files().list(prefix, limit, cursor);
                }

                @Override
                public Future<Boolean> delete(String path) {
                    return HostApis.NONE.files().delete(path);
                }

                @Override
                public Future<automately.core.files.FileWriter> create(String path, String contentType) {
                    return Future.failedFuture(new HostException("Error", "unavailable", "no files in this fake"));
                }

                @Override
                public Future<FetchedResponse> open(String path) {
                    return Future.failedFuture(new HostException("Error", "not_found", "No such file: " + path));
                }

                @Override
                public Future<Void> respond(HttpExchange exchange, String path, JsonObject headers) {
                    exchange.respond(200, new JsonObject().put("x-file", path));
                    exchange.end();
                    return Future.succeededFuture();
                }
            };
        }

        void awaitServer() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (servers.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(servers).isNotEmpty();
        }

        @Override
        public Future<Void> close() {
            closed = true;
            return Future.succeededFuture();
        }

        void awaitListener() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (listeners.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(listeners).isNotEmpty();
        }

        CompletableFuture<Object> send(Object payload, boolean expectReply) {
            CompletableFuture<Object> answer = new CompletableFuture<>();
            Inbound inbound = new Inbound() {
                @Override
                public Object payload() {
                    return payload;
                }

                @Override
                public boolean expectsReply() {
                    return expectReply;
                }

                @Override
                public void reply(Object value) {
                    answer.complete(value);
                }

                @Override
                public void fail(int code, String message) {
                    answer.completeExceptionally(new IllegalStateException(code + " " + message));
                }
            };
            listeners.forEach(listener -> listener.accept(inbound));
            if (!expectReply) {
                answer.complete(null);
            }
            return answer;
        }
    }

    private static RunningExecution start(String script, FakeHost host, LogSink logs, boolean longRunning, JsonObject service) {
        JobContext context = new JobContext("job-h", 1, OrphanPolicy.FAIL, null, JobState.EMPTY, service);
        Execution execution = new Execution("job-h", "tenant-a", script, new JsonObject(), longRunning ? Duration.ZERO : Duration.ofSeconds(10),
                context, longRunning);
        return runtime.execute(execution, logs, StateSink.DISCARD, host);
    }

    private static ExecutionResult run(String script, FakeHost host) throws Exception {
        return await(start(script, host, (level, line) -> { }, false, null));
    }

    private static ExecutionResult await(RunningExecution running) throws Exception {
        return running.result().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    @Test
    void hostFailuresBecomeTypedErrors() throws Exception {
        ExecutionResult result = run("""
                export default async () => {
                  try { await kv.get('anything'); } catch (e) { return [e.name, e.code, e.message]; }
                }
                """, new FakeHost());

        assertThat(result.output()).isEqualTo(new JsonArray().add("Error").add("unknown").add("no such op kv.get"));
    }

    @Test
    void aJobWaitingOnAHostCallIsNotNeverSettles() throws Exception {
        FakeHost host = new FakeHost();
        host.kvGet = key -> {
            Promise<Object> promise = Promise.promise();
            vertx.setTimer(300, id -> promise.complete("late"));
            return promise.future();
        };

        ExecutionResult result = run("export default () => kv.get('k')", host);

        assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("late");
    }

    @Test
    void parallelCallsOverlap() throws Exception {
        FakeHost host = new FakeHost();
        host.kvGet = key -> {
            Promise<Object> promise = Promise.promise();
            vertx.setTimer(300, id -> promise.complete(key));
            return promise.future();
        };
        long start = System.nanoTime();

        ExecutionResult result = run("export default () => Promise.all(['a', 'b', 'c', 'd'].map((k) => kv.get(k)))", host);

        assertThat(result.output()).isEqualTo(new JsonArray().add("a").add("b").add("c").add("d"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("4 × 300 ms calls overlap").isLessThan(Duration.ofMillis(1000));
    }

    @Test
    void listenersReceiveEventsAndKeepTheJobAlive() throws Exception {
        FakeHost host = new FakeHost();
        RunningExecution running = start("""
                export default () => new Promise(async (resolve) => {
                  const seen = [];
                  await bus.subscribe('orders', (message, meta) => {
                    seen.push(`${meta.topic}:${message.id}`);
                    if (seen.length === 3) resolve(seen);
                  });
                })
                """, host, (level, line) -> { }, false, null);
        host.awaitListener();

        for (int i = 1; i <= 3; i++) {
            host.send(new JsonObject().put("topic", "orders").put("message", new JsonObject().put("id", i)), false);
        }

        assertThat(await(running).output()).isEqualTo(new JsonArray().add("orders:1").add("orders:2").add("orders:3"));
        assertThat(host.listeners).as("unregistered when the script ended").isEmpty();
        assertThat(host.closed).isTrue();
    }

    @Test
    void handlersAnswerRequestsAndErrorsBecomeFailures() throws Exception {
        FakeHost host = new FakeHost();
        RunningExecution running = start("""
                export default async (input, ctx) => {
                  await bus.handle('math', async (message) => {
                    if (message.op === 'divide' && message.b === 0) throw new Error('division by zero');
                    return { result: message.a / message.b };
                  });
                  return ctx.stopRequested;
                }
                """, host, (level, line) -> { }, true, null);
        host.awaitListener();

        Object ok = host.send(new JsonObject().put("topic", "math").put("message", new JsonObject().put("op", "divide").put("a", 6).put("b", 3)), true)
                .get(5, TimeUnit.SECONDS);
        CompletableFuture<Object> failed = host.send(new JsonObject().put("topic", "math")
                .put("message", new JsonObject().put("op", "divide").put("a", 1).put("b", 0)), true);

        assertThat(ok).isEqualTo(new JsonObject().put("result", 2));
        assertThat(failed).failsWithin(Duration.ofSeconds(5)).withThrowableThat().withMessageContaining("500 division by zero");

        running.stop("done", Duration.ofSeconds(2));
        await(running);
        assertThat(host.listeners).as("the handler was unregistered when the script ended").isEmpty();
    }

    @Test
    void anErrorInASubscriberIsLoggedNotFatal() throws Exception {
        FakeHost host = new FakeHost();
        List<String> logs = new CopyOnWriteArrayList<>();
        RunningExecution running = start("""
                export default async (input, ctx) => {
                  await bus.subscribe('t', () => { throw new Error('bad message'); });
                  return ctx.stopRequested;
                }
                """, host, (level, line) -> logs.add(level + ":" + line), true, null);
        host.awaitListener();

        host.send(new JsonObject().put("topic", "t").put("message", 1), false);
        Thread.sleep(200);
        running.stop("done", Duration.ofSeconds(2));

        assertThat(await(running).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(logs).anySatisfy(line -> assertThat(line).startsWith("error:Uncaught error in listener").contains("bad message"));
    }

    @Test
    void unsubscribingStopsDelivery() throws Exception {
        FakeHost host = new FakeHost();
        ExecutionResult result = run("""
                export default async () => {
                  const stop = await bus.subscribe('t', () => {});
                  stop();
                  return 'unsubscribed';
                }
                """, host);

        assertThat(result.output()).isEqualTo("unsubscribed");
        assertThat(host.listeners).isEmpty();
    }

    @Test
    void onlyServicesCanServe() throws Exception {
        ExecutionResult result = run("""
                export default (input, ctx) => { try { ctx.serve(() => 'hi'); } catch (e) { return e.message; } }
                """, new FakeHost());

        assertThat(result.output()).asString().contains("Only services can serve HTTP");
    }

    private static final JsonObject SERVICE = new JsonObject().put("name", "web").put("revision", 1).put("run", 1)
            .put("config", new JsonObject()).putNull("previousRun");

    private RunningExecution serving(String handler, FakeHost host) throws Exception {
        RunningExecution running = start("export default async (input, ctx) => { await ctx.serve(" + handler + "); return ctx.stopRequested; }",
                host, (level, line) -> { }, true, SERVICE);
        host.awaitServer();
        return running;
    }

    private static FakeExchange send(FakeHost host, FakeExchange exchange) {
        host.servers.forEach(server -> server.accept(exchange));
        return exchange;
    }

    @Test
    void servedResponsesAreNormalised() throws Exception {
        FakeHost host = new FakeHost();
        RunningExecution running = serving("""
                async (req) => {
                  if (req.path === '/text') return 'plain';
                  if (req.path === '/json') return { status: 201, headers: { 'X-Test': 'yes' }, body: { echo: await req.json() } };
                  if (req.path === '/empty') return undefined;
                  throw new Error('nope');
                }
                """, host);

        FakeExchange text = send(host, new FakeExchange("GET", "/text", List.of()));
        FakeExchange json = send(host, new FakeExchange("POST", "/json", List.of("{\"a\"", ":1}")));
        FakeExchange empty = send(host, new FakeExchange("GET", "/empty", List.of()));
        FakeExchange broken = send(host, new FakeExchange("GET", "/boom", List.of()));
        text.ended.get(5, TimeUnit.SECONDS);
        json.ended.get(5, TimeUnit.SECONDS);
        empty.ended.get(5, TimeUnit.SECONDS);

        assertThat(text.status).isEqualTo(200);
        assertThat(text.headers.getString("content-type")).isEqualTo("text/plain; charset=utf-8");
        assertThat(text.text()).isEqualTo("plain");
        assertThat(json.status).isEqualTo(201);
        assertThat(json.headers.getString("x-test")).isEqualTo("yes");
        assertThat(json.headers.getString("content-type")).isEqualTo("application/json");
        assertThat(new JsonObject(json.text())).isEqualTo(new JsonObject().put("echo", new JsonObject().put("a", 1)));
        assertThat(empty.status).isEqualTo(204);
        assertThat(broken.ended).failsWithin(Duration.ofSeconds(5)).withThrowableThat().withMessageContaining("500 nope");
        running.stop("done", Duration.ofSeconds(2));
        await(running);
    }

    @Test
    void requestBodiesStreamInChunks() throws Exception {
        FakeHost host = new FakeHost();
        RunningExecution running = serving("""
                async (req) => {
                  let chunks = 0, bytes = 0;
                  for await (const part of req.body) { chunks++; bytes += part.length; }
                  return { body: { chunks, bytes } };
                }
                """, host);
        List<String> parts = java.util.stream.IntStream.range(0, 50).mapToObj(i -> "x".repeat(1000)).toList();

        FakeExchange upload = send(host, new FakeExchange("POST", "/upload", parts));
        upload.ended.get(10, TimeUnit.SECONDS);

        assertThat(new JsonObject(upload.text())).isEqualTo(new JsonObject().put("chunks", 50).put("bytes", 50_000));
        running.stop("done", Duration.ofSeconds(2));
        await(running);
    }

    @Test
    void anUnreadRequestBodyIsPausedNotBuffered() throws Exception {
        FakeHost host = new FakeHost();
        RunningExecution running = serving("""
                async (req) => {
                  const it = req.body[Symbol.asyncIterator]();
                  await it.next();                       // read one chunk, then stall
                  await new Promise((r) => setTimeout(r, 300));
                  return 'stalled';
                }
                """, host);
        List<String> parts = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> "y").toList();

        FakeExchange upload = send(host, new FakeExchange("POST", "/slow", parts));
        Thread.sleep(150);

        assertThat(upload.body.delivered()).as("the source paused at the high-water mark").isLessThanOrEqualTo(HostBridge.HIGH_WATER + 2);
        upload.ended.get(5, TimeUnit.SECONDS);
        running.stop("done", Duration.ofSeconds(2));
        await(running);
    }

    @Test
    void generatorResponsesStreamAndWaitForTheClient() throws Exception {
        FakeHost host = new FakeHost();
        RunningExecution running = serving("""
                async (req) => ({
                  headers: { 'content-type': 'text/event-stream' },
                  body: (async function* () {
                    for (let i = 1; i <= 5; i++) { yield `data: ${i}\n\n`; }
                  })(),
                })
                """, host);
        FakeExchange exchange = new FakeExchange("GET", "/events", List.of());
        exchange.writeDelayMs = 60;
        long start = System.nanoTime();

        send(host, exchange).ended.get(10, TimeUnit.SECONDS);

        assertThat(exchange.headers.getString("content-type")).isEqualTo("text/event-stream");
        assertThat(exchange.text()).isEqualTo("data: 1\n\ndata: 2\n\ndata: 3\n\ndata: 4\n\ndata: 5\n\n");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("each write waited for the slow client").isGreaterThanOrEqualTo(Duration.ofMillis(5 * 60));
        running.stop("done", Duration.ofSeconds(2));
        await(running);
    }

    @Test
    void aDisconnectedClientEndsTheGenerator() throws Exception {
        FakeHost host = new FakeHost();
        List<String> logs = new CopyOnWriteArrayList<>();
        RunningExecution running = start("""
                export default async (input, ctx) => {
                  await ctx.serve(async () => ({ body: (async function* () {
                    try { for (let i = 0; ; i++) { yield `tick ${i}\n`; } }
                    finally { console.log('generator closed'); }
                  })() }));
                  return ctx.stopRequested;
                }
                """, host, (level, line) -> logs.add(line), true, SERVICE);
        host.awaitServer();
        FakeExchange exchange = send(host, new FakeExchange("GET", "/forever", List.of()));
        Thread.sleep(100);

        exchange.clientGone = true;
        Thread.sleep(200);

        assertThat(logs).contains("generator closed");
        running.stop("done", Duration.ofSeconds(2));
        await(running);
    }

    @Test
    void fetchBodiesStreamAndCanBeBuffered() throws Exception {
        FakeHost host = new FakeHost();
        host.fetchChunks = List.of("a", "b", "c");

        ExecutionResult result = run("""
                export default async () => {
                  const streamed = [];
                  for await (const part of (await fetch('https://x.test/stream')).body) streamed.push(new TextDecoder().decode(part));
                  const text = await (await fetch('https://x.test/text')).text();
                  return [streamed, text];
                }
                """, host);

        assertThat(result.output()).as(String.valueOf(result.error())).isEqualTo(new JsonArray().add(new JsonArray().add("a").add("b").add("c")).add("abc"));
    }

    @Test
    void fetchSendsBytesAsBytesAndFormsAsForms() throws Exception {
        FakeHost host = new FakeHost();
        host.fetchChunks = List.of();

        run("""
                export default async () => {
                  const whole = new Uint8Array([1, 2, 0xff, 0x00, 0x80, 9]);
                  await fetch('https://x.test/bytes', { method: 'POST', body: whole.subarray(2, 5) });
                  await fetch('https://x.test/text', { method: 'POST', body: 'hé' });
                  await fetch('https://x.test/form', { method: 'POST', body: new URLSearchParams({ a: '1 2', b: 'x' }) });
                }
                """, host);

        assertThat(host.fetchesSent).hasSize(3);
        assertThat(host.fetchesSent.get(0).getString("bytes")).isEqualTo("ff0080");
        assertThat(host.fetchesSent.get(1).getString("bytes")).isEqualTo("68c3a9");
        assertThat(host.fetchesSent.get(2).getString("bytes")).isEqualTo(java.util.HexFormat.of().formatHex("a=1+2&b=x".getBytes()));
        assertThat(host.fetchesSent.get(2).getJsonObject("headers").getString("content-type")).startsWith("application/x-www-form-urlencoded");
    }

    @Test
    void bufferingAHugeFetchBodyFailsAndCancelsIt() throws Exception {
        FakeHost host = new FakeHost();
        host.fetchChunks = java.util.stream.IntStream.range(0, 6).mapToObj(i -> "z".repeat(1024 * 1024)).toList();

        ExecutionResult result = run("""
                export default async () => {
                  try { await (await fetch('https://x.test/huge')).text(); } catch (e) { return [e.name, e.message]; }
                }
                """, host);

        assertThat(result.output()).asString().contains("RangeError").contains("read it as a stream");
        assertThat(host.fetchesCancelled).contains("https://x.test/huge");
    }

    @Test
    void anUnreadFetchBodyIsCancelledWhenTheScriptEnds() throws Exception {
        FakeHost host = new FakeHost();
        host.fetchChunks = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "chunk").toList();

        run("export default async () => { await fetch('https://x.test/ignored'); return 'done'; }", host);

        assertThat(host.fetchesCancelled).contains("https://x.test/ignored");
    }

    @Test
    void textEncodingRoundTripsUnicode() throws Exception {
        ExecutionResult result = run("""
                export default () => {
                  const text = 'héllo wörld ✓ 🚀';
                  const bytes = new TextEncoder().encode(text);
                  return [bytes.length, new TextDecoder().decode(bytes) === text];
                }
                """, new FakeHost());

        // h é(2) l l o ␠ w ö(2) r l d ␠ ✓(3) ␠ 🚀(4) = 22 bytes of UTF-8
        assertThat(result.output()).isEqualTo(new JsonArray().add(22).add(true));
    }

    @Test
    void aBodyCanOnlyBeReadOnce() throws Exception {
        FakeHost host = new FakeHost();
        ExecutionResult result = run("""
                export default async () => {
                  const res = await fetch('https://x.test/once');
                  await res.text();
                  try { await res.text(); } catch (e) { return e.name; }
                }
                """, host);

        assertThat(result.output()).isEqualTo("TypeError");
    }

    @Test
    void structuredLogsCarryFields() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        await(start("export default () => { log.info('order placed', { id: 7, total: 9.5 }); log.warn('plain'); }",
                new FakeHost(), new LogSink() {
                    @Override
                    public void log(String level, String line) {
                        logs.add(level + ":" + line);
                    }

                    @Override
                    public void log(String level, String message, JsonObject fields) {
                        logs.add(level + ":" + message + "|" + (fields == null ? "-" : fields.encode()));
                    }
                }, false, null));

        assertThat(logs).containsExactly("info:order placed|{\"id\":7,\"total\":9.5}", "warn:plain|-");
    }

    @Test
    void tooManyCallsInFlightAreRefused() throws Exception {
        FakeHost host = new FakeHost();
        host.kvGet = key -> {
            Promise<Object> promise = Promise.promise();
            vertx.setTimer(200, id -> promise.complete(1));
            return promise.future();
        };

        ExecutionResult result = run("""
                export default async () => {
                  const results = await Promise.allSettled(Array.from({ length: 300 }, (_, i) => kv.get('k' + i)));
                  return results.filter((r) => r.status === 'rejected' && r.reason.name === 'RangeError').length;
                }
                """, host);

        assertThat((Integer) result.output()).isEqualTo(300 - HostBridge.MAX_CALLS_IN_FLIGHT);
    }

    @Test
    void hostApiGlobalsCannotBeReplaced() throws Exception {
        ExecutionResult result = run("""
                export default () => {
                  for (const name of ['kv', 'bus', 'fetch', 'log']) { try { globalThis[name] = null; } catch {} }
                  return [typeof kv.get, typeof bus.publish, typeof fetch, typeof log.info];
                }
                """, new FakeHost());

        assertThat(result.output()).isEqualTo(new JsonArray().add("function").add("function").add("function").add("function"));
    }

    /** A body stream over fixed chunks; it delivers as long as it's resumed, like a socket. */
    static final class ListStream implements BodyStream {

        private final List<String> chunks;
        private final Runnable onCancel;
        private Consumer<Buffer> chunk;
        private Runnable end;
        private int next;
        private boolean paused = true;
        private boolean cancelled;
        int maxOutstandingObserved;

        ListStream(List<String> chunks, Runnable onCancel) {
            this.chunks = chunks;
            this.onCancel = onCancel;
        }

        @Override
        public synchronized void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
            this.chunk = chunk;
            this.end = end;
        }

        @Override
        public void pause() {
            synchronized (this) {
                paused = true;
            }
        }

        @Override
        public void resume() {
            synchronized (this) {
                paused = false;
            }
            vertx.runOnContext(v -> pump());
        }

        private void pump() {
            while (true) {
                Consumer<Buffer> target;
                Buffer data;
                synchronized (this) {
                    if (paused || cancelled || chunk == null) {
                        return;
                    }
                    if (next >= chunks.size()) {
                        cancelled = true;
                        end.run();
                        return;
                    }
                    target = chunk;
                    data = Buffer.buffer(chunks.get(next++));
                }
                target.accept(data);
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            onCancel.run();
        }

        synchronized int delivered() {
            return next;
        }
    }

    /** An exchange that records the response; writes can be slowed down to show backpressure. */
    static final class FakeExchange implements HttpExchange {

        final JsonObject request;
        final ListStream body;
        final CompletableFuture<Void> ended = new CompletableFuture<>();
        final List<String> written = new CopyOnWriteArrayList<>();
        volatile int status;
        volatile JsonObject headers;
        volatile long writeDelayMs;
        volatile boolean clientGone;

        FakeExchange(String method, String path, List<String> bodyChunks) {
            this.request = new JsonObject().put("method", method).put("url", "http://web" + path).put("path", path).put("host", "web")
                    .put("query", new JsonObject()).put("headers", new JsonObject());
            this.body = new ListStream(bodyChunks, () -> { });
        }

        @Override
        public JsonObject request() {
            return request;
        }

        @Override
        public BodyStream body() {
            return body;
        }

        @Override
        public void respond(int status, JsonObject headers) {
            this.status = status;
            this.headers = headers;
        }

        @Override
        public Future<Void> write(Buffer chunk) {
            if (clientGone) {
                return Future.failedFuture("The client disconnected");
            }
            Promise<Void> done = Promise.promise();
            vertx.setTimer(Math.max(1, writeDelayMs), id -> {
                written.add(chunk.toString());
                done.complete();
            });
            return done.future();
        }

        @Override
        public void end() {
            ended.complete(null);
        }

        @Override
        public void fail(int status, String message) {
            this.status = status;
            ended.completeExceptionally(new IllegalStateException(status + " " + message));
        }

        String text() {
            return String.join("", written);
        }
    }
}
