package cluster93.runner.protocol.pool;

import cluster93.core.host.NodeHostApis;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.job.JobStatus;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.StateSink;
import cluster93.runner.protocol.frame.FrameCodec;
import cluster93.runner.protocol.message.HelloMessage;
import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import cluster93.runner.protocol.transport.uds.UdsClientTransport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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

        NetSocket second = await(client.connect(socketPath));
        sockets.add(second);
        CountDownLatch closed = new CountDownLatch(1);
        second.closeHandler(v -> closed.countDown());

        assertThat(closed.await(10, TimeUnit.SECONDS))
                .as("a connection past C93_WORKER_CONNECTIONS_MAX is refused").isTrue();
        assertThat(manager.connectedWorkerCount()).isEqualTo(1);
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
