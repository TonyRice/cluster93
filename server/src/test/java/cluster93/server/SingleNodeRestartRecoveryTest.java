package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single-node master with durable Ignite storage: crash the node mid-flight and start a fresh instance
 * for the same node id and data directory. Running jobs and services come back at once, rather than waiting
 * out the orphan grace that protects a node that only looked dead (docs/design/orphaned-jobs.md).
 */
class SingleNodeRestartRecoveryTest {

    private static final String TENANT = "recovery";
    private static final String NODE_ID = "node-1";
    /** Longer than any wait in this test: the fast path must bypass it, not merely beat it. */
    private static final long ORPHAN_GRACE_MS = 30_000;

    @TempDir
    Path data;

    private final List<RunningNode> nodes = new ArrayList<>();
    private Vertx clientVertx;
    private WebClient client;

    @AfterEach
    void stopAll() throws Exception {
        for (RunningNode node : nodes) {
            node.stop().toCompletionStage().toCompletableFuture().handle((v, e) -> null).get(60, TimeUnit.SECONDS);
        }
        nodes.clear();
        if (clientVertx != null) {
            await(clientVertx.close());
        }
    }

    /** The same node id and data directory on every start, so the cluster recognises the restart. */
    private ServerConfig config() {
        return ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("C93_NODE_ID", NODE_ID),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                Map.entry("C93_ORPHAN_GRACE_MS", String.valueOf(ORPHAN_GRACE_MS)),
                Map.entry("C93_DATA_DIR", data.resolve("node").toString()),
                Map.entry("C93_DATA_MEMORY_MB", "128"),
                Map.entry("C93_BASELINE_ADJUST_MS", "2000"),
                Map.entry("C93_CLUSTER_SIZE", "1"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", "20701"),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1"))));
    }

    @Test
    void aRunningJobAndServiceRecoverAtOnceAfterARestart() throws Exception {
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        RunningNode node1 = start();

        // A long-running job that only declares how it should be recovered. Nothing in the request says so.
        String jobScript = """
                export const config = { onOrphan: 'retry', maxAttempts: 3 };
                export default async (input, ctx) => {
                  if (ctx.attempt === 1) {
                    await ctx.sleep(60000);
                  }
                  return { attempt: ctx.attempt };
                };
                """;
        HttpResponse<Buffer> submitted = await(client.post(node1.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", jobScript).put("timeoutMs", 60_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        JsonObject submittedJob = submitted.bodyAsJsonObject();
        assertThat(submittedJob.getString("onOrphan")).as("the in-script policy was honoured").isEqualTo("retry");
        assertThat(submittedJob.getInteger("maxAttempts")).isEqualTo(3);
        String jobId = submittedJob.getString("id");
        awaitJson(node1, "/v1/jobs/" + jobId, j -> "RUNNING".equals(j.getString("status")), Duration.ofSeconds(30));

        // A service that stays up until asked to stop.
        String serviceScript = """
                export default async (input, ctx) => {
                  while (true) {
                    await Promise.race([ctx.sleep(100), ctx.stopRequested]);
                  }
                };
                """;
        HttpResponse<Buffer> created = await(client.put(node1.httpPort(), "localhost", "/v1/services/web")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", serviceScript)));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        JsonObject firstRun = awaitJson(node1, "/v1/services/web", s -> "RUNNING".equals(s.getString("status")), Duration.ofSeconds(30));
        assertThat(firstRun.getString("currentJobId")).isNotNull();

        // Crash: the node stops without recording its running attempts as finished.
        await(node1.stop());
        nodes.remove(node1);

        RunningNode node2 = start();

        long started = System.nanoTime();
        JsonObject recovered = awaitJson(node2, "/v1/jobs/" + jobId, j -> "SUCCEEDED".equals(j.getString("status")), Duration.ofSeconds(15));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(recovered.getInteger("attempt")).as("a new attempt under the new instance").isEqualTo(2);
        assertThat(recovered.getJsonObject("output")).isEqualTo(new JsonObject().put("attempt", 2));
        assertThat(elapsedMs).as("recovered without waiting out the %d ms grace", ORPHAN_GRACE_MS).isLessThan(15_000);

        JsonObject restarted = awaitJson(node2, "/v1/services/web",
                s -> "RUNNING".equals(s.getString("status")) && s.getInteger("runs") >= 2, Duration.ofSeconds(15));
        assertThat(restarted.getString("currentJobId")).as("the service resumed serving").isNotEqualTo(firstRun.getString("currentJobId"));
    }

    private RunningNode start() throws Exception {
        RunningNode node = await(Cluster93.start(config()));
        nodes.add(node);
        TestAuth.use(node);
        return node;
    }

    private JsonObject awaitJson(RunningNode node, String path, Predicate<JsonObject> condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<Buffer> response = await(client.get(node.httpPort(), "localhost", path)
                    .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
            if (response.statusCode() == 200) {
                last = response.bodyAsJsonObject();
                if (condition.test(last)) {
                    return last;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }
}
