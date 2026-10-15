package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full parity for out-of-process worker execution ({@code C93_WORKER_MODE=process}): inbound ingress serving
 * ({@code ctx.serve}), outbound HTTP and TCP through the egress gate, package imports, long-running services, bus
 * request/reply, resumed step memoization and worker-process kill detection (D-128).
 *
 * <p>Workers run as child JVMs (a wrapper script the test writes) so the freshly built worker classes are exercised
 * rather than any stale native image.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessModeParityTest {

    private static final String TENANT = "parity";

    private static RunningNode nodeA;
    private static RunningNode nodeB;
    private static Vertx clientVertx;
    private static WebClient client;
    private static Path workerScript;
    private static int echoHttpPort;
    private static int echoTcpPort;

    @BeforeAll
    static void startCluster() throws Exception {
        workerScript = writeWorkerScript();
        nodeA = await(Cluster93.start(config("node-a")));
        TestAuth.use(nodeA);
        nodeB = await(Cluster93.start(config("node-b")));
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        echoHttpPort = await(clientVertx.createHttpServer()
                .requestHandler(req -> req.response().putHeader("content-type", "text/plain").end("hello-parity"))
                .listen(0, "127.0.0.1")).actualPort();
        echoTcpPort = await(clientVertx.createNetServer()
                .connectHandler(sock -> sock.handler(sock::write))
                .listen(0, "127.0.0.1")).actualPort();

        awaitJson(nodeA, "/v1/nodes", json -> json.getJsonArray("nodes").size() == 2);
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (clientVertx != null) {
            if (clientVertx != null) { await(clientVertx.close()); }
        }
        if (nodeB != null) {
            nodeB.stop().toCompletionStage().toCompletableFuture().handle((v, e) -> null).get(30, TimeUnit.SECONDS);
        }
        if (nodeA != null) {
            nodeA.stop().toCompletionStage().toCompletableFuture().handle((v, e) -> null).get(30, TimeUnit.SECONDS);
        }
    }

    // --- package imports and env ---

    @Test
    @Order(1)
    @DisplayName("A job resolves and imports a published package off-process")
    void importsAPublishedPackage() throws Exception {
        publishPackage("parity-strings", "1.0.0", "export const shout = (s) => String(s).toUpperCase();");
        JsonObject job = runJob("""
                import { shout } from 'parity-strings';
                export default () => ({ greeted: shout('parity') });
                """);
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(job.getJsonObject("output").getString("greeted")).isEqualTo("PARITY");
    }

    // --- ctx.serve and ingress ---

    @Test
    @Order(2)
    @DisplayName("A long-running service serves HTTP through the ingress from a worker process")
    void servesHttp() throws Exception {
        putService("web", """
                export default async (input, ctx) => {
                  await ctx.serve(async (req) => {
                    if (req.path === '/hello') return `hello from run ${ctx.service.run}`;
                    if (req.path === '/echo') return { status: 201, headers: { 'x-served-by': 'c93' }, body: { method: req.method, q: req.query.q } };
                    return { status: 404, body: { error: 'no route' } };
                  });
                  return ctx.stopRequested;
                }
                """);
        awaitServiceRunning("web");

        for (RunningNode node : List.of(nodeA, nodeB)) {
            HttpResponse<Buffer> hello = awaitServing(node, "web", "web--" + TENANT + ".apps.test", "/hello");
            assertThat(hello.statusCode()).as(hello.bodyAsString()).isEqualTo(200);
            assertThat(hello.bodyAsString()).isEqualTo("hello from run 1");
        }
        HttpResponse<Buffer> echo = await(ingress(nodeB, "web--" + TENANT + ".apps.test", "GET", "/echo?q=search").send());
        assertThat(echo.statusCode()).isEqualTo(201);
        assertThat(echo.getHeader("x-served-by")).isEqualTo("c93");
        assertThat(echo.bodyAsJsonObject()).isEqualTo(new JsonObject().put("method", "GET").put("q", "search"));
    }

    // --- fetch egress ---

    @Test
    @Order(3)
    @DisplayName("A worker process fetches an allowed address through the egress gate")
    void fetchesThroughTheEgressGate() throws Exception {
        JsonObject job = runJob("""
                export default async () => {
                  const response = await fetch('http://127.0.0.1:%d/');
                  return { status: response.status, body: await response.text() };
                }
                """.formatted(echoHttpPort));
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        JsonObject output = job.getJsonObject("output");
        assertThat(output.getInteger("status")).isEqualTo(200);
        assertThat(output.getString("body")).isEqualTo("hello-parity");
    }

    // --- net.connect egress ---

    @Test
    @Order(4)
    @DisplayName("A worker process opens a TCP socket through the egress gate and streams both ways")
    void connectsTcpThroughTheEgressGate() throws Exception {
        JsonObject job = runJob("""
                import net from 'node:net';
                export default () => new Promise((resolve, reject) => {
                  const socket = net.connect({ host: '127.0.0.1', port: %d }, () => socket.write('ping-through-the-gate'));
                  let data = '';
                  socket.on('data', (chunk) => { data += chunk; if (data.includes('ping-through-the-gate')) { socket.end(); resolve({ echoed: data }); } });
                  socket.on('error', reject);
                });
                """.formatted(echoTcpPort));
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(job.getJsonObject("output").getString("echoed")).isEqualTo("ping-through-the-gate");
    }

    // --- bus request/reply (listeners + events + replies) ---

    @Test
    @Order(5)
    @DisplayName("A worker process handles a bus request from another run")
    void answersBusRequests() throws Exception {
        putService("calculator", """
                export default async (input, ctx) => {
                  await bus.handle('add', (message) => message.a + message.b);
                  return ctx.stopRequested;
                }
                """);
        awaitServiceRunning("calculator");

        JsonObject job = runJob("export default () => bus.request('add', { a: 2, b: 40 })");
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(job.getValue("output")).isEqualTo(42);
    }

    // --- worker kill detection ---

    @Test
    @Order(6)
    @DisplayName("Killing a worker process fails its run immediately")
    void detectsAWorkerProcessKill() throws Exception {
        String script = """
                export default async (input, ctx) => {
                  await ctx.sleep(60000);
                  return 'never';
                }
                """;
        String id = submit(TENANT, new JsonObject().put("script", script).put("timeoutMs", 60_000));
        awaitJson(nodeA, "/v1/jobs/" + id, j -> "RUNNING".equals(j.getString("status")));

        int killed = killWorkerProcesses();
        assertThat(killed).as("at least the running job's worker was killed").isGreaterThan(0);

        JsonObject done = awaitTerminal(nodeA, id);
        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("FAILED");
        assertThat(done.getString("error")).contains("Worker process died");
    }

    // --- resumed job step memoization (orphan policy) ---

    @Test
    @Order(100)
    @DisplayName("A resumed job skips memoized steps on a fresh node after the running node is lost")
    void resumesMemoizedSteps() throws Exception {
        String script = """
                export default async (input, ctx) => {
                  for (const name of ['one', 'two', 'three']) {
                    await ctx.step(name, () => {
                      // Attempt 1 lingers in step "two" so the test can lose the node running it.
                      const until = Date.now() + (name === 'two' && ctx.attempt === 1 ? 8000 : 0);
                      while (Date.now() < until) {}
                      return name.toUpperCase();
                    });
                  }
                  return { attempt: ctx.attempt, skipped: ctx.completedSteps };
                }
                """;
        String id = submit(TENANT, new JsonObject().put("script", script).put("onOrphan", "resume")
                .put("maxAttempts", 3).put("timeoutMs", 30_000));

        // Wait until the first step is durably recorded, then lose the node running the job.
        awaitJson(nodeA, "/v1/jobs/" + id + "/steps", steps -> steps.getJsonArray("steps").stream()
                .anyMatch(s -> "one".equals(((JsonObject) s).getString("name"))));
        JsonObject running = getJson(nodeA, "/v1/jobs/" + id);
        String victimId = running.getString("node");
        RunningNode victim = victimId.equals(nodeA.nodeId()) ? nodeA : nodeB;
        RunningNode survivor = victim == nodeA ? nodeB : nodeA;
        if (victim != null) { await(victim.stop()); }

        JsonObject done = awaitTerminal(survivor, id);
        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(done.getInteger("attempt")).isEqualTo(2);
        JsonObject output = done.getJsonObject("output");
        assertThat(output.getInteger("attempt")).isEqualTo(2);
        assertThat(output.getJsonArray("skipped")).as("step \"one\" was memoized and not re-run").contains("one");

        // All three steps are durably recorded: "one" in attempt 1, "two"/"three" in attempt 2. A STATE commit that
        // raced the run's result must still have landed before the run is reported finished (D-128).
        JsonObject steps = getJson(survivor, "/v1/jobs/" + id + "/steps");
        assertThat(steps.getJsonArray("steps").stream()
                .map(s -> ((JsonObject) s).getString("name") + "@" + ((JsonObject) s).getInteger("attempt")))
                .as("every step is recorded for its attempt").containsExactly("one@1", "two@2", "three@2");
    }

    // --- helpers ---

    private static ServerConfig config(String nodeId) {
        Map<String, String> env = new HashMap<>(Map.ofEntries(
                Map.entry("C93_NODE_ID", nodeId),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_INGRESS_DOMAIN", "apps.test"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                Map.entry("C93_ORPHAN_GRACE_MS", "600"),
                Map.entry("C93_REQUEUE_AFTER_MS", "500"),
                Map.entry("C93_STOP_GRACE_MS", "1000"),
                Map.entry("C93_EGRESS_ALLOW_PRIVATE", "true"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", "22701"),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1"),
                Map.entry("C93_WORKER_MODE", "process"),
                // Pin the shared-worker design on: a worker runs several jobs at once (D-155). Leaving this to the
                // default would let a change to ServerConfig silently stop the parity suite exercising a shared
                // worker, which is exactly the path this test class exists to cover.
                Map.entry("C93_MAX_JOBS_PER_WORKER", "4"),
                Map.entry("C93_WORKER_DEFAULT_MODE", "javet"),
                Map.entry("C93_WORKER_BINARY_JAVET", workerScript.toString())));
        return ServerConfig.fromEnv(TestTls.secure(env));
    }

    /**
     * A shell wrapper the pool spawns as its "worker binary": it execs a JVM running {@code WorkerMain} with the
     * freshly built worker classes in front of the (possibly stale) worker jar.
     */
    private static Path writeWorkerScript() throws Exception {
        Path classes = findUp("worker/target/classes");
        requireLoadableEngine(classes);
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        String classpath = (classes != null ? classes + File.pathSeparator : "")
                + cluster93.runner.protocol.pool.TenantWorkerPool.resolveWorkerClasspath();
        // The worker's frames travel on stdout, so its logging must go to stderr; point logback at a stderr-only config.
        Path logback = Files.createTempFile("cluster93-worker-logback-", ".xml");
        Files.writeString(logback, "<configuration><appender name=\"STDERR\" class=\"ch.qos.logback.core.ConsoleAppender\">"
                + "<target>System.err</target><encoder><pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>"
                + "</encoder></appender><root level=\"WARN\"><appender-ref ref=\"STDERR\"/></root></configuration>", StandardCharsets.UTF_8);
        Path script = Files.createTempFile("cluster93-parity-worker-", ".sh");
        Files.writeString(script, "#!/bin/sh\nexec " + quote(javaBin) + " -Dlogback.configurationFile=" + quote(logback.toString())
                + " -cp " + quote(classpath) + " cluster93.worker.WorkerMain \"$@\"\n", StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        return script;
    }

    /**
     * This test runs the worker as a child JVM on the server's classpath. Both engine modules are on it (the server
     * depends on cluster93-runtime-javet, and pulls cluster93-runtime-graaljs in test scope), so a worker built for
     * either engine runs; a worker built for none cannot start at all. Abort with the fix rather than let every job
     * fail with no hint (D-135).
     */
    private static void requireLoadableEngine(Path workerClasses) throws Exception {
        Path registration = workerClasses == null ? null
                : workerClasses.resolve("META-INF").resolve("services").resolve("cluster93.worker.WorkerEngine");
        String engine = registration != null && Files.isRegularFile(registration)
                ? Files.readString(registration).strip() : "";
        boolean known = engine.endsWith("JavetWorkerEngine") || engine.endsWith("GraalJsWorkerEngine");
        if (!known) {
            String message = "ProcessModeParityTest runs the worker as a child JVM, and needs one of the engines "
                    + "cluster93-worker registers with a Maven profile. The worker build at "
                    + (workerClasses == null ? "worker/target/classes (missing)" : workerClasses.toString())
                    + " registers '" + (engine.isEmpty() ? "<no engine>" : engine) + "'. Build it with: "
                    + "./mvnw -pl worker -am -Pjavet compile   (or -Pgraaljs, or run scripts/dev.sh build)";
            // A container abort shows no reason in the console, so say it first, then skip rather than hang (D-135).
            System.err.println("[ProcessModeParityTest] SKIPPED: " + message);
            org.junit.jupiter.api.Assumptions.abort(message);
        }
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static Path findUp(String relative) {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static int killWorkerProcesses() throws Exception {
        int killed = 0;
        try (var pids = Files.list(Path.of("/proc"))) {
            for (Path dir : pids.toList()) {
                String name = dir.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                String commandLine;
                try {
                    commandLine = new String(Files.readAllBytes(dir.resolve("cmdline")), StandardCharsets.ISO_8859_1);
                } catch (Exception unreadable) {
                    continue;
                }
                if (commandLine.contains("cluster93.worker.WorkerMain")) {
                    ProcessHandle.of(Long.parseLong(name)).ifPresent(ProcessHandle::destroyForcibly);
                    killed++;
                }
            }
        }
        return killed;
    }

    private static void publishPackage(String name, String version, String indexJs) throws Exception {
        String sha = sha256(indexJs);
        String bearer = TestAuth.bearer(TENANT);
        HttpResponse<Buffer> uploaded = await(client.put(nodeA.httpPort(), "localhost", "/v1/packages/blobs/" + sha)
                .putHeader("Authorization", bearer).putHeader("Content-Type", "application/octet-stream")
                .sendBuffer(Buffer.buffer(indexJs)));
        assertThat(uploaded.statusCode()).as(uploaded.bodyAsString()).isIn(200, 201);
        JsonObject manifest = new JsonObject()
                .put("name", name).put("version", version)
                .put("files", new JsonObject().put("index.js", "sha256:" + sha));
        HttpResponse<Buffer> published = await(client.post(nodeA.httpPort(), "localhost", "/v1/packages")
                .putHeader("Authorization", bearer).sendJsonObject(manifest));
        assertThat(published.statusCode()).as(published.bodyAsString()).isIn(201, 409);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static HttpRequest<Buffer> ingress(RunningNode node, String host, String method, String path) {
        return client.request(io.vertx.core.http.HttpMethod.valueOf(method), node.ingressPort(), "localhost", path).putHeader("Host", host);
    }

    private static void putService(String name, String script) throws Exception {
        HttpResponse<Buffer> response = await(client.put(nodeA.httpPort(), "localhost", "/v1/services/" + name)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("script", script)));
        assertThat(response.statusCode()).as(response.bodyAsString()).isIn(200, 201);
    }

    private static void awaitServiceRunning(String name) throws Exception {
        awaitJson(nodeA, "/v1/services/" + name, s -> "RUNNING".equals(s.getString("status")));
        // Let the run register its handlers. A service on a shared worker takes longer to reach bus.handle/ctx.serve
        // than one with a worker to itself: the second context in a warm process is built after RUNNING is reported.
        Thread.sleep(3000);
    }

    /** Polls the ingress until the service is actually serving (its {@code ctx.serve} has registered). */
    private static HttpResponse<Buffer> awaitServing(RunningNode node, String service, String host, String path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        HttpResponse<Buffer> last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = await(ingress(node, host, "GET", path).timeout(5_000).send());
            } catch (Exception e) {
                last = null;
            }
            if (last != null && last.statusCode() != 503) {
                return last;
            }
            Thread.sleep(200);
        }
        JsonObject status = getJson(node, "/v1/services/" + service);
        String jobId = status.getString("currentJobId");
        throw new AssertionError("Service " + service + " never served; last=" + (last == null ? "timeout" : last.statusCode() + " " + last.bodyAsString())
                + "; service=" + status.encode() + (jobId == null ? "" : "; run=" + getJson(node, "/v1/jobs/" + jobId).encode()));
    }

    private static String submit(String tenant, JsonObject body) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(nodeA.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(tenant)).sendJsonObject(body));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static JsonObject runJob(String script) throws Exception {
        String id = submit(TENANT, new JsonObject().put("script", script).put("timeoutMs", 15_000));
        return awaitTerminal(nodeA, id);
    }

    private static JsonObject awaitTerminal(RunningNode node, String id) throws Exception {
        return awaitJson(node, "/v1/jobs/" + id, j -> !List.of("QUEUED", "RUNNING").contains(j.getString("status")));
    }

    private static HttpResponse<Buffer> get(RunningNode node, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private static JsonObject getJson(RunningNode node, String path) throws Exception {
        return get(node, path).bodyAsJsonObject();
    }

    private static JsonObject awaitJson(RunningNode node, String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = getJson(node, path);
            if (last != null && condition.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
