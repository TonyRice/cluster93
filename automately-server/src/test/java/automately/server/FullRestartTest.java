package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cluster is the database (D-030): stop every node, start them again from their data directories, and
 * everything is still there: finished jobs, kv, domains, and the service, which is running again and
 * continues from its checkpoint.
 */
class FullRestartTest {

    private static final String TENANT = "acme";

    @TempDir
    Path data;

    private final List<RunningNode> nodes = new ArrayList<>();
    private Vertx clientVertx;
    private WebClient client;

    @AfterEach
    void stopAll() throws Exception {
        stopNodes();
        if (clientVertx != null) {
            await(clientVertx.close());
        }
    }

    private ServerConfig config(String nodeId) {
        return ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_ORPHAN_GRACE_MS", "400"),
                Map.entry("AUTOMATELY_DATA_DIR", data.resolve(nodeId).toString()),
                Map.entry("AUTOMATELY_DATA_MEMORY_MB", "64"),
                Map.entry("AUTOMATELY_BASELINE_ADJUST_MS", "2000"),
                Map.entry("AUTOMATELY_CLUSTER_SIZE", "3"),
                Map.entry("AUTOMATELY_CLUSTERED", "true"),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "21701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1")));
    }

    /** Nodes of a persistent cluster wait for each other on restart, so they're started in parallel, like a real cluster. */
    private void startNodes() throws Exception {
        List<CompletableFuture<RunningNode>> starting = new ArrayList<>();
        for (String id : List.of("node-a", "node-b", "node-c")) {
            starting.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return await(Automately.start(config(id)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (CompletableFuture<RunningNode> node : starting) {
            nodes.add(node.get(120, TimeUnit.SECONDS));
        }
        awaitJson("/v1/nodes", json -> json.getJsonArray("nodes").size() == 3);
    }

    private void stopNodes() throws Exception {
        List<CompletableFuture<Void>> stopping = nodes.stream()
                .map(node -> node.stop().toCompletionStage().toCompletableFuture().exceptionally(e -> null))
                .toList();
        for (CompletableFuture<Void> stop : stopping) {
            stop.get(60, TimeUnit.SECONDS);
        }
        nodes.clear();
    }

    @Test
    void everythingComesBackAfterEveryNodeRestarts() throws Exception {
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        startNodes();

        assertThat(put("/v1/services/counter", new JsonObject().put("script", ChaosTest.COUNTING_SERVICE)).statusCode()).isEqualTo(201);
        JsonObject running = awaitJson("/v1/services/counter", s -> "RUNNING".equals(s.getString("status")));
        String firstRun = running.getString("currentJobId");
        awaitJson("/v1/jobs/" + firstRun + "/steps", s -> s.getValue("checkpoint") != null
                && ((JsonObject) s.getValue("checkpoint")).getInteger("count") >= 5);
        String jobId = runJob("export default async () => { await kv.set('greeting', 'survives'); return 'stored'; }");
        assertThat(post("/v1/services/counter/domains", new JsonObject().put("domain", "shop.example.com")).statusCode()).isEqualTo(201);

        stopNodes();
        startNodes();

        assertThat(get("/v1/jobs/" + jobId).bodyAsJsonObject().getString("output")).isEqualTo("stored");
        JsonObject kvJob = get("/v1/jobs/" + runJob("export default () => kv.get('greeting')")).bodyAsJsonObject();
        assertThat(kvJob.getString("output")).isEqualTo("survives");
        assertThat(get("/v1/services/counter/domains").bodyAsJsonObject().getJsonArray("domains")).hasSize(1);

        JsonObject restarted = awaitJson("/v1/services/counter", s -> "RUNNING".equals(s.getString("status")) && s.getInteger("runs") >= 2);
        String secondRun = restarted.getString("currentJobId");
        assertThat(secondRun).isNotEqualTo(firstRun);
        JsonObject steps = awaitJson("/v1/jobs/" + secondRun + "/steps", s -> s.getValue("checkpoint") != null);
        assertThat(((JsonObject) steps.getValue("checkpoint")).getInteger("count")).as("continued from before the restart").isGreaterThanOrEqualTo(5);
    }

    private String runJob(String script) throws Exception {
        HttpResponse<Buffer> submitted = post("/v1/jobs", new JsonObject().put("script", script).put("timeoutMs", 10_000));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        String id = submitted.bodyAsJsonObject().getString("id");
        awaitJson("/v1/jobs/" + id, j -> "SUCCEEDED".equals(j.getString("status")));
        return id;
    }

    private HttpResponse<Buffer> get(String path) throws Exception {
        return await(client.get(nodes.getFirst().httpPort(), "localhost", path).putHeader("X-Automately-Tenant", TENANT).send());
    }

    private HttpResponse<Buffer> post(String path, JsonObject body) throws Exception {
        return await(client.post(nodes.getFirst().httpPort(), "localhost", path).putHeader("X-Automately-Tenant", TENANT).sendJsonObject(body));
    }

    private HttpResponse<Buffer> put(String path, JsonObject body) throws Exception {
        return await(client.put(nodes.getFirst().httpPort(), "localhost", path).putHeader("X-Automately-Tenant", TENANT).sendJsonObject(body));
    }

    private JsonObject awaitJson(String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<Buffer> response = get(path);
            if (response.statusCode() == 200) {
                last = response.bodyAsJsonObject();
                if (condition.test(last)) {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }
}
