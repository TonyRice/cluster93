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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pivot, observed at the process level (D-155, D-162). With {@code C93_MAX_JOBS_PER_WORKER} jobs per worker and
 * a per-tenant cap of the same number, a tenant's pool is allowed exactly <b>one</b> worker
 * ({@code ceil(maxJobsPerTenant / maxJobsPerWorker)} = 1) — so any jobs running at once <em>must</em> be running on
 * that one worker process. This asserts that directly, by counting real worker processes in {@code /proc} while the
 * jobs are in flight.
 */
class OneWorkerManyJobsTest {

    private static final String TENANT = "oneworker";
    private static final int JOBS = 4;
    /** Names this node's worker binary uniquely, so its workers can be counted without other tests' workers. */
    private static final String MARKER = "c93-oneworker-" + java.util.UUID.randomUUID();

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        java.nio.file.Path binary = WorkerProcesses.markedBinary(MARKER);
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null,
                "no native worker binary present; cannot count this node's worker processes");
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "oneworker"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_WORKER_BINARY", binary.toString()),
                // capacity 4, and 4 jobs per worker: the tenant's pool is allowed ceil(4/4) = 1 worker.
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_JOBS_PER_TENANT", "4"),
                Map.entry("C93_MAX_JOBS_PER_WORKER", "4"),
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
    @DisplayName("Four jobs run at once on one worker process, and all four finish (D-155)")
    void oneWorkerRunsSeveralJobsAtOnce() throws Exception {
        // Each job sleeps long enough that all of them are provably in flight together.
        String script = "export default async (input, ctx) => { await ctx.sleep(6000); return `done-${input.n}`; };";

        List<String> ids = new ArrayList<>();
        for (int n = 1; n <= JOBS; n++) {
            ids.add(submit(new JsonObject().put("script", script).put("input", new JsonObject().put("n", n))));
        }

        awaitAllRunning(ids);

        List<Long> workers = WorkerProcesses.pids(MARKER);
        assertThat(workers)
                .as("worker processes while %d jobs are RUNNING together (the tenant is allowed exactly one)", JOBS)
                .hasSize(1);
        System.out.println("[OneWorkerManyJobsTest] " + JOBS + " jobs RUNNING on worker pid " + workers.get(0));

        for (int n = 1; n <= JOBS; n++) {
            JsonObject job = awaitTerminal(ids.get(n - 1));
            assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
            assertThat(job.getString("output")).isEqualTo("done-" + n);
        }
    }

    // --- helpers ---

    private static String submit(JsonObject body) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(body.copy().put("timeoutMs", 30_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static void awaitAllRunning(List<String> ids) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> states = ids.stream().map(OneWorkerManyJobsTest::stateOf).toList();
            if (states.stream().allMatch("RUNNING"::equals)) {
                return;
            }
            assertThat(states).as("a job went terminal before all were RUNNING").doesNotContain("FAILED", "CANCELLED");
            Thread.sleep(50);
        }
        throw new AssertionError("all jobs did not reach RUNNING together: "
                + ids.stream().map(OneWorkerManyJobsTest::stateOf).toList());
    }

    private static String stateOf(String id) {
        try {
            return job(TENANT, id).getString("status");
        } catch (Exception e) {
            throw new IllegalStateException("could not read job " + id, e);
        }
    }

    private static JsonObject job(String tenant, String id) throws Exception {
        HttpResponse<Buffer> response = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id)
                .putHeader("Authorization", TestAuth.bearer(tenant)).send());
        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(200);
        return response.bodyAsJsonObject();
    }

    private static JsonObject awaitTerminal(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject job;
        do {
            Thread.sleep(50);
            job = job(TENANT, id);
        } while (List.of("QUEUED", "RUNNING").contains(job.getString("status")) && System.nanoTime() < deadline);
        return job;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
