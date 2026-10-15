package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node-wide worker cap (D-156). The node must never run more worker processes than {@code C93_MAX_WORKERS}, and
 * work that cannot get a worker because the cap is reached must <b>wait</b> for one — never fail, and never deadlock
 * behind an idle worker that is held by a different tenant.
 */
class WorkerCapTest {

    private static final String TENANT = "capped";
    /** Names this node's worker binary uniquely, so its workers can be counted without other tests' workers. */
    private static final String MARKER = "c93-capped-" + java.util.UUID.randomUUID();

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        java.nio.file.Path binary = WorkerProcesses.markedBinary(MARKER);
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null,
                "no native worker binary present; cannot count this node's worker processes");
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "capped"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_WORKER_BINARY", binary.toString()),
                // Two concurrent jobs wanted, but the whole node may run exactly one worker: the second job has to
                // wait for the first's worker rather than getting one of its own.
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_WORKERS", "1"),
                Map.entry("C93_MAX_JOBS_PER_TENANT", "2"),
                Map.entry("C93_MAX_JOBS_PER_WORKER", "1"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
    }

    @Test
    @DisplayName("The node runs no more workers than C93_MAX_WORKERS, and a waiting job still completes (D-156)")
    void theNodeWideWorkerCapHoldsAndWorkWaits() throws Exception {
        String script = "export default async (input, ctx) => { await ctx.sleep(1500); return `done-${input.n}`; };";
        String first = submit(new JsonObject().put("script", script).put("input", new JsonObject().put("n", 1)));
        String second = submit(new JsonObject().put("script", script).put("input", new JsonObject().put("n", 2)));

        int peak = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            peak = Math.max(peak, WorkerProcesses.pids(MARKER).size());
            if (terminal(state(first)) && terminal(state(second))) {
                break;
            }
            Thread.sleep(50);
        }

        JsonObject a = job(first);
        JsonObject b = job(second);
        assertThat(a.getString("status")).as(a.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(b.getString("status")).as(b.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(peak).as("peak worker processes while two jobs wanted a worker (C93_MAX_WORKERS=1)").isEqualTo(1);
    }

    // --- helpers ---

    private static boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private static String state(String id) throws Exception {
        return job(id).getString("status");
    }

    private static String submit(JsonObject body) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(body.copy().put("timeoutMs", 30_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static JsonObject job(String id) throws Exception {
        HttpResponse<Buffer> response = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(200);
        return response.bodyAsJsonObject();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
