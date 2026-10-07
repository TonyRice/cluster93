package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kills the node running a job, mid-step, in a real three-node cluster with real V8 isolates, and checks
 * the job's {@code onOrphan} policy is honoured end to end (docs/design/orphaned-jobs.md).
 */
class ChaosTest {

    private static final String TENANT = "tenant-a";

    /** Step "two" takes long enough on attempt 1 for the test to kill the node in the middle of it. */
    private static final String RESUMABLE = """
            export default async (input, ctx) => {
              for (const name of ['one', 'two', 'three']) {
                await ctx.step(name, () => {
                  console.log(`ran ${name} on attempt ${ctx.attempt}`);
                  const until = Date.now() + (name === 'two' && ctx.attempt === 1 ? 4000 : 20);
                  while (Date.now() < until) {}
                  return name.toUpperCase();
                });
              }
              return { attempt: ctx.attempt, skipped: ctx.completedSteps, previous: ctx.previous && ctx.previous.outcome };
            }
            """;

    private final List<RunningNode> nodes = new ArrayList<>();
    private Vertx clientVertx;
    private WebClient client;

    @BeforeEach
    void startCluster() throws Exception {
        for (String id : List.of("node-a", "node-b", "node-c")) {
            nodes.add(await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                    Map.entry("AUTOMATELY_NODE_ID", id),
                    Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                    Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                    Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                    Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                    Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                    Map.entry("AUTOMATELY_CAPACITY", "2"),
                    Map.entry("AUTOMATELY_MAX_HEAP_MB", "64"),
                    Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                    Map.entry("AUTOMATELY_ORPHAN_GRACE_MS", "400"),
                    Map.entry("AUTOMATELY_CLUSTERED", "true"),
                    Map.entry("AUTOMATELY_CLUSTER_PORT", "17701"),
                    Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1"))))));
        }
        TestAuth.use(nodes.getFirst());
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (get(nodes.getFirst(), "/v1/nodes").bodyAsJsonObject().getJsonArray("nodes").size() < 3) {
            assertThat(System.nanoTime()).as("cluster formed").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    @AfterEach
    void stopCluster() throws Exception {
        await(clientVertx.close());
        for (RunningNode node : nodes) {
            node.stop().toCompletionStage().toCompletableFuture().handle((v, e) -> null).get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void resumeContinuesOnAnotherNodeWithoutRepeatingCompletedSteps() throws Exception {
        String id = submitAndAwaitStep(RESUMABLE, "resume", "one");
        RunningNode victim = killNodeRunning(id);
        RunningNode survivor = survivorOtherThan(victim);
        Future<HttpResponse<Buffer>> events = client.get(survivor.httpPort(), "localhost", "/v1/jobs/" + id + "/events")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).timeout(30_000).send();

        JsonObject done = awaitTerminal(survivor, id);

        assertThat(done.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(done.getInteger("attempt")).isEqualTo(2);
        assertThat(done.getString("node")).isNotEqualTo(victim.nodeId());
        assertThat(done.getJsonObject("output")).isEqualTo(new JsonObject()
                .put("attempt", 2).put("skipped", new JsonArray().add("one")).put("previous", "ORPHANED"));
        assertThat(done.getJsonArray("logs").getList()).as("attempt 2 never re-ran step one")
                .containsExactly("ran two on attempt 2", "ran three on attempt 2");
        assertThat(done.getJsonArray("attempts").stream().map(a -> ((JsonObject) a).getString("outcome")))
                .containsExactly("ORPHANED", "SUCCEEDED");

        JsonObject steps = get(survivor, "/v1/jobs/" + id + "/steps").bodyAsJsonObject();
        assertThat(steps.getJsonArray("steps").stream().map(s -> ((JsonObject) s).getString("name") + "@" + ((JsonObject) s).getInteger("attempt")))
                .containsExactly("one@1", "two@2", "three@2");

        String stream = await(events).bodyAsString();
        assertThat(stream).contains("event: attempt", "\"previousOutcome\":\"ORPHANED\"", "event: end", "\"status\":\"SUCCEEDED\"");
    }

    @Test
    void failPolicyEndsAsOrphanedAndShowsHowFarItGot() throws Exception {
        String id = submitAndAwaitStep(RESUMABLE, "fail", "one");
        RunningNode victim = killNodeRunning(id);
        RunningNode survivor = survivorOtherThan(victim);

        JsonObject done = awaitTerminal(survivor, id);

        assertThat(done.getString("status")).isEqualTo("ORPHANED");
        assertThat(done.getInteger("attempt")).isEqualTo(1);
        assertThat(done.getString("error")).contains(victim.nodeId());
        JsonObject steps = get(survivor, "/v1/jobs/" + id + "/steps").bodyAsJsonObject();
        assertThat(steps.getJsonArray("steps").stream().map(s -> ((JsonObject) s).getString("name"))).containsExactly("one");
    }

    /** A service that counts in its checkpoint, so a restart shows whether state carried over. */
    static final String COUNTING_SERVICE = """
            export default async (input, ctx) => {
              let count = ctx.checkpoint?.count ?? 0;
              console.log(`run ${ctx.service.run} starting from ${count}`);
              while (true) {
                count++;
                await ctx.saveCheckpoint({ count });
                await Promise.race([ctx.sleep(100), ctx.stopRequested]);
              }
            }
            """;

    @Test
    void aServiceComesBackOnASurvivorWithItsCheckpoint() throws Exception {
        HttpResponse<Buffer> created = await(client.put(nodes.getFirst().httpPort(), "localhost", "/v1/services/counter")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("script", COUNTING_SERVICE)));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        JsonObject first = awaitService(nodes.getFirst(), s -> "RUNNING".equals(s.getString("status")));
        String firstJob = first.getString("currentJobId");
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (countOf(nodes.getFirst(), firstJob) < 5) {
            assertThat(System.nanoTime()).as("service counting").isLessThan(deadline);
            Thread.sleep(100);
        }

        RunningNode victim = killNodeRunning(firstJob);
        RunningNode survivor = survivorOtherThan(victim);
        JsonObject restarted = awaitService(survivor, s -> "RUNNING".equals(s.getString("status")) && s.getInteger("runs") >= 2);

        JsonObject secondJob = get(survivor, "/v1/jobs/" + restarted.getString("currentJobId")).bodyAsJsonObject();
        assertThat(secondJob.getString("node")).isNotEqualTo(victim.nodeId());
        assertThat(get(survivor, "/v1/jobs/" + firstJob).bodyAsJsonObject().getString("status")).isEqualTo("ORPHANED");
        assertThat(restarted.getJsonObject("lastExit").getString("status")).isEqualTo("ORPHANED");
        int carried = countOf(survivor, secondJob.getString("id"));
        assertThat(carried).as("the new run continued from the old run's count").isGreaterThanOrEqualTo(5);
    }

    private int countOf(RunningNode node, String jobId) throws Exception {
        Object checkpoint = get(node, "/v1/jobs/" + jobId + "/steps").bodyAsJsonObject().getValue("checkpoint");
        return checkpoint == null ? 0 : ((JsonObject) checkpoint).getInteger("count");
    }

    private JsonObject awaitService(RunningNode node, java.util.function.Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = get(node, "/v1/services/counter").bodyAsJsonObject();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Service never reached the expected state: " + last);
    }

    private String submitAndAwaitStep(String script, String onOrphan, String step) throws Exception {
        HttpResponse<Buffer> response = await(client.post(nodes.getFirst().httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", script).put("onOrphan", onOrphan).put("timeoutMs", 30_000)));
        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(202);
        String id = response.bodyAsJsonObject().getString("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (get(nodes.getFirst(), "/v1/jobs/" + id + "/steps").bodyAsJsonObject().getJsonArray("steps").stream()
                .noneMatch(s -> ((JsonObject) s).getString("name").equals(step))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Step " + step + " never completed; job is " + get(nodes.getFirst(), "/v1/jobs/" + id).bodyAsString());
            }
            Thread.sleep(50);
        }
        return id;
    }

    private RunningNode killNodeRunning(String id) throws Exception {
        String nodeId = get(nodes.getFirst(), "/v1/jobs/" + id).bodyAsJsonObject().getString("node");
        RunningNode victim = nodes.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElseThrow();
        nodes.remove(victim);
        // Abrupt from the job's point of view: nothing records the attempt as finished.
        String stopError = victim.stop().toCompletionStage().toCompletableFuture()
                .handle((v, e) -> e == null ? null : e.toString()).get(60, TimeUnit.SECONDS);
        assertThat(stopError).as("stopping " + victim.nodeId()).isNull();
        RunningNode survivor = survivorOtherThan(victim);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonArray live = null;
        while (System.nanoTime() < deadline) {
            live = get(survivor, "/v1/nodes").bodyAsJsonObject().getJsonArray("nodes");
            if (live.stream().noneMatch(n -> ((JsonObject) n).getString("id").equals(victim.nodeId()))) {
                return victim;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(victim.nodeId() + " still listed as live 10 s after stopping: " + live);
    }

    private RunningNode survivorOtherThan(RunningNode victim) {
        return nodes.stream().filter(n -> n != victim).findFirst().orElseThrow();
    }

    private JsonObject awaitTerminal(RunningNode node, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject job = null;
        while (System.nanoTime() < deadline) {
            job = get(node, "/v1/jobs/" + id).bodyAsJsonObject();
            if (List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "ORPHANED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job never finished: " + job);
    }

    private HttpResponse<Buffer> get(RunningNode node, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
