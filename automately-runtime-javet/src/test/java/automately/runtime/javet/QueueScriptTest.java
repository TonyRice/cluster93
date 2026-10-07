package automately.runtime.javet;

import automately.core.files.Room;
import automately.core.files.StorageCapacity;
import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.NodeHostApis;
import automately.core.host.ServeRegistry;
import automately.core.host.SharedDataKvStore;
import automately.core.job.JobStatus;
import automately.core.job.OrphanPolicy;
import automately.core.queue.QueueService;
import automately.core.queue.SharedDataQueueStore;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.StateSink;
import automately.core.state.JobState;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code bus.queue(name)} from real scripts (D-061), over the node's host APIs and the in-memory queue store. */
class QueueScriptTest {

    private static JavetScriptRuntime runtime;
    private Vertx vertx;
    private QueueService queues;
    private NodeHostApis.Node node;

    @BeforeAll
    static void createRuntime() {
        runtime = new JavetScriptRuntime(Vertx.vertx(), 64, 8);
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        queues = new QueueService(vertx, new SharedDataQueueStore(vertx), TenantQuota.unlimited(), StorageCapacity.UNLIMITED);
        node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, EgressPolicy.PUBLIC_ONLY), new ServeRegistry(),
                null, StorageCapacity.UNLIMITED, TenantQuota.unlimited(), Room.fixed(Long.MAX_VALUE), queues);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private ExecutionResult run(String script) throws Exception {
        JobContext context = new JobContext("job-q", 1, OrphanPolicy.FAIL, null, JobState.EMPTY, null);
        Execution execution = new Execution("job-q", "acme", script, new JsonObject(), Duration.ofSeconds(20), context, false);
        return runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD, new NodeHostApis(node, "acme", "job-q", false))
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void pushedMessagesAreConsumedAndAcknowledged() throws Exception {
        ExecutionResult result = run("""
                export default async () => {
                  const q = bus.queue('work');
                  for (const n of [1, 2, 3]) await q.push({ n });
                  return new Promise(async (resolve) => {
                    const seen = [];
                    await q.consume(async (message, info) => {
                      seen.push([message.n, info.attempt, info.queue]);
                      if (seen.length === 3) resolve(seen.sort());
                    }, { concurrency: 2 });
                  });
                }
                """);

        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo(new JsonArray().add(new JsonArray().add(1).add(1).add("work"))
                .add(new JsonArray().add(2).add(1).add("work")).add(new JsonArray().add(3).add(1).add("work")));
        awaitCounts("work", 0, 0, 0);
    }

    @Test
    void aThrowingHandlerGetsTheMessageAgain() throws Exception {
        ExecutionResult result = run("""
                export default async () => {
                  const q = bus.queue('flaky');
                  await q.push('hello');
                  return new Promise(async (resolve) => {
                    await q.consume(async (message, { attempt }) => {
                      if (attempt === 1) throw new Error('not yet');
                      resolve([message, attempt]);
                    });
                  });
                }
                """);

        assertThat(result.output()).isEqualTo(new JsonArray().add("hello").add(2));
        awaitCounts("flaky", 0, 0, 0);
    }

    @Test
    void usedUpAttemptsDeadLetterAndCanBeRetried() throws Exception {
        ExecutionResult result = run("""
                export default async () => {
                  const q = bus.queue('doomed');
                  const id = await q.push({ order: 7 }, { maxAttempts: 1 });
                  await new Promise(async (resolve) => {
                    const stop = await q.consume(async () => { setTimeout(resolve, 100); throw new Error('card declined'); });
                  });
                  const dead = await q.dead();
                  const stats = await q.stats();
                  const retried = await q.retry(id);
                  return { dead: dead.messages.map((m) => [m.message.order, m.lastError]), stats, retried, after: await q.stats() };
                }
                """);

        JsonObject output = (JsonObject) result.output();
        assertThat(output.getJsonArray("dead")).isEqualTo(new JsonArray().add(new JsonArray().add(7).add("card declined")));
        assertThat(output.getJsonObject("stats").getInteger("dead")).isEqualTo(1);
        assertThat(output.getBoolean("retried")).isTrue();
        assertThat(output.getJsonObject("after").getInteger("ready")).isEqualTo(1);
    }

    @Test
    void aMessageInFlightWhenTheScriptEndsGoesBackUntried() throws Exception {
        ExecutionResult result = run("""
                export default async () => {
                  const q = bus.queue('handoff');
                  await q.push('unfinished', { maxAttempts: 1 });
                  return new Promise(async (resolve) => {
                    await q.consume(() => { resolve('took it'); return new Promise(() => {}); });
                  });
                }
                """);

        assertThat(result.output()).isEqualTo("took it");
        awaitCounts("handoff", 1, 0, 0);
        assertThat(await(queues.claim("acme", "handoff", "next")).orElseThrow().attempt()).as("the release didn't count").isEqualTo(1);
    }

    @Test
    void queueArgumentsAreValidated() throws Exception {
        ExecutionResult result = run("""
                export default async () => {
                  const errors = [];
                  for (const call of [() => bus.queue('bad name').push(1), () => bus.queue('q').push(1, { maxAttempts: 0 }),
                                      () => bus.queue('q').consume(() => {}, { concurrency: 99 })]) {
                    try { await call(); errors.push('no error'); } catch (e) { errors.push(e.code); }
                  }
                  try { bus.queue('q').consume('not a function'); } catch (e) { errors.push(e.name); }
                  return errors;
                }
                """);

        assertThat(result.output()).isEqualTo(new JsonArray().add("invalid_argument").add("invalid_argument").add("invalid_argument").add("TypeError"));
    }

    private void awaitCounts(String queue, long ready, long inFlight, long dead) throws Exception {
        JsonObject expected = new JsonObject().put("ready", ready).put("inFlight", inFlight).put("dead", dead);
        long deadline = System.currentTimeMillis() + 5_000;
        JsonObject counts;
        do {
            counts = await(queues.stats("acme", queue));
            if (counts.equals(expected)) {
                return;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        assertThat(counts).isEqualTo(expected);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
