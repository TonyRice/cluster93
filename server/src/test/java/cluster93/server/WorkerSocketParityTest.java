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
 * The parity scenarios a spawned worker already gets, run on a worker that <b>connected</b> over the node's socket
 * (D-166): streaming in both directions. A socket worker carries the same frames as a pipe, but the streams that host
 * APIs use — a fetched response body, a TCP socket, an inbound request being proxied to {@code ctx.serve} — all
 * multiplex over that connection, so this is the path worth exercising end to end rather than assuming.
 *
 * <p>Every step also asserts that the node spawned nothing ({@code c93_workers_running} stays 0), which is what makes
 * the run the connection's rather than a child process's.
 */
class WorkerSocketParityTest {

    private static final String TENANT = "socketparity";
    private static final String SECRET = "parity-over-a-socket";

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static Vertx workerVertx;
    private static UdsClientTransport workerTransport;
    private static WorkerClientConnection workerConnection;
    private static WorkerDaemon workerDaemon;
    private static NetSocket workerSocket;
    private static final java.util.List<NetSocket> extraWorkers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static Path dir;
    private static String socketPath;
    private static int echoHttpPort;
    private static int echoTcpPort;

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("c93-socket-parity");
        socketPath = dir.resolve("workers").resolve("node.sock").toString();
        Path secretFile = dir.resolve("worker-secret");
        Files.writeString(secretFile, SECRET);
        Files.setPosixFilePermissions(secretFile, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));

        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "socketparity"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_INGRESS_DOMAIN", "apps.test"),
                Map.entry("C93_WORKER_MODE", "process"),
                Map.entry("C93_WORKER_DEFAULT_MODE", "javet"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_JOBS_PER_WORKER", "4"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                // The egress gate, as the parity suite runs it: private addresses allowed, so a local echo server is
                // reachable *through the node* while the worker container has no network at all.
                Map.entry("C93_EGRESS_ALLOW_PRIVATE", "true"),
                // Anything the node might spawn dies at once, so every job here can only run on the connection: a
                // success IS the proof (asserting a metric instead races a service restart onto a spawned worker).
                Map.entry("C93_WORKER_BINARY_JAVET", "/bin/false"),
                Map.entry("C93_WORKER_SOCKET", socketPath),
                Map.entry("C93_WORKER_SECRET_FILE", secretFile.toString())))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        echoHttpPort = await(clientVertx.createHttpServer()
                .requestHandler(req -> req.response().putHeader("content-type", "text/plain").end("hello-socket"))
                .listen(0, "127.0.0.1")).actualPort();
        echoTcpPort = await(clientVertx.createNetServer()
                .connectHandler(sock -> sock.handler(sock::write))
                .listen(0, "127.0.0.1")).actualPort();

        startWorker();
        awaitUntil("the socket worker to join the pool", () -> scrape().contains("c93_workers_connected{node=\"socketparity\"} 1.0"));
    }

    @AfterAll
    static void stop() throws Exception {
        for (NetSocket extra : extraWorkers) { await(extra.close()); }
        if (workerSocket != null) { await(workerSocket.close()); }
        if (workerTransport != null) { await(workerTransport.close()); }
        if (workerVertx != null) { await(workerVertx.close()); }
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
        deleteRecursively(dir);
    }

    private static void startWorker() throws Exception {
        workerVertx = Vertx.vertx();
        workerTransport = new UdsClientTransport(workerVertx);
        connectWorker();
    }

    /**
     * Another worker, connected over the same socket. A worker serves one tenant at a time and may be re-bound once idle
     * (D-172), so a scenario that needs a worker the pool has not already given away simply connects its own.
     */
    private static WorkerDaemon connectWorker() throws Exception {
        NetSocket socket = await(workerTransport.connect(socketPath));
        WorkerClientConnection connection = new WorkerClientConnection(socket, socket);
        JavetScriptRuntime engine = new JavetScriptRuntime(workerVertx, 64, 4);
        WorkerDaemon daemon = new WorkerDaemon(connection, engine, "javet", SECRET, 64, 4);
        await(daemon.start());
        extraWorkers.add(socket);
        return daemon;
    }

    @Test
    @DisplayName("A job on a socket worker fetches through the node's egress gate, streaming the response back (D-166)")
    void fetchesThroughTheEgressGateOverTheSocket() throws Exception {
        JsonObject done = awaitTerminal(submit(TENANT, """
                export default async () => {
                  const response = await fetch('http://127.0.0.1:%d/');
                  return { status: response.status, body: await response.text() };
                };
                """.formatted(echoHttpPort)));

        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("SUCCEEDED");
        JsonObject output = done.getJsonObject("output");
        assertThat(output.getInteger("status")).isEqualTo(200);
        assertThat(output.getString("body")).as("the body streamed back over the worker socket").isEqualTo("hello-socket");
    }

    @Test
    @DisplayName("A job on a socket worker opens a TCP socket through the egress gate, both ways (D-166)")
    void connectsTcpOverTheSocket() throws Exception {
        JsonObject done = awaitTerminal(submit(TENANT, """
                import net from 'node:net';
                export default () => new Promise((resolve, reject) => {
                  const socket = net.connect({ host: '127.0.0.1', port: %d }, () => socket.write('ping-over-the-socket'));
                  let data = '';
                  socket.on('data', (chunk) => { data += chunk; if (data.includes('ping-over-the-socket')) { socket.end(); resolve({ echoed: data }); } });
                  socket.on('error', reject);
                });
                """.formatted(echoTcpPort)));

        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(done.getJsonObject("output").getString("echoed")).isEqualTo("ping-over-the-socket");
    }

    @Test
    @DisplayName("A service on a socket worker serves HTTP through the ingress, requests proxied over the socket (D-166)")
    void servesHttpOverTheSocket() throws Exception {
        HttpResponse<Buffer> put = await(client.put(node.httpPort(), "localhost", "/v1/services/web")
                .putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", """
                        export default async (input, ctx) => {
                          await ctx.serve(async (req) => {
                            if (req.path === '/hello') return `hello from run ${ctx.service.run}`;
                            return { status: 201, headers: { 'x-served-by': 'c93' }, body: { method: req.method, q: req.query.q } };
                          });
                          return ctx.stopRequested;
                        }
                        """)));
        assertThat(put.statusCode()).as(put.bodyAsString()).isIn(200, 201);
        awaitUntil("the service to be running", () -> {
            JsonObject status = get(TENANT, "/v1/services/web");
            return "RUNNING".equals(status.getString("status"));
        });
        // Let the service's run register its handler: over a socket the context is built after RUNNING is reported.
        Thread.sleep(3000);

        HttpResponse<Buffer> hello = awaitServing("web--" + TENANT + ".apps.test", "/hello");
        assertThat(hello.statusCode()).as(hello.bodyAsString()).isEqualTo(200);
        assertThat(hello.bodyAsString()).isEqualTo("hello from run 1");

        HttpResponse<Buffer> echo = await(ingress("web--" + TENANT + ".apps.test", "GET", "/echo?q=search").send());
        assertThat(echo.statusCode()).isEqualTo(201);
        assertThat(echo.getHeader("x-served-by")).isEqualTo("c93");
        assertThat(echo.bodyAsJsonObject()).isEqualTo(new JsonObject().put("method", "GET").put("q", "search"));
    }

    @Test
    @DisplayName("The node's egress gate is in the path: a host no rule allows is refused, not reached (D-108)")
    void theEgressGateRefusesWhatNoRuleAllows() throws Exception {
        // A tenant whose policy denies one address outright: the gate is the only thing between a script and the
        // network — the worker itself has none, so anything that refuses says the node refused.
        String tenant = "socketgated";
        TestAuth.bearer(tenant);   // the tenant has to exist before its policy can be set
        HttpResponse<Buffer> policy = await(client.put(node.httpPort(), "localhost", "/v1/tenants/" + tenant + "/egress-policy")
                .putHeader("Authorization", TestAuth.ADMIN)
                // A deny rule: rules are judged before anything else, so this refuses the target whatever the
                // development switch says — which is what makes the refusal the *gate's* decision (D-108).
                .sendJsonObject(new JsonObject().put("rules", new io.vertx.core.json.JsonArray().add(
                        new JsonObject().put("action", "deny").put("to", "127.0.0.1")
                                .put("ports", new io.vertx.core.json.JsonArray().add(1))
                                .put("protocols", new io.vertx.core.json.JsonArray().add("http"))
                                .put("note", "no service lives on port 1")))));
        assertThat(policy.statusCode()).as(policy.bodyAsString()).isIn(200, 201);

        // Its own connection: a worker already bound and warm is a candidate for re-binding, not a guarantee (D-172),
        // so this scenario supplies the worker it wants rather than borrowing the pool's.
        connectWorker();
        awaitUntil("the gated tenant's worker to join the pool",
                () -> scrape().contains("c93_workers_connected{node=\"socketparity\"} 2.0"));

        JsonObject allowed = awaitTerminal(tenant, submit(tenant, """
                export default async () => {
                  const response = await fetch('http://127.0.0.1:%d/');
                  return { status: response.status };
                };
                """.formatted(echoHttpPort)));
        assertThat(allowed.getString("status")).as(allowed.encodePrettily()).isEqualTo("SUCCEEDED");

        // The script sees a TypeError with the gate's code, as Node's fetch would (D-108): the node names why.
        JsonObject refused = awaitTerminal(tenant, submit(tenant, """
                export default async () => {
                  try {
                    await fetch('http://127.0.0.1:1/');
                    return { reached: true };
                  } catch (e) {
                    // fetch() reports a failure as a TypeError with the host-side reason on `cause` (web/fetch.js).
                    const cause = e.cause;
                    return {
                      reached: false,
                      error: [e.name, e.code, e.message,
                              cause ? [cause.name, cause.code, cause.message].join(' | ') : 'no cause'].join(' || '),
                    };
                  }
                };
                """));
        assertThat(refused.getString("status")).as(refused.encodePrettily()).isEqualTo("SUCCEEDED");
        JsonObject outcome = refused.getJsonObject("output");
        assertThat(outcome.getBoolean("reached")).as("the gate stopped it: " + outcome).isFalse();
        assertThat(outcome.getString("error")).as("the gate named the reason: " + outcome)
                .contains("denies");
    }

    // --- helpers ---

    private static String submit(String tenant, String script) throws Exception {
        return submit(tenant, script, new JsonObject());
    }

    private static String submit(String tenant, String script, JsonObject input) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(tenant))
                .sendJsonObject(new JsonObject().put("script", script).put("input", input).put("timeoutMs", 20_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static JsonObject awaitTerminal(String id) throws Exception {
        return awaitTerminal(TENANT, id);
    }

    private static JsonObject awaitTerminal(String tenant, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = get(tenant, "/v1/jobs/" + id);
            if (!"QUEUED".equals(last.getString("status")) && !"RUNNING".equals(last.getString("status"))) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + id + " never reached a terminal state: " + (last == null ? null : last.encodePrettily()));
    }

    private static HttpResponse<Buffer> awaitServing(String host, String path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        HttpResponse<Buffer> last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = await(ingress(host, "GET", path).timeout(5_000).send());
            } catch (Exception e) {
                last = null;
            }
            if (last != null && last.statusCode() != 503) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Service never served " + path + "; last=" + (last == null ? "timeout" : last.statusCode()));
    }

    private static io.vertx.ext.web.client.HttpRequest<Buffer> ingress(String host, String method, String path) {
        return client.request(io.vertx.core.http.HttpMethod.valueOf(method), node.ingressPort(), "localhost", path)
                .putHeader("Host", host);
    }

    private static JsonObject get(String path) {
        return get(TENANT, path);
    }

    private static JsonObject get(String tenant, String path) {
        try {
            return await(client.get(node.httpPort(), "localhost", path)
                    .putHeader("Authorization", TestAuth.bearer(tenant)).send()).bodyAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("GET " + path + ": " + e.getMessage(), e);
        }
    }

    private static String scrape() {
        try {
            return await(client.get(node.metricsPort(), "localhost", "/metrics").send()).bodyAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
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
