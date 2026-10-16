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
 * A worker container against a node, through the whole stack (D-166, D-167): the deployment creates the container, its
 * worker connects to the node's socket, presents the pre-shared secret (D-168), and becomes an <b>open worker</b> the
 * node binds to a tenant and runs a job on. The node spawns nothing — its worker-process count stays zero — which is
 * the point of the shape: the node never creates a container, and holds no engine socket.
 */
class WorkerSocketTest {

    private static final String TENANT = "socketc";
    private static final String SECRET = "a-pre-shared-worker-secret";

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
        dir = Files.createTempDirectory("c93-worker-socket-node");
        socketPath = dir.resolve("workers").resolve("node.sock").toString();
        Path secretFile = dir.resolve("worker-secret");
        Files.writeString(secretFile, SECRET);
        // Exactly as S5 asks: the mounted secret is its owner's to read, and nobody else's.
        Files.setPosixFilePermissions(secretFile, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));

        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "socketnode"),
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
                // The whole point: the node listens, and the deployment's worker connects to it.
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
    }

    @Test
    @DisplayName("A worker container's connection runs a tenant's job, and the node spawns no process of its own (D-166)")
    void aConnectedWorkerRunsAJob() throws Exception {
        startWorker(SECRET);

        // The connection is a worker the node counts, and no worker process was spawned for it.
        awaitUntil("the connected worker to appear in this node's metrics", () -> scrape().contains("c93_workers_connected{node=\"socketnode\"} 1.0"));
        assertThat(scrape()).contains("c93_workers_running{node=\"socketnode\"} 0.0");

        String id = submit(("""
                export default async (input, ctx) => {
                  const store = kv.namespace('socket');
                  await store.set('ran', input.name);
                  return { echoed: await store.get('ran'), attempt: ctx.attempt };
                };
                """));
        JsonObject done = awaitTerminal(id);

        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(done.getJsonObject("output").getString("echoed")).isEqualTo("over-the-socket");
        // The host call really went to this node: a later run (a fresh context on the same warm worker) reads it back.
        JsonObject read = awaitTerminal(submit("export default async () => ({ seen: await kv.namespace('socket').get('ran') });"));
        assertThat(read.getJsonObject("output").getString("seen")).isEqualTo("over-the-socket");
        assertThat(scrape()).as("the worker stayed connected and warm between the two runs")
                .contains("c93_workers_connected{node=\"socketnode\"} 1.0");
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

    private static String submit(String script) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", script).put("input", new JsonObject().put("name", "over-the-socket"))
                        .put("timeoutMs", 20_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static JsonObject awaitTerminal(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id)
                    .putHeader("Authorization", TestAuth.bearer(TENANT)).send()).bodyAsJsonObject();
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
