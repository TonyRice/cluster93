package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Services over HTTP on a real two-node cluster with V8 isolates (docs/scripting/services.md). */
class ServicesTest {

    private static final String TENANT = "svc-tenant";

    private static final String TICKER = """
            export default async (input, ctx) => {
              const id = setInterval(() => console.log('tick ' + ctx.service.run), input.everyMs);
              const { reason } = await ctx.stopRequested;
              clearInterval(id);
              console.log('stopping: ' + reason);
              return 'clean';
            }
            """;

    private static RunningNode nodeA;
    private static RunningNode nodeB;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void startCluster() throws Exception {
        nodeA = await(Cluster93.start(config("node-a")));
        TestAuth.use(nodeA);
        nodeB = await(Cluster93.start(config("node-b")));
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (await(client.get(nodeA.httpPort(), "localhost", "/v1/nodes").putHeader("Authorization", TestAuth.ADMIN).send()).bodyAsJsonObject().getJsonArray("nodes").size() < 2) {
            assertThat(System.nanoTime()).as("cluster formed").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (nodeB != null) { await(nodeB.stop()); }
        if (nodeA != null) { await(nodeA.stop()); }
    }

    @AfterEach
    void deleteServices() throws Exception {
        for (Object service : get("/v1/services").bodyAsJsonObject().getJsonArray("services")) {
            await(client.delete(nodeA.httpPort(), "localhost", "/v1/services/" + ((JsonObject) service).getString("name"))
                    .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        }
    }

    private static ServerConfig config(String nodeId) {
        return ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("C93_NODE_ID", nodeId),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                Map.entry("C93_STOP_GRACE_MS", "1000"),
                Map.entry("C93_MAX_SERVICES_PER_TENANT", "3"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", "19701"),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1"))));
    }

    @Test
    void aTimerServiceRunsUntilStoppedAndCleansUp() throws Exception {
        HttpResponse<Buffer> created = put("ticker", new JsonObject().put("script", TICKER).put("config", new JsonObject().put("everyMs", 50)));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);

        JsonObject running = awaitService("ticker", s -> s.getString("status").equals("RUNNING"));
        String jobId = running.getString("currentJobId");
        Thread.sleep(400);
        JsonObject stopped = post("/v1/services/ticker/stop").bodyAsJsonObject();
        assertThat(stopped.getBoolean("enabled")).isFalse();

        JsonObject after = awaitService("ticker", s -> s.getString("status").equals("STOPPED") && s.getValue("currentJobId") == null);
        JsonObject job = get("/v1/jobs/" + jobId).bodyAsJsonObject();
        assertThat(job.getString("status")).isEqualTo("CANCELLED");
        assertThat(job.getString("output")).isEqualTo("clean");
        assertThat(job.getJsonArray("logs").getList()).contains("tick 1", "stopping: service ticker stopped");
        assertThat(after.getJsonObject("lastExit").getString("status")).isEqualTo("CANCELLED");
        assertThat(after.getInteger("consecutiveFailures")).isZero();
    }

    @Test
    void aCrashingServiceIsRestartedWithBackoffAndKeepsItsCheckpoint() throws Exception {
        put("crasher", new JsonObject().put("script", """
                export default async (input, ctx) => {
                  const count = (ctx.checkpoint?.count ?? 0) + 1;
                  await ctx.saveCheckpoint({ count });
                  console.log(`run ${ctx.service.run} sees count ${count}`);
                  await ctx.sleep(50);
                  throw new Error('crash ' + count);
                }
                """));

        JsonObject after = awaitService("crasher", s -> s.getInteger("runs") >= 3 && s.getJsonObject("lastExit") != null
                && s.getJsonObject("lastExit").getString("error").contains("crash 3"));

        assertThat(after.getInteger("consecutiveFailures")).isGreaterThanOrEqualTo(3);
        String lastRun = after.getJsonObject("lastExit").getString("jobId");
        assertThat(get("/v1/jobs/" + lastRun).bodyAsJsonObject().getJsonArray("logs").getList()).contains("run 3 sees count 3");
    }

    @Test
    void updatingTheScriptRollsToANewRevision() throws Exception {
        put("roller", new JsonObject().put("script", TICKER).put("config", new JsonObject().put("everyMs", 100)));
        String first = awaitService("roller", s -> s.getString("status").equals("RUNNING")).getString("currentJobId");

        JsonObject updated = put("roller", new JsonObject().put("script", TICKER).put("config", new JsonObject().put("everyMs", 30)))
                .bodyAsJsonObject();
        assertThat(updated.getInteger("revision")).isEqualTo(2);

        JsonObject rolled = awaitService("roller", s -> s.getString("status").equals("RUNNING") && s.getInteger("currentRevision") == 2);
        assertThat(rolled.getString("currentJobId")).isNotEqualTo(first);
        assertThat(get("/v1/jobs/" + first).bodyAsJsonObject().getString("status")).isEqualTo("CANCELLED");
    }

    @Test
    void limitsEnabledServicesPerTenant() throws Exception {
        for (String name : new String[]{"one", "two", "three"}) {
            assertThat(put(name, new JsonObject().put("script", TICKER).put("config", new JsonObject().put("everyMs", 1000))).statusCode())
                    .isEqualTo(201);
        }

        HttpResponse<Buffer> fourth = put("four", new JsonObject().put("script", TICKER));
        assertThat(fourth.statusCode()).isEqualTo(409);
        assertThat(fourth.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("limit_reached");
        assertThat(put("four", new JsonObject().put("script", TICKER).put("enabled", false)).statusCode())
                .as("a disabled service doesn't count").isEqualTo(201);
    }

    @Test
    void rejectsInvalidDefinitions() throws Exception {
        assertThat(put("Bad Name", new JsonObject().put("script", TICKER)).statusCode()).isEqualTo(400);
        assertThat(put("ok", new JsonObject()).statusCode()).isEqualTo(400);
        assertThat(put("ok", new JsonObject().put("script", TICKER).put("restart", "sometimes")).statusCode()).isEqualTo(400);
        assertThat(put("ok", new JsonObject().put("script", TICKER).put("config", "nope")).statusCode()).isEqualTo(400);
        assertThat(get("/v1/services/missing").statusCode()).isEqualTo(404);
    }

    @Test
    void cancellingAJobLetsItCleanUp() throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(nodeB.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("timeoutMs", 30_000).put("script", """
                        export default async (input, ctx) => {
                          await ctx.stopRequested;
                          return 'tidied up';
                        }
                        """)));
        String id = submitted.bodyAsJsonObject().getString("id");
        awaitJob(id, j -> j.getString("status").equals("RUNNING"));

        assertThat(post("/v1/jobs/" + id + "/cancel").statusCode()).isEqualTo(202);
        JsonObject done = awaitJob(id, j -> j.getString("status").equals("CANCELLED"));

        assertThat(done.getString("output")).isEqualTo("tidied up");
        assertThat(post("/v1/jobs/" + id + "/cancel").statusCode()).as("already finished").isEqualTo(409);
    }

    @Test
    void connectingToARunningJobFirstReplaysWhatItLoggedSoFar() throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(nodeB.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("timeoutMs", 30_000).put("script", """
                        export default async (input, ctx) => {
                          console.log('one');
                          console.log('two');
                          await ctx.stopRequested;
                          return 'done';
                        }
                        """)));
        String id = submitted.bodyAsJsonObject().getString("id");
        awaitJob(id, j -> j.getString("status").equals("RUNNING"));
        Thread.sleep(300);

        // Connect through node A, whichever node runs it: the earlier lines come first, once each, then live events.
        java.net.http.HttpResponse<java.io.InputStream> events = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + nodeA.httpPort() + "/v1/jobs/" + id + "/events"))
                        .header("Authorization", TestAuth.bearer(TENANT)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(events.body()))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("data:")) {
                    JsonObject event = new JsonObject(line.substring(5).trim());
                    lines.add(event.getString("type") + (event.containsKey("line") ? ":" + event.getString("line") : ""));
                    if (lines.size() == 2) {
                        assertThat(post("/v1/jobs/" + id + "/cancel").statusCode()).isEqualTo(202);
                    }
                    if ("end".equals(event.getString("type"))) {
                        break;
                    }
                }
            }
        }
        assertThat(lines).containsExactly("log:one", "log:two", "end");
    }

    // --- helpers ---

    private static HttpResponse<Buffer> put(String name, JsonObject body) throws Exception {
        return await(client.put(nodeA.httpPort(), "localhost", "/v1/services/" + name.replace(" ", "%20"))
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(body));
    }

    private static HttpResponse<Buffer> post(String path) throws Exception {
        return await(client.post(nodeB.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private static HttpResponse<Buffer> get(String path) throws Exception {
        return await(client.get(nodeA.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private static JsonObject awaitService(String name, Predicate<JsonObject> condition) throws Exception {
        return awaitJson("/v1/services/" + name, condition);
    }

    private static JsonObject awaitJob(String id, Predicate<JsonObject> condition) throws Exception {
        return awaitJson("/v1/jobs/" + id, condition);
    }

    private static JsonObject awaitJson(String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = get(path).bodyAsJsonObject();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
