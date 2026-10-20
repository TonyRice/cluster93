package cluster93.runner.protocol.pool;

import cluster93.core.host.NodeHostApis;
import cluster93.core.host.egress.Decision;
import cluster93.core.host.egress.EgressProtocol;
import cluster93.core.host.egress.Placement;
import cluster93.core.host.egress.TenantEgress;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.job.JobStatus;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.StateSink;
import cluster93.runner.protocol.frame.FrameCodec;
import cluster93.runner.protocol.message.HelloMessage;
import cluster93.runner.protocol.message.PolicyMessage;
import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import cluster93.runner.protocol.transport.uds.UdsClientTransport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node's <b>listening</b> worker source (D-166, D-167): a deployment creates the worker containers, they connect
 * to the node's socket, and each connection becomes an <b>open worker</b> the node binds to whichever tenant has work.
 * The node never creates a container, and a connection is not a worker until it has said hello and presented the
 * secret both sides read (D-147, D-168).
 */
class WorkerSocketSourceTest {

    private Vertx vertx;
    private UdsClientTransport client;
    private Path dir;
    private String socketPath;
    private TenantWorkerPoolManager manager;
    private final List<NetSocket> sockets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        client = new UdsClientTransport(vertx);
        dir = Files.createTempDirectory("c93-worker-socket");
        socketPath = dir.resolve("node.sock").toString();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (NetSocket socket : sockets) {
            socket.close();
        }
        if (manager != null) {
            manager.close();
        }
        if (client != null) {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        deleteRecursively(dir);
    }

    @Test
    @DisplayName("A connection becomes an open worker, and the node binds it to the next tenant that needs one (D-166)")
    void aConnectionBecomesAnOpenWorkerTheNodeBinds() throws Exception {
        manager = manager(1L * 1024 * 1024 * 1024);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        // The directory the node listens in is its own, and only its owner may reach it (S3): the connect is what
        // proves it was made 0700 for us, since a tighter-mode socket is unreachable to anyone else.
        assertThat(java.nio.file.Files.getPosixFilePermissions(Path.of(socketPath).getParent()).toString())
                .isEqualTo("[OWNER_READ, OWNER_WRITE, OWNER_EXECUTE]");
        // And the socket itself: the kernel creates it with the process umask, which is often group- or
        // world-connectable — the node forces it back to its owner alone, because this is the boundary a worker
        // connection crosses (S3).
        assertThat(java.nio.file.Files.getPosixFilePermissions(Path.of(socketPath)).toString())
                .isEqualTo("[OWNER_READ, OWNER_WRITE]");

        await(connectAndSayHello("s3cret", 64, 4));
        awaitUntil("the connection to become an open worker", () -> manager.openWorkerCount() == 1);
        assertThat(manager.connectedWorkerCount()).isEqualTo(1);
        assertThat(manager.acceptsWorkerConnections()).isTrue();

        // The worker never claimed a tenant; the node assigns one when it has work.
        WorkerHandle handle = await(manager.acquire("acme", null));
        assertThat(handle).isInstanceOf(SocketWorkerHandle.class);
        assertThat(handle.spawned()).as("a container worker was not spawned by the node").isFalse();
        assertThat(handle.boundTenantId()).isEqualTo("acme");
        assertThat(manager.openWorkerCount()).isZero();
        assertThat(manager.connectedWorkerCount()).as("it is still connected, now bound").isEqualTo(1);
    }

    @Test
    @DisplayName("A connected worker reserves node memory from connect and gives it back when it goes (D-169)")
    void aConnectedWorkerReservesMemoryWhileItIsOpen() throws Exception {
        manager = manager(1L * 1024 * 1024 * 1024);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        // It declares --heap-mb 64 and --threads 4, so the node reserves 64 × 4 MB plus native overhead (D-169).
        long expected = (64L * 4 + 128L) * 1024L * 1024L;
        NetSocket socket = await(connectAndSayHello("s3cret", 64, 4));
        awaitUntil("the reservation to be taken", () -> manager.workersMemoryUsed() == expected);

        socket.close();
        awaitUntil("the reservation to be given back", () -> manager.workersMemoryUsed() == 0L);
        awaitUntil("the connection to be forgotten", () -> manager.connectedWorkerCount() == 0);
    }

    @Test
    @DisplayName("A worker that presents the wrong secret is refused, and never becomes a worker (D-168)")
    void aWrongSecretIsRefused() throws Exception {
        manager = manager(1L * 1024 * 1024 * 1024);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        NetSocket socket = await(client.connect(socketPath));
        sockets.add(socket);
        CountDownLatch closed = new CountDownLatch(1);
        socket.closeHandler(v -> closed.countDown());
        await(socket.write(FrameCodec.encode(new HelloMessage("javet", "2.0.0", 4242L, "not-the-secret", 64, 4).toFrame())));

        assertThat(closed.await(10, TimeUnit.SECONDS))
                .as("the node must close a connection that presents the wrong secret").isTrue();
        assertThat(manager.openWorkerCount()).isZero();
        assertThat(manager.connectedWorkerCount()).isZero();
        assertThat(manager.workersMemoryUsed()).as("a refused connection reserves nothing").isZero();
    }

    @Test
    @DisplayName("The node accepts only as many worker connections as it is configured for (D-166)")
    void theConnectionCapIsEnforced() throws Exception {
        manager = manager(1L * 1024 * 1024 * 1024);
        await(manager.listenForWorkers(socketPath, "s3cret", 1));

        await(connectAndSayHello("s3cret", 64, 4));
        awaitUntil("the first connection to become an open worker", () -> manager.openWorkerCount() == 1);

        // A second one says hello too, so an acceptance would show up as a second worker. (The node's refusal is not
        // observed through the client's close handler: it can close before the handler is registered.)
        await(connectAndSayHello("s3cret", 64, 4));
        Thread.sleep(500);

        assertThat(manager.connectedWorkerCount())
                .as("a connection past C93_WORKER_CONNECTIONS_MAX is refused, so it never joins").isEqualTo(1);
        assertThat(manager.openWorkerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A job runs end to end on a worker that connected over the socket: RUN, host calls, RESULT_RUN (D-166)")
    void aJobRunsOnAConnectedWorker() throws Exception {
        manager = manager(1L * 1024 * 1024 * 1024);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        // A real worker, as a container runs it: it presents the secret, says hello, and then serves whatever the
        // node dispatches over the same connection. Nothing here is a process the node started (D-167).
        NetSocket socket = await(client.connect(socketPath));
        sockets.add(socket);
        var connection = new cluster93.runner.protocol.client.WorkerClientConnection(socket, socket);
        var engine = new cluster93.runtime.javet.JavetScriptRuntime(vertx, 64, 2);
        var daemon = new cluster93.runner.protocol.client.WorkerDaemon(connection, engine, "javet", "s3cret", 64, 2);
        await(daemon.start());
        awaitUntil("the worker to become an open worker", () -> manager.openWorkerCount() == 1);

        WorkerHandle handle = await(manager.acquire("acme", null));

        SharedDataKvStore kvStore = new SharedDataKvStore(vertx);
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, kvStore, null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "acme", "job-socket-1", false);
        Execution execution = new Execution("job-socket-1", "acme", """
                export default async (input, ctx) => {
                  const store = kv.namespace('socket');
                  await store.set('seen', input.name);
                  return { echoed: await store.get('seen'), engine: 'over-a-socket' };
                };
                """, new JsonObject().put("name", "parity"), Duration.ofSeconds(10),
                JobContext.firstAttempt("job-socket-1", OrphanPolicy.FAIL));

        ExecutionResult result = await(handle.run(1, execution, hostApis, (level, line) -> { }, StateSink.DISCARD));

        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCEEDED);
        JsonObject output = (JsonObject) result.output();
        assertThat(output.getString("echoed")).isEqualTo("parity");
        assertThat(kvStore.get("acme", "socket", "seen").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS))
                .as("the worker's host call really reached this node's store").isEqualTo("parity");
    }

    @Test
    @DisplayName("A worker is handed the egress policy it decides under when it is bound, on refresh, and on a re-bind (D-173)")
    void aBoundWorkerIsHandedItsPolicy() throws Exception {
        // One worker node-wide, kept warm: so the second tenant can only be served by re-binding this one (D-172).
        java.util.concurrent.atomic.AtomicLong versions = new java.util.concurrent.atomic.AtomicLong();
        manager = new TenantWorkerPoolManager(vertx, Map.of(), "javet", 0, 1, 1, 2, 64,
                TenantWorkerPool.ReusePolicy.REUSE_SAME_TENANT, new WorkerCgroups(), NodeRpcAuthorizer.ALLOW_ALL,
                1L * 1024 * 1024 * 1024)
                .workerPolicy(tenant -> Future.succeededFuture(new PolicyMessage(tenant, Placement.WORKER, versions.incrementAndGet(),
                        60_000L, false, List.of(),
                        new JsonObject().put("posture", "allow-list"),
                        new JsonObject().put("rules", new JsonArray().add(new JsonObject()
                                .put("action", "allow").put("to", "api." + tenant + ".example")
                                .put("ports", new JsonArray().add(443)))),
                        List.of("10.0.0.5"), List.of(9393, 9394))));
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        // A real worker, as a container runs it.
        NetSocket socket = await(client.connect(socketPath));
        sockets.add(socket);
        var connection = new cluster93.runner.protocol.client.WorkerClientConnection(socket, socket);
        var engine = new cluster93.runtime.javet.JavetScriptRuntime(vertx, 64, 2);
        var daemon = new cluster93.runner.protocol.client.WorkerDaemon(connection, engine, "javet", "s3cret", 64, 2);
        await(daemon.start());
        awaitUntil("the worker to become an open worker", () -> manager.openWorkerCount() == 1);

        assertThat(daemon.egressPolicy().fresh()).as("nothing has been bound yet").isFalse();

        // Bound: the policy arrives with the BIND, before any job can run.
        WorkerHandle handle = await(manager.acquire("acme", null));
        awaitUntil("the worker to take acme's policy", () -> daemon.egressPolicy().policy() != null);
        PolicyMessage acme = daemon.egressPolicy().policy();
        assertThat(acme.tenantId()).isEqualTo("acme");
        assertThat(acme.placement()).isEqualTo(Placement.WORKER);
        assertThat(daemon.egressPolicy().fresh()).isTrue();

        // And it is the policy the *decision* runs — the same code the node runs, on the worker's own addresses.
        java.net.InetAddress publicAddress = java.net.InetAddress.getByName("93.184.216.34");
        java.net.InetAddress nodeAddress = java.net.InetAddress.getByName("10.0.0.5");
        Decision.Verdict allowed = daemon.egressPolicy().nodes()
                .compose(nodes -> daemon.egressPolicy().cluster().compose(cluster -> daemon.egressPolicy().tenant("acme")
                        .map(tenant -> Decision.decide("api.acme.example", List.of(publicAddress), 443,
                                EgressProtocol.HTTP, nodes, daemon.egressPolicy().allowPrivate(), cluster, tenant))))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(allowed.address()).as("acme's rule allows exactly what it names").isNotNull();

        Decision.Verdict refused = daemon.egressPolicy().nodes()
                .compose(nodes -> daemon.egressPolicy().cluster().map(cluster -> Decision.decide("node", List.of(nodeAddress),
                        9394, EgressProtocol.HTTP, nodes, false, cluster, TenantEgress.none("acme"))))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(refused.refused()).as("the cluster's own addresses travelled with the policy")
                .isEqualTo(Decision.Reason.CLUSTER_NODE);

        // A rule change reaches a worker that is already bound, and carries a higher version.
        long before = acme.version();
        manager.refreshWorkerPolicies(null);
        awaitUntil("the refreshed policy to arrive", () -> daemon.egressPolicy().policy().version() > before);

        // Re-bound to another tenant (D-172): it must decide under that tenant's rules, not acme's.
        manager.release("acme", null, handle);
        await(manager.acquire("globex", null));
        awaitUntil("globex's policy to replace acme's", () -> "globex".equals(daemon.egressPolicy().policy().tenantId()));
        assertThat(daemon.egressPolicy().policy().tenantPolicy().getJsonArray("rules").getJsonObject(0).getString("to"))
                .as("the rules the worker now holds are the bound tenant's").isEqualTo("api.globex.example");
    }

    @Test
    @DisplayName("A worker that predates the policy frame is sent none, so a node upgrade cannot kill it (D-173)")
    void aWorkerThatCannotTakeAPolicyIsSentNone() throws Exception {
        java.util.concurrent.atomic.AtomicInteger built = new java.util.concurrent.atomic.AtomicInteger();
        manager = manager(1L * 1024 * 1024 * 1024)
                .workerPolicy(tenant -> {
                    built.incrementAndGet();
                    return Future.succeededFuture(null);
                });
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        // A worker of the version before the policy frame: its HELLO does not advertise one.
        await(connectAndSayHello("s3cret", 64, 4));
        awaitUntil("the connection to become an open worker", () -> manager.openWorkerCount() == 1);
        WorkerHandle handle = await(manager.acquire("acme", null));

        assertThat(handle.channel().acceptsEgressPolicy()).as("it never said it could apply one").isFalse();
        assertThat(built.get()).as("the node does not even build a policy for it").isZero();
        assertThat(handle.isAlive()).as("and a frame it cannot parse was never sent to it").isTrue();
    }

    @Test
    @DisplayName("An idle connected worker is released cooperatively: GOODBYE, exit 0, and its books move once (D-187)")
    void anIdleConnectedWorkerIsReleased() throws Exception {
        // Keep an idle worker warm rather than retiring it: D-187 releases a worker the pool would otherwise reuse.
        manager = new TenantWorkerPoolManager(vertx, Map.of(), "javet", 0, 4, 8, 4, 64,
                TenantWorkerPool.ReusePolicy.REUSE_SAME_TENANT, new WorkerCgroups(), NodeRpcAuthorizer.ALLOW_ALL,
                1L * 1024 * 1024 * 1024).releaseGraceMs(100);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        // A real worker, as a container runs it, so the release is decided on the worker side too.
        NetSocket socket = await(client.connect(socketPath));
        sockets.add(socket);
        var connection = new cluster93.runner.protocol.client.WorkerClientConnection(socket, socket);
        var engine = new cluster93.runtime.javet.JavetScriptRuntime(vertx, 64, 2);
        var daemon = new cluster93.runner.protocol.client.WorkerDaemon(connection, engine, "javet", "s3cret", 64, 2);
        await(daemon.start());
        awaitUntil("the worker to become an open worker", () -> manager.openWorkerCount() == 1);

        WorkerHandle handle = await(manager.acquire("acme", null));
        manager.release("acme", null, handle); // the run-level signal, as if its last job had finished
        awaitUntil("the worker to be warm", () -> manager.workersWarm() == 1);
        assertThat(manager.workersMemoryUsed()).isPositive();

        WorkerRelease released = await(manager.releaseIdle(null, "acme", "over capacity for this node"));

        assertThat(released.workerId()).isEqualTo(handle.workerId());
        assertThat(released.tenant()).isEqualTo("acme");
        assertThat(released.trigger()).isEqualTo("manual");
        // The worker's side of the contract: GOODBYE completes released(), which is what makes WorkerMain exit 0.
        await(daemon.released());
        awaitUntil("the released worker to leave", () -> manager.connectedWorkerCount() == 0);
        assertThat(manager.workersMemoryUsed()).as("freed exactly once, on the close").isZero();
        assertThat(handle.deathCause()).isEqualTo("released by the node: over capacity for this node");
    }

    @Test
    @DisplayName("A worker that has been taken for work is not idle, and the floor is respected (D-187)")
    void aBusyWorkerAndTheFloorAreRefused() throws Exception {
        manager = manager(1L * 1024 * 1024 * 1024).releaseGraceMs(100);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        NetSocket socket = await(client.connect(socketPath));
        sockets.add(socket);
        var connection = new cluster93.runner.protocol.client.WorkerClientConnection(socket, socket);
        var engine = new cluster93.runtime.javet.JavetScriptRuntime(vertx, 64, 2);
        var daemon = new cluster93.runner.protocol.client.WorkerDaemon(connection, engine, "javet", "s3cret", 64, 2);
        await(daemon.start());
        awaitUntil("the worker to become an open worker", () -> manager.openWorkerCount() == 1);

        WorkerHandle handle = await(manager.acquire("acme", null));

        assertThatThrownBy(() -> await(manager.releaseIdle(null, null, "not idle")))
                .cause().isInstanceOf(WorkerRelease.Refused.class)
                .satisfies(cause -> assertThat(((WorkerRelease.Refused) cause).code()).isEqualTo("worker_busy"));
        assertThatThrownBy(() -> await(manager.releaseIdle(handle.workerId(), null, "not idle")))
                .cause().isInstanceOf(WorkerRelease.Refused.class)
                .satisfies(cause -> assertThat(((WorkerRelease.Refused) cause).code()).isEqualTo("worker_busy"));
        assertThat(handle.releasing()).as("a refused release is undone").isFalse();
        assertThat(manager.connectedWorkerCount()).isEqualTo(1);

        manager.releasePolicy(1, 0, 0);
        assertThatThrownBy(() -> await(manager.releaseIdle(null, null, "below the floor")))
                .cause().isInstanceOf(WorkerRelease.Refused.class)
                .satisfies(cause -> assertThat(((WorkerRelease.Refused) cause).code()).isEqualTo("below_floor"));
    }

    @Test
    @DisplayName("Automatic release sheds one worker past the threshold and stops there (D-187)")
    void automaticReleaseShedsOnePastTheThreshold() throws Exception {
        manager = manager(1L * 1024 * 1024 * 1024).releasePolicy(0, 1, 100).releaseGraceMs(100);
        await(manager.listenForWorkers(socketPath, "s3cret", 8));

        await(connectAndSayHello("s3cret", 64, 4));
        await(connectAndSayHello("s3cret", 64, 4));
        awaitUntil("both connections to become workers", () -> manager.connectedWorkerCount() == 2);

        awaitUntil("one automatic release", () -> manager.connectedWorkerCount() == 1);
        Thread.sleep(400);
        assertThat(manager.connectedWorkerCount())
                .as("one per cooldown, and only while above the threshold").isEqualTo(1);
        assertThat(manager.workersMemoryUsed()).as("the released worker's reservation is given back").isEqualTo(
                (64L * 4 + 128L) * 1024L * 1024L);
    }

    private TenantWorkerPoolManager manager(long maxWorkerMemoryBytes) {
        return new TenantWorkerPoolManager(vertx, Map.of(), "javet",
                0, 4, 8, 4, 64, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new WorkerCgroups(),
                NodeRpcAuthorizer.ALLOW_ALL, maxWorkerMemoryBytes);
    }

    /** Connects and says hello, as a worker container's {@code worker-* ipc --socket} does. */
    private Future<NetSocket> connectAndSayHello(String secret, int heapMb, int maxJobs) {
        return client.connect(socketPath).compose(socket -> {
            sockets.add(socket);
            return socket.write(FrameCodec.encode(
                    new HelloMessage("javet", "2.0.0", 4242L, secret, heapMb, maxJobs).toFrame())).map(socket);
        });
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
