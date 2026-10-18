package cluster93.runner.protocol.client;

import cluster93.core.host.NodeHostApis;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.job.JobStatus;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.StateSink;
import cluster93.runner.protocol.message.PolicyMessage;
import cluster93.runner.protocol.pool.TenantWorkerPool;
import cluster93.runner.protocol.pool.TenantWorkerPoolManager;
import cluster93.runner.protocol.pool.WorkerCgroups;
import cluster93.runner.protocol.pool.WorkerHandle;
import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import cluster93.runner.protocol.transport.uds.UdsClientTransport;
import cluster93.runtime.javet.JavetScriptRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Egress decided and made by the worker (D-173/A4): the node hands it the rules, and the worker resolves, checks and
 * connects itself.
 *
 * <p>The proof is a <b>divergent</b> policy and a node that cannot fetch at all: the worker's synced rules allow an
 * address the node's own gate would refuse, and the node has no fetch client for the run — so a fetch that returns a
 * body can only have been made by the worker, and a refusal can only be the worker's own decision.
 */
class WorkerLocalEgressTest {

    private Vertx vertx;
    private UdsClientTransport client;
    private Path dir;
    private String socketPath;
    private TenantWorkerPoolManager manager;
    private WorkerDaemon daemon;
    /** The worker's side of the connection: closing it ends the worker, as it does for a real container. */
    private io.vertx.core.net.NetSocket daemonSocket;
    private int echoPort;
    /**
     * The address the "public endpoint" is reached at. It must not be loopback: a node's own loopback is one of the
     * cluster's addresses, which no rule can open (D-108) — so a loopback target would be refused before any rule was
     * ever consulted, which is exactly what the first version of this test got wrong.
     */
    private String echoHost;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        dir = Files.createTempDirectory("c93-worker-egress");
        socketPath = dir.resolve("workers").resolve("node.sock").toString();
        client = new UdsClientTransport(vertx);

        // An address of this machine that is not loopback and not a cluster node: a private LAN address, so only an
        // allow rule can reach it (which is the point — this worker's rules do, the node's own gate does not).
        echoHost = lanAddress();
        // The "public endpoint": a server in this JVM, reachable only if someone actually connects to it.
        echoPort = vertx.createHttpServer()
                .requestHandler(request -> request.response().end("pong-from-the-worker"))
                .listen(0, "0.0.0.0")
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).actualPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (daemonSocket != null) {
            await(daemonSocket.close());
        }
        if (manager != null) {
            manager.close();
        }
        client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    /** A node that hands the worker {@code tenantPolicy} and no cluster rules of its own. */
    private void startNode(JsonObject tenantPolicy) throws Exception {
        manager = new TenantWorkerPoolManager(vertx, Map.of(), "javet", 0, 2, 2, 2, 64,
                TenantWorkerPool.ReusePolicy.REUSE_SAME_TENANT, new WorkerCgroups(), NodeRpcAuthorizer.ALLOW_ALL,
                512L * 1024 * 1024)
                .workerPolicy(tenant -> Future.succeededFuture(new PolicyMessage(tenant, PolicyMessage.WORKER, 1L,
                        60_000L, false, List.of(),
                        // Default-deny posture: nothing is reachable that a rule doesn't name.
                        new JsonObject().put("posture", "allow-list"), tenantPolicy, List.of(), List.of())));
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        // A real worker, as a container runs one — and one that decides egress itself.
        daemonSocket = await(client.connect(socketPath));
        WorkerClientConnection connection = new WorkerClientConnection(daemonSocket, daemonSocket);
        daemon = new WorkerDaemon(connection, new JavetScriptRuntime(vertx, 64, 2), "javet", "s3cret", 64, 2)
                .egressLocally(vertx);
        await(daemon.start());
        awaitUntil("the worker to become an open worker", () -> manager.openWorkerCount() == 1);
    }

    /** Runs a job on the bound worker, with the node side unable to fetch at all: only the worker can reach out. */
    private ExecutionResult run(String script) throws Exception {
        WorkerHandle handle = await(manager.acquire("acme", null));
        // No FetchClient on this node's host APIs: the remote path cannot fetch, so a body can only come from the worker.
        NodeHostApis nodeHostApis = new NodeHostApis(new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), null, null),
                "acme", "job-egress", false);
        Execution execution = new Execution("job-egress", "acme", script, new JsonObject(), Duration.ofSeconds(20),
                JobContext.firstAttempt("job-egress", OrphanPolicy.FAIL));
        return await(handle.run(1, execution, nodeHostApis, (level, line) -> { }, StateSink.DISCARD));
    }

    @Test
    @DisplayName("A worker fetches with its own rules and its own connection, and the node is not in the path (D-173)")
    void theWorkerMakesTheConnection() throws Exception {
        startNode(new JsonObject().put("rules", new JsonArray().add(new JsonObject()
                .put("action", "allow").put("to", echoHost + "/32").put("ports", new JsonArray().add(echoPort)))));

        ExecutionResult result = run("""
                export default async () => {
                  try {
                    const response = await fetch('http://%s:%d/ping');
                    return { body: await response.text(), status: response.status };
                  } catch (e) {
                    return { failed: String(e.message), cause: e.cause ? [e.cause.code, e.cause.message] : null };
                  }
                };
                """.formatted(echoHost, echoPort));

        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCEEDED);
        JsonObject output = (JsonObject) result.output();
        assertThat(output.getString("body")).as("the body came back, so somebody connected — and it was the worker: %s",
                        output.encode())
                .isEqualTo("pong-from-the-worker");
        assertThat(output.getInteger("status")).isEqualTo(200);
        assertThat(daemon.accounting().denied()).as("nothing was refused").isZero();
    }

    @Test
    @DisplayName("A worker refuses what its own rules refuse, in the node's error shape (D-173)")
    void theWorkerRefusesWhatItsRulesRefuse() throws Exception {
        startNode(new JsonObject().put("rules", new JsonArray()));

        // Nothing allows example.com, and the posture is default-deny: the worker's own decision, not the node's —
        // this node has no fetch client, so the node could not have produced `egress_denied` at all.
        ExecutionResult result = run("""
                export default async () => {
                  try {
                    await fetch('http://example.com/');
                    return { reached: true };
                  } catch (e) {
                    return { reached: false, code: e.cause ? e.cause.code : e.code, message: e.cause ? e.cause.message : e.message };
                  }
                };
                """);

        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCEEDED);
        JsonObject output = (JsonObject) result.output();
        assertThat(output.getBoolean("reached")).isFalse();
        assertThat(output.getString("code")).as("the same code a script sees when the node refuses").isEqualTo("egress_denied");
        assertThat(output.getString("message")).contains("not allowed");
        assertThat(daemon.accounting().denials()).as("and the worker counted its own refusal")
                .containsEntry("not_allowed", 1L);
    }

    @Test
    @DisplayName("A tenant's local grant reaches the worker's own loopback, and without it the worker refuses its own (D-174)")
    void theLocalGrantOpensTheWorkersOwnLoopback() throws Exception {
        // The grant travels in the policy the node syncs: no protocol work, and no rule needed.
        startNode(new JsonObject().put("allowLocal", true));

        ExecutionResult allowed = run("""
                export default async () => {
                  const response = await fetch('http://127.0.0.1:%d/ping');
                  return { body: await response.text() };
                };
                """.formatted(echoPort));

        assertThat(allowed.status()).as(String.valueOf(allowed.error())).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(((JsonObject) allowed.output()).getString("body"))
                .as("its own loopback, which is the machine the connection comes from")
                .isEqualTo("pong-from-the-worker");
    }

    @Test
    @DisplayName("Without the grant, a worker refuses its own loopback as a cluster address (D-174)")
    void withoutTheGrantTheWorkersOwnLoopbackIsRefused() throws Exception {
        startNode(new JsonObject().put("rules", new JsonArray()));

        ExecutionResult refused = run("""
                export default async () => {
                  try {
                    await fetch('http://127.0.0.1:%d/ping');
                    return { reached: true };
                  } catch (e) {
                    return { reached: false, code: e.cause ? e.cause.code : e.code };
                  }
                };
                """.formatted(echoPort));

        assertThat(refused.status()).as(String.valueOf(refused.error())).isEqualTo(JobStatus.SUCCEEDED);
        JsonObject output = (JsonObject) refused.output();
        assertThat(output.getBoolean("reached")).isFalse();
        assertThat(output.getString("code")).isEqualTo("egress_denied");
        assertThat(daemon.accounting().denials()).containsEntry("cluster_node", 1L);
    }

    @Test
    @DisplayName("A worker that has no policy, or the node's placement, calls the node as before (D-173)")
    void withoutAPolicyTheNodeStillServes() throws Exception {
        // A node that decides egress itself: it sends no policy at all.
        manager = new TenantWorkerPoolManager(vertx, Map.of(), "javet", 0, 2, 2, 2, 64,
                TenantWorkerPool.ReusePolicy.REUSE_SAME_TENANT, new WorkerCgroups(), NodeRpcAuthorizer.ALLOW_ALL,
                512L * 1024 * 1024);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));
        daemonSocket = await(client.connect(socketPath));
        WorkerClientConnection connection = new WorkerClientConnection(daemonSocket, daemonSocket);
        daemon = new WorkerDaemon(connection, new JavetScriptRuntime(vertx, 64, 2), "javet", "s3cret", 64, 2)
                .egressLocally(vertx);
        await(daemon.start());
        awaitUntil("the worker to become an open worker", () -> manager.openWorkerCount() == 1);
        await(manager.acquire("acme", null));

        assertThat(daemon.egressPolicy().fresh()).as("no policy was ever sent").isFalse();

        // The job does not fetch — this only asserts that a worker without a policy stays the node's to decide for,
        // which is what makes the fallback safe rather than a hole.
        ExecutionResult result = run("export default () => ({ ok: true });");
        assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(daemon.accounting().denied()).isZero();
    }

    /** This machine's first non-loopback IPv4 address. */
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

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}
