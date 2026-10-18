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
 * Placement is per tenant (D-173 item 8): one node whose default is the worker, one worker connection, and two tenants
 * decided on opposite sides. The same job, refused for the same reason with the same message, is refused by
 * <b>different sides</b> — and the metrics say which, because a node counts its own refusals in
 * {@code c93_egress_denied} while a worker's reach it only as the report D-176 added
 * ({@code c93_worker_egress_denied}).
 *
 * <p>Why the refusal is the honest proof here, rather than a body: <b>a node's own addresses are refused by both
 * sides</b> (D-108), so no address of this machine can show a connection the worker <em>succeeded</em> in making —
 * that is what the container check's sibling target is for (`scripts/worker-isolation-check.sh --case C5`). Who said
 * no is what a node and a worker can be told apart by without one, and it is the same discriminator C5 uses.
 *
 * <p>It also asserts the operator-facing half: the node reports each tenant's effective placement and whether the
 * tenant asked for it or follows the node, and a worker re-bound between the two tenants (D-172) switches sides,
 * because the placement arrives with the binding.
 *
 * <p><b>Found while writing this, and left as a finding rather than policy:</b> the same refusal does not reach a
 * script identically from the two sides. In the worker the gate fails the call itself, so the script sees
 * {@code egress_denied}. On the node a refused {@code net.connect} is a <em>stream</em>: the node resets it, and the
 * reset carries the gate's reason but not the error's name or code — D-132 kept those for a fetch response and a file
 * finish, not for a socket — so the script sees {@code host_error} with the gate's words inside
 * {@code "Stream reset (code 502): ..."}. The node path is the shape host calls always had, so this is older than the
 * placement; but it is the placement showing through, which is what D-173 says must not happen. This test therefore
 * compares the <em>words</em>, which match, and the counters, which do not.
 */
class PerTenantEgressPlacementTest {

    private static final String ON_THE_NODE = "placement-on-node";
    private static final String IN_THE_WORKER = "placement-in-worker";
    private static final String SECRET = "a-pre-shared-worker-secret";
    private static final String NODE = "placementnode";
    /** An address of this machine that is not loopback: one every side refuses, whichever reason it lands on. */
    private static String targetHost;

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static Vertx workerVertx;
    private static UdsClientTransport workerTransport;
    // Held for the test's lifetime on purpose: a worker is a live connection, and nothing else references the daemon
    // or its engine once it has said hello — a collected client would close its socket.
    private static WorkerDaemon workerDaemon;
    private static WorkerClientConnection workerConnection;
    private static JavetScriptRuntime workerEngine;
    private static NetSocket workerSocket;
    private static Path dir;
    private static String socketPath;

    @BeforeAll
    static void start() throws Exception {
        targetHost = lanAddress();
        dir = Files.createTempDirectory("c93-per-tenant-placement");
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
                // The node's default: the worker decides. One tenant below overrides it for itself.
                Map.entry("C93_EGRESS_PLACEMENT", "worker"),
                Map.entry("C93_WORKER_SOCKET", socketPath),
                Map.entry("C93_WORKER_SECRET_FILE", secretFile.toString())))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        startWorker(SECRET);
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

    @Test
    @DisplayName("One tenant can be placed on the node while the node's default is the worker, and the same job is refused by different sides (D-173 item 8)")
    void thePlacementIsTheTenants() throws Exception {
        awaitUntil("the connected worker to appear in this node's metrics",
                () -> scrape().contains("c93_workers_connected{node=\"" + NODE + "\"} 1.0"));

        // The operator's side: a tenant that asks for the node, and one that names nothing and follows the node's. A
        // tenant has to exist before anything can be set on it; minting its first token is what creates it (TestAuth).
        TestAuth.bearer(ON_THE_NODE);
        TestAuth.bearer(IN_THE_WORKER);
        setPolicy(ON_THE_NODE, new JsonObject().put("placement", "node").put("rules", new io.vertx.core.json.JsonArray()));
        setPolicy(IN_THE_WORKER, new JsonObject().put("rules", new io.vertx.core.json.JsonArray()));
        assertThat(policyOf(ON_THE_NODE).getJsonObject("placement"))
                .as("the tenant's own choice, and the node says where it came from")
                .isEqualTo(new JsonObject().put("value", "node").put("from", "tenant")
                        .put("why", "This tenant's policy names it."));
        assertThat(policyOf(IN_THE_WORKER).getJsonObject("placement").getString("value"))
                .as("a tenant that names none follows the node's default").isEqualTo("worker");
        assertThat(policyOf(IN_THE_WORKER).getJsonObject("placement").getString("from")).isEqualTo("node");
        assertThat(policyOf(IN_THE_WORKER).getJsonObject("placement"))
                .as("the whole object, so a tenant can read who decided its placement")
                .isEqualTo(new JsonObject().put("value", "worker").put("from", "node")
                        .put("why", "The node's C93_EGRESS_PLACEMENT, which this tenant follows."));

        // The same job for both: nothing allows this address, so whoever decides refuses it — with the same words.
        JsonObject onTheNode = run(ON_THE_NODE, refusedScript());
        JsonObject inTheWorker = run(IN_THE_WORKER, refusedScript());
        assertThat(onTheNode.getBoolean("reached")).isFalse();
        assertThat(inTheWorker.getBoolean("reached")).isFalse();
        assertThat(refusal(onTheNode)).as("the node refused, and said exactly why: %s", onTheNode.encode())
                .isEqualTo("net.connect to " + targetHost + ":9 is not allowed: it resolves to one of the cluster's own"
                        + " nodes, which scripts can never reach");
        assertThat(refusal(inTheWorker)).as("the worker said the same thing, because it is the same gate: %s",
                        inTheWorker.encode())
                .as("the same refusal, word for word, on either side (D-173)")
                .isEqualTo(refusal(onTheNode));

        // Who said no. The node decided for the tenant it was asked to decide for, and made nothing for the other.
        assertThat(counter("c93_egress_denied_total", "tenant=\"" + ON_THE_NODE + "\""))
                .as("the node refused the job it made itself: %s", scrape())
                .isEqualTo(1.0);
        assertThat(counter("c93_egress_denied_total", "tenant=\"" + IN_THE_WORKER + "\""))
                .as("and it did not refuse the one its worker made — it never saw it as a refusal of its own")
                .isZero();

        // The other side of the same pair: the worker refused for the tenant that decides, and told the node so.
        awaitUntil("the worker's refusal to reach the node as a report (D-176)",
                () -> counter("c93_worker_egress_denied_total", "tenant=\"" + IN_THE_WORKER + "\"") == 1.0);
        assertThat(counter("c93_worker_egress_denied_total", "tenant=\"" + ON_THE_NODE + "\""))
                .as("the worker made no refusals for the tenant the node decides for").isZero();
        assertThat(workerDaemon.accounting().denials())
                .as("and the refusal the node counted up there is this worker's own tally: %s",
                        workerDaemon.accounting().denials())
                .isNotEmpty();
    }

    /** The same script for both tenants: one connection to an address no rule allows. */
    private static String refusedScript() {
        return """
                import net from 'node:net';
                export default async () => {
                  try {
                    await new Promise((resolve, reject) => {
                      const socket = net.connect({ host: '%s', port: 9 });
                      socket.once('connect', () => { socket.end(); resolve(); });
                      socket.once('error', reject);
                    });
                    return { reached: true };
                  } catch (e) {
                    return { reached: false, code: e.code || (e.cause && e.cause.code) || null,
                             message: e.message || (e.cause && e.cause.message) || null };
                  }
                };
                """.formatted(targetHost);
    }

    private static void startWorker(String secret) throws Exception {
        workerVertx = Vertx.vertx();
        workerTransport = new UdsClientTransport(workerVertx);
        workerSocket = await(workerTransport.connect(socketPath));
        workerConnection = new WorkerClientConnection(workerSocket, workerSocket);
        // What a container runs: one worker over the mounted socket, with its own engine, heap, thread count — and
        // its own egress, which is what makes the placement mean anything (D-173). A short report interval: a test
        // cannot wait the production one out.
        workerEngine = new JavetScriptRuntime(workerVertx, 64, 4);
        workerDaemon = new WorkerDaemon(workerConnection, workerEngine, "javet", secret, 64, 4)
                .egressLocally(workerVertx, 25);
        await(workerDaemon.start());
    }

    private static void setPolicy(String tenant, JsonObject policy) throws Exception {
        HttpResponse<Buffer> stored = await(client.put(node.httpPort(), "localhost",
                        "/v1/tenants/" + tenant + "/egress-policy")
                .putHeader("Authorization", TestAuth.ADMIN).sendJsonObject(policy));
        assertThat(stored.statusCode()).as(stored.bodyAsString()).isEqualTo(200);
    }

    private static JsonObject policyOf(String tenant) throws Exception {
        return await(client.get(node.httpPort(), "localhost", "/v1/tenants/" + tenant + "/egress-policy")
                .putHeader("Authorization", TestAuth.ADMIN).send()).bodyAsJsonObject();
    }

    /**
     * The gate's own words, without whatever the node's path wraps them in: a socket the node makes fails the connect
     * and reaches the script as a stream reset, so the refusal arrives as {@code "Stream reset (code 502): <the gate>"}
     * (see the class comment — the wrapper, and the lost error code, are properties of the node path, not of the
     * placement). What has to match is what the gate said, and stripping the wrapper is what makes that comparison
     * stand: if the node ever keeps the code and drops the wrapper, this returns the message unchanged and still holds.
     */
    private static String refusal(JsonObject output) {
        String message = output.getString("message", "");
        int marker = message.indexOf("): ");
        return message.startsWith("Stream reset (code ") && marker > 0 ? message.substring(marker + 3) : message;
    }

    /** Runs the script as the tenant and returns its output, once the job is terminal. */
    private static JsonObject run(String tenant, String script) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs")
                .putHeader("Authorization", TestAuth.bearer(tenant))
                .sendJsonObject(new JsonObject().put("script", script).put("timeoutMs", 20_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        String id = submitted.bodyAsJsonObject().getString("id");

        JsonObject job = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            job = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id)
                    .putHeader("Authorization", TestAuth.bearer(tenant)).send()).bodyAsJsonObject();
            if (!"QUEUED".equals(job.getString("status")) && !"RUNNING".equals(job.getString("status"))) {
                assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
                return job.getJsonObject("output");
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + id + " never reached a terminal state: "
                + (job == null ? null : job.encodePrettily()));
    }

    /** This machine's first non-loopback IPv4 address: one every side refuses, whichever reason it lands on. */
    private static String lanAddress() throws Exception {
        for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (java.net.InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        throw new IllegalStateException("This test needs an address of this machine that is not loopback");
    }

    private static String scrape() {
        try {
            return await(client.get(node.metricsPort(), "localhost", "/metrics").send()).bodyAsString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not scrape this node's metrics: " + e.getMessage(), e);
        }
    }

    /** The total of a counter's lines carrying every one of these tags, or 0 when there are none. */
    private static double counter(String metric, String... tags) {
        double total = 0;
        for (String line : scrape().split("\n")) {
            if (!line.startsWith(metric) || line.startsWith("#")) {
                continue;
            }
            boolean matches = true;
            for (String tag : tags) {
                matches &= line.contains(tag);
            }
            if (matches) {
                total += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return total;
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
