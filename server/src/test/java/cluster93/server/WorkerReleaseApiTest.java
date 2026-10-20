package cluster93.server;

import cluster93.runner.protocol.frame.FrameCodec;
import cluster93.runner.protocol.message.HelloMessage;
import cluster93.runner.protocol.transport.uds.UdsClientTransport;
import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator's release surface (D-187): an admin can ask an idle connected worker to leave, and the answer says
 * what happened. The mechanism itself is exercised in the pool and the socket source; this is the HTTP contract —
 * who may call it, what a refusal looks like, and that the worker really leaves.
 */
class WorkerReleaseApiTest {

    private static RunningNode node;
    private static Vertx workerVertx;
    private static UdsClientTransport transport;
    private static NetSocket worker;
    private static Path dir;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("c93-worker-release");
        String socketPath = dir.resolve("workers").resolve("node.sock").toString();
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "releasenode"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_WORKER_MODE", "process"),
                Map.entry("C93_WORKER_DEFAULT_MODE", "javet"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                // No secret: this test is about the release route, and the node's warning is not the subject.
                Map.entry("C93_WORKER_SOCKET", socketPath)))));
        TestAuth.use(node);
        workerVertx = Vertx.vertx();
        transport = new UdsClientTransport(workerVertx);
        worker = await(transport.connect(socketPath));
        // A connection is not a worker until it says hello; this one claims no tenant, which is exactly the first
        // candidate a release considers.
        await(worker.write(FrameCodec.encode(new HelloMessage("javet", "2.0.0", 4242L, null, 64, 4).toFrame())));
    }

    @AfterAll
    static void stop() throws Exception {
        if (worker != null) {
            await(worker.close());
        }
        if (transport != null) {
            await(transport.close());
        }
        if (workerVertx != null) {
            await(workerVertx.close());
        }
        if (node != null) {
            await(node.stop());
        }
        deleteRecursively(dir);
    }

    @Test
    @DisplayName("An admin releases an idle connected worker; a tenant token may not, and a second release has nothing to do (D-187)")
    void onlyAnAdminReleasesAndTheWorkerLeaves() throws Exception {
        awaitUntil("the worker to connect", () -> scrape().contains("c93_workers_connected{node=\"releasenode\"} 1.0"));

        HttpResponse<String> forbidden = post("/v1/cluster/workers/release", "{}", TestAuth.bearer("releaseapi"));
        assertThat(forbidden.statusCode()).as(forbidden.body()).isEqualTo(403);

        HttpResponse<String> released = post("/v1/cluster/workers/release", "{\"reason\":\"test release\"}", TestAuth.ADMIN);
        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
        JsonObject body = new JsonObject(released.body()).getJsonObject("released");
        assertThat(body.getString("workerId")).startsWith("worker-c");
        assertThat(body.getString("reason")).isEqualTo("test release");
        assertThat(body.getString("trigger")).isEqualTo("manual");

        // It leaves: the node closes the socket after the grace period even though this test's "worker" does not exit.
        awaitUntil("the released worker to leave", () -> scrape().contains("c93_workers_connected{node=\"releasenode\"} 0.0"));
        assertThat(scrape()).as("the release counter names the trigger").contains("c93_workers_released_total{node=\"releasenode\",trigger=\"manual\"} 1.0");

        HttpResponse<String> again = post("/v1/cluster/workers/release", "{}", TestAuth.ADMIN);
        assertThat(again.statusCode()).as("nothing is idle any more").isEqualTo(409);
        assertThat(new JsonObject(again.body()).getJsonObject("error").getString("code")).isEqualTo("no_idle_worker");

        HttpResponse<String> unknown = post("/v1/cluster/workers/release", "{\"workerId\":\"worker-c999\"}", TestAuth.ADMIN);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(new JsonObject(unknown.body()).getJsonObject("error").getString("code")).isEqualTo("worker_unknown");
    }

    private static HttpResponse<String> post(String path, String body, String authorization) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + node.httpPort() + path))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String scrape() {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + node.metricsPort() + "/metrics"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException("Could not scrape this node's metrics: " + e.getMessage(), e);
        }
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
