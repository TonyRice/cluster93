package cluster93.server;

import cluster93.runner.protocol.client.WorkerClientConnection;
import cluster93.runner.protocol.client.WorkerDaemon;
import cluster93.runner.protocol.transport.uds.UdsClientTransport;
import cluster93.runtime.javet.JavetScriptRuntime;
import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One worker container serving <b>two</b> tenants in turn (D-172): the node binds the connection to the first tenant,
 * and when its work is done — nothing running on it — hands the same worker to the second tenant instead of letting
 * the deployment's one container sit idle while a second process is started.
 *
 * <p>What is re-bound is the connection, not the isolation: a worker serves one tenant at a time, a run only starts
 * once the worker is bound to the tenant that run belongs to, and each run gets a fresh engine context. This test
 * asserts all three, from the outside.
 */
class WorkerRebindTest {

    private static final String TENANT_A = "rebind-a";
    private static final String TENANT_B = "rebind-b";
    private static final String SECRET = "a-pre-shared-worker-secret";
    private static final String NODE = "rebindnode";

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static Vertx workerVertx;
    private static UdsClientTransport workerTransport;
    // Held for the test's lifetime on purpose: a worker is a live connection, and nothing else references the
    // daemon or its engine once it has said hello — a collected client would close its socket.
    private static WorkerDaemon workerDaemon;
    private static WorkerClientConnection workerConnection;
    private static JavetScriptRuntime workerEngine;
    private static NetSocket workerSocket;
    private static Path dir;
    private static String socketPath;

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("c93-worker-rebind-node");
        socketPath = dir.resolve("workers").resolve("node.sock").toString();
        Path secretFile = dir.resolve("worker-secret");
        Files.writeString(secretFile, SECRET);
        Files.setPosixFilePermissions(secretFile, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));

        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", NODE),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_WORKER_MODE", "process"),
                Map.entry("C93_WORKER_DEFAULT_MODE", "javet"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_JOBS_PER_WORKER", "4"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                // The deployment's one container connects here; the node creates nothing.
                Map.entry("C93_WORKER_SOCKET", socketPath),
                Map.entry("C93_WORKER_SECRET_FILE", secretFile.toString())))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        // The worker goes first, so the node sees a worker leave rather than the other way round (which a real worker
        // process would not log as a protocol error).
        if (workerSocket != null) { await(workerSocket.close()); }
        if (workerTransport != null) { await(workerTransport.close()); }
        if (workerVertx != null) { await(workerVertx.close()); }
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
        deleteRecursively(dir);
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

    @Test
    @DisplayName("An idle worker is re-bound to another tenant on the same connection, with nothing of the first left on it (D-172)")
    void anIdleWorkerServesASecondTenant() throws Exception {
        startWorker(SECRET);
        awaitUntil("the connected worker to appear in this node's metrics",
                () -> scrape().contains("c93_workers_connected{node=\"" + NODE + "\"} 1.0"));

        // Tenant A runs a job, and leaves a marker in its engine's globals.
        JsonObject first = awaitTerminal(TENANT_A, submit(TENANT_A, """
                export default async () => {
                  globalThis.leakedFromA = 'acme-only';
                  return { ranFor: 'a' };
                };
                """));
        assertThat(first.getString("status")).as(first.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(workerDaemon.boundTenantId()).as("the connection serves the tenant that submitted").isEqualTo(TENANT_A);

        // Its worker is idle now, and held warm — the state in which it can be handed to another tenant.
        awaitUntil("tenant A's worker to go idle and warm",
                () -> scrape().contains("c93_workers_warm{node=\"" + NODE + "\"} 1.0"));

        // Tenant B runs a job: it must be served by that same connection, re-bound, in a fresh context.
        JsonObject second = awaitTerminal(TENANT_B, submit(TENANT_B, """
                export default async () => ({ leakedFromA: typeof globalThis.leakedFromA });
                """));
        assertThat(second.getString("status")).as(second.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(second.getJsonObject("output").getString("leakedFromA"))
                .as("the second tenant's run starts from a context with nothing of the first tenant's on it")
                .isEqualTo("undefined");

        // The shape of it: no process was started for B, and the one connection is the second tenant's now.
        assertThat(scrape()).contains("c93_workers_connected{node=\"" + NODE + "\"} 1.0");
        assertThat(scrape()).as("no worker process was spawned for the second tenant")
                .contains("c93_workers_running{node=\"" + NODE + "\"} 0.0");
        assertThat(workerDaemon.boundTenantId()).as("the worker was re-bound, not replaced").isEqualTo(TENANT_B);
    }

    private static void startWorker(String secret) throws Exception {
        workerVertx = Vertx.vertx();
        workerTransport = new UdsClientTransport(workerVertx);
        workerSocket = await(workerTransport.connect(socketPath));
        workerConnection = new WorkerClientConnection(workerSocket, workerSocket);
        // What a container runs: one worker over the mounted socket, with its own engine, heap and thread count.
        workerEngine = new JavetScriptRuntime(workerVertx, 64, 4);
        workerDaemon = new WorkerDaemon(workerConnection, workerEngine, "javet", secret, 64, 4);
        await(workerDaemon.start());
    }

    private static String submit(String tenant, String script) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(tenant))
                .sendJsonObject(new JsonObject().put("script", script)
                        .put("timeoutMs", 20_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static JsonObject awaitTerminal(String tenant, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id)
                    .putHeader("Authorization", TestAuth.bearer(tenant)).send()).bodyAsJsonObject();
            if (!"QUEUED".equals(last.getString("status")) && !"RUNNING".equals(last.getString("status"))) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + id + " never reached a terminal state: " + (last == null ? null : last.encodePrettily()));
    }

    private static String scrape() {
        try {
            return await(client.get(node.metricsPort(), "localhost", "/metrics").send()).bodyAsString();
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
}
