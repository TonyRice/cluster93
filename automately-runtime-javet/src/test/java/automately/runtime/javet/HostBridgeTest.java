package automately.runtime.javet;

import automately.core.job.JobStatus;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.HostException;
import automately.core.runtime.HostServices;
import automately.core.runtime.Inbound;
import automately.core.runtime.JobContext;
import automately.core.runtime.LogSink;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.StateSink;
import automately.core.state.JobState;
import io.vertx.core.Future;
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

/** The async host-call mechanism (docs/design/host-apis.md), against a scripted fake {@link HostServices}. */
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

    /** Ops: "echo" (after 20 ms), "slow" (after args.ms), "boom" (HostException). Listen kind "test.events". */
    private static class FakeHost implements HostServices {

        final List<Consumer<Inbound>> listeners = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public Future<Object> call(String operation, JsonObject args) {
            calls.add(operation);
            Promise<Object> promise = Promise.promise();
            switch (operation) {
                case "echo" -> vertx.setTimer(20, id -> promise.complete(args.getValue("value")));
                case "slow" -> vertx.setTimer(args.getLong("ms"), id -> promise.complete(args.getLong("ms")));
                case "boom" -> promise.fail(new HostException("RangeError", "limit_exceeded", "value too large"));
                default -> promise.fail(new HostException("Error", "unknown", "no such op " + operation));
            }
            return promise.future();
        }

        @Override
        public Future<Registration> listen(String kind, JsonObject args, Consumer<Inbound> listener) {
            listeners.add(listener);
            return Future.succeededFuture(() -> {
                listeners.remove(listener);
                return Future.succeededFuture();
            });
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
        FakeHost host = new FakeHost() {
            @Override
            public Future<Object> call(String operation, JsonObject args) {
                Promise<Object> promise = Promise.promise();
                vertx.setTimer(300, id -> promise.complete("late"));
                return promise.future();
            }
        };

        ExecutionResult result = run("export default () => kv.get('k')", host);

        assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("late");
    }

    @Test
    void parallelCallsOverlap() throws Exception {
        FakeHost host = new FakeHost() {
            @Override
            public Future<Object> call(String operation, JsonObject args) {
                Promise<Object> promise = Promise.promise();
                vertx.setTimer(300, id -> promise.complete(args.getString("key")));
                return promise.future();
            }
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

    @Test
    void servedResponsesAreNormalised() throws Exception {
        FakeHost host = new FakeHost();
        JsonObject service = new JsonObject().put("name", "web").put("revision", 1).put("run", 1).put("config", new JsonObject()).putNull("previousRun");
        RunningExecution running = start("""
                export default async (input, ctx) => {
                  await ctx.serve(async (req) => {
                    if (req.path === '/text') return 'plain';
                    if (req.path === '/json') return { status: 201, body: { echo: await req.json(), q: req.query.x, h: req.headers.get('X-Test') } };
                    if (req.path === '/empty') return undefined;
                    throw new Error('nope');
                  });
                  return ctx.stopRequested;
                }
                """, host, (level, line) -> { }, true, service);
        host.awaitListener();

        JsonObject base = new JsonObject().put("method", "POST").put("url", "http://web/").put("host", "web")
                .put("query", new JsonObject().put("x", "1")).put("headers", new JsonObject().put("x-test", "yes")).put("body", "{\"a\":1}");
        JsonObject text = (JsonObject) host.send(base.copy().put("path", "/text"), true).get(5, TimeUnit.SECONDS);
        JsonObject json = (JsonObject) host.send(base.copy().put("path", "/json"), true).get(5, TimeUnit.SECONDS);
        JsonObject empty = (JsonObject) host.send(base.copy().put("path", "/empty"), true).get(5, TimeUnit.SECONDS);
        CompletableFuture<Object> broken = host.send(base.copy().put("path", "/boom"), true);

        assertThat(text).isEqualTo(new JsonObject().put("status", 200)
                .put("headers", new JsonObject().put("content-type", "text/plain; charset=utf-8")).put("body", "plain"));
        assertThat(json.getInteger("status")).isEqualTo(201);
        assertThat(new JsonObject(json.getString("body"))).isEqualTo(new JsonObject()
                .put("echo", new JsonObject().put("a", 1)).put("q", "1").put("h", "yes"));
        assertThat(json.getJsonObject("headers").getString("content-type")).isEqualTo("application/json");
        assertThat(empty.getInteger("status")).isEqualTo(204);
        assertThat(broken).failsWithin(Duration.ofSeconds(5)).withThrowableThat().withMessageContaining("500 nope");
        running.stop("done", Duration.ofSeconds(2));
        await(running);
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
        FakeHost host = new FakeHost() {
            @Override
            public Future<Object> call(String operation, JsonObject args) {
                Promise<Object> promise = Promise.promise();
                vertx.setTimer(200, id -> promise.complete(1));
                return promise.future();
            }
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
}
