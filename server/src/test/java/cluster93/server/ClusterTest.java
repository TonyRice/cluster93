package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Two real nodes in one JVM: Hazelcast cluster, V8 isolates, HTTP on both. */
class ClusterTest {

    private static final String TENANT = "tenant-a";

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
        awaitLiveNodes(2);
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (nodeB != null) { await(nodeB.stop()); }
        if (nodeA != null) { await(nodeA.stop()); }
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
                Map.entry("C93_CAPACITY", "1"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", "15701"),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1"))));
    }

    @Test
    void bothNodesSeeEachOther() throws Exception {
        JsonArray nodes = get(nodeB, "/v1/nodes").bodyAsJsonObject().getJsonArray("nodes");

        assertThat(nodes.stream().map(n -> ((JsonObject) n).getString("id"))).containsExactly("node-a", "node-b");
    }

    @Test
    void spreadsConcurrentJobsAcrossNodesAndAnyNodeCanReportThem() throws Exception {
        String busyScript = """
                export default (input) => {
                  const until = Date.now() + 400;
                  while (Date.now() < until) {}
                  return { job: input.n };
                }
                """;
        String first = submit(nodeA, busyScript, new JsonObject().put("n", 1)).getString("id");
        String second = submit(nodeA, busyScript, new JsonObject().put("n", 2)).getString("id");

        JsonObject firstDone = awaitTerminal(nodeB, first);
        JsonObject secondDone = awaitTerminal(nodeB, second);

        assertThat(firstDone.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(secondDone.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(secondDone.getJsonObject("output")).isEqualTo(new JsonObject().put("job", 2));
        assertThat(Set.of(firstDone.getString("node"), secondDone.getString("node"))).containsExactlyInAnyOrder("node-a", "node-b");
    }

    @Test
    void streamsEventsFromAnyNode() throws Exception {
        String id = submit(nodeA, """
                export default async () => {
                  const until = Date.now() + 300;
                  while (Date.now() < until) {}
                  console.log('working');
                  return 'done';
                }
                """, new JsonObject()).getString("id");

        String stream = await(client.get(nodeB.httpPort(), "localhost", "/v1/jobs/" + id + "/events")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .timeout(10_000)
                .send()).bodyAsString();

        assertThat(stream).contains("event: log", "\"line\":\"working\"", "event: end", "\"status\":\"SUCCEEDED\"");
    }

    @Test
    void replaysEventsForAFinishedJob() throws Exception {
        String id = submit(nodeA, "export default () => { console.log('quick'); return 1 }", new JsonObject()).getString("id");
        awaitTerminal(nodeA, id);

        String stream = await(client.get(nodeB.httpPort(), "localhost", "/v1/jobs/" + id + "/events")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .timeout(10_000)
                .send()).bodyAsString();

        assertThat(stream).contains("\"line\":\"quick\"", "event: end");
    }

    @Test
    void hostileScriptIsKilledAndTheClusterKeepsWorking() throws Exception {
        String loop = submit(nodeA, "export default () => { while (true) {} }", new JsonObject(), 300).getString("id");

        assertThat(awaitTerminal(nodeA, loop).getString("status")).isEqualTo("TIMED_OUT");

        String next = submit(nodeA, "export default () => 'fine'", new JsonObject()).getString("id");
        assertThat(awaitTerminal(nodeA, next).getString("output")).isEqualTo("fine");
    }

    @Test
    void tenantsCannotSeeEachOthersJobs() throws Exception {
        String id = submit(nodeA, "export default () => 1", new JsonObject()).getString("id");

        HttpResponse<Buffer> response = await(client.get(nodeA.httpPort(), "localhost", "/v1/jobs/" + id)
                .putHeader("Authorization", TestAuth.bearer("tenant-b"))
                .send());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void aPersonalTokenFromOneNodeFollowsItsUserOnTheOther() throws Exception {
        TestAuth.bearer("people");
        HttpResponse<Buffer> invited = await(client.post(nodeA.httpPort(), "localhost", "/v1/tenants/people/invites")
                .putHeader("Authorization", TestAuth.ADMIN)
                .sendJsonObject(new JsonObject().put("roles", new JsonArray().add("developer")).put("user", "dana")));
        assertThat(invited.statusCode()).as(invited.bodyAsString()).isEqualTo(201);
        HttpResponse<Buffer> redeemed = await(client.post(nodeB.httpPort(), "localhost", "/v1/invites/redeem")
                .sendJsonObject(new JsonObject().put("code", invited.bodyAsJsonObject().getString("code"))));
        assertThat(redeemed.statusCode()).as(redeemed.bodyAsString()).isEqualTo(201);
        String dana = "Bearer " + redeemed.bodyAsJsonObject().getJsonObject("token").getString("token");

        assertThat(await(client.post(nodeA.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", dana)
                .sendJsonObject(new JsonObject().put("script", "export default () => 1"))).statusCode()).isEqualTo(202);

        // Disabled through node B; refused on node A at once.
        assertThat(await(client.patch(nodeB.httpPort(), "localhost", "/v1/users/dana").putHeader("Authorization", TestAuth.ADMIN)
                .sendJsonObject(new JsonObject().put("disabled", true))).statusCode()).isEqualTo(200);
        assertThat(await(client.get(nodeA.httpPort(), "localhost", "/v1/whoami").putHeader("Authorization", dana).send()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        HttpResponse<Buffer> response = await(client.get(nodeA.httpPort(), "localhost", "/v1/jobs").send());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("unauthorized");
    }

    @Test
    void rejectsInvalidSubmissions() throws Exception {
        for (JsonObject body : List.of(
                new JsonObject(),
                new JsonObject().put("script", ""),
                new JsonObject().put("script", "export default () => 1").put("input", "nope"),
                new JsonObject().put("script", "export default () => 1").put("timeoutMs", 10_000_000))) {
            HttpResponse<Buffer> response = await(client.post(nodeA.httpPort(), "localhost", "/v1/jobs")
                    .putHeader("Authorization", TestAuth.bearer(TENANT))
                    .sendJsonObject(body));

            assertThat(response.statusCode()).as(body.encode()).isEqualTo(400);
        }
    }

    private static JsonObject submit(RunningNode node, String script, JsonObject input) throws Exception {
        return submit(node, script, input, 10_000);
    }

    private static JsonObject submit(RunningNode node, String script, JsonObject input, long timeoutMs) throws Exception {
        HttpResponse<Buffer> response = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", script).put("input", input).put("timeoutMs", timeoutMs)));
        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(202);
        return response.bodyAsJsonObject();
    }

    private static HttpResponse<Buffer> get(RunningNode node, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private static JsonObject awaitTerminal(RunningNode node, String id) throws Exception {
        Set<String> terminal = new HashSet<>(List.of("SUCCEEDED", "FAILED", "TIMED_OUT"));
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonObject job = null;
        while (System.nanoTime() < deadline) {
            job = get(node, "/v1/jobs/" + id).bodyAsJsonObject();
            if (terminal.contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Job never finished: " + job);
    }

    private static void awaitLiveNodes(int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (get(nodeA, "/v1/nodes").bodyAsJsonObject().getJsonArray("nodes").size() == count) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Cluster never reached " + count + " nodes");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
