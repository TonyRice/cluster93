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
 * A job that is <b>queued</b> because a worker is momentarily full must run when one of that worker's jobs frees a
 * slot (D-155). The pool serves its own pending queue when a job finishes, not only when a whole worker is retired —
 * otherwise a queued job waits for a worker that never goes idle (a tenant's worker is often kept busy by siblings),
 * which is a hang.
 */
class QueuedJobRunsWhenASlotFreesTest {

    private static final String TENANT = "queued";

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "queued"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                // One worker with TWO job slots (maxJobsPerTenant / maxJobsPerWorker = 2/2 -> one worker), but the
                // node admits three jobs at once. The third must queue on the pool and run when a slot frees.
                Map.entry("C93_CAPACITY", "3"),
                Map.entry("C93_MAX_JOBS_PER_TENANT", "2"),
                Map.entry("C93_MAX_JOBS_PER_WORKER", "2"),
                Map.entry("C93_MAX_WORKERS", "3"),
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
    @DisplayName("A job queued behind a full worker runs when a sibling frees its slot, while another still runs (D-155)")
    void aQueuedJobRunsWhenASlotFrees() throws Exception {
        // One long job holds a slot the whole test, so the worker never goes idle: the third job can only run if a
        // freed slot is handed out while a sibling is still occupying the worker.
        String longJob = "export default async (input, ctx) => { await ctx.sleep(60000); return 'long'; };";
        String shortJob = "export default async (input, ctx) => { await ctx.sleep(500); return `done-${input.n}`; };";

        String first = submit(new JsonObject().put("script", longJob).put("timeoutMs", 120_000)); // holds slot 1
        String second = submit(new JsonObject().put("script", shortJob).put("input", new JsonObject().put("n", 2))
                .put("timeoutMs", 60_000)); // slot 2
        String third = submit(new JsonObject().put("script", shortJob).put("input", new JsonObject().put("n", 3))
                .put("timeoutMs", 60_000)); // queues: the worker is full

        // Wait for the queued job to finish. No fixed deadline: what matters is that it finishes *before* the long
        // job — if the queue is only drained on worker retirement, it cannot, and it waits the long job out.
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && !terminal(state(third))) {
            Thread.sleep(50);
        }

        JsonObject two = journal(second);
        JsonObject three = journal(third);
        assertThat(two.getString("status")).as(two.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(three.getString("status")).as(three.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(state(first))
                .as("the long job must still be running: the queued job ran on the slot its sibling freed")
                .isEqualTo("RUNNING");
    }

    // --- helpers ---

    private static boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private static String state(String id) throws Exception {
        return journal(id).getString("status");
    }

    private static String submit(JsonObject body) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(body.copy().put("timeoutMs", 45_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static JsonObject journal(String id) throws Exception {
        HttpResponse<Buffer> response = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(200);
        return response.bodyAsJsonObject();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
