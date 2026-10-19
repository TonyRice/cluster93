package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A clustered node comes up with a worker socket for worker containers (D-182).
 *
 * <p>Clustering is what makes this a case of its own. A clustered {@code Vertx} completes its creation on an event
 * loop, so the node's wiring runs there, and that wiring waits for the worker socket's bind before the node serves.
 * Waiting on an event loop stops the very loop the bind needs: the socket file appeared — the kernel had made it —
 * while the bind never finished, and the node died on the listen timeout with nothing but {@code : null}. One node,
 * its own address as its only member, is enough to show it; the cluster's size was never the point, being clustered
 * was.
 *
 * <p>So the assertion is the bind's own completion, not the file's existence: the file exists either way, but only
 * {@code enforceOwnerOnly} sets the socket to 0600, and that runs only once the bind's future completes (S3). The
 * end-to-end shape — a real worker container joining a real clustered compute node — is case C6 of
 * {@code scripts/worker-isolation-check.sh}; this is the regression that costs seconds and needs no container.
 */
class ClusteredWriterSocketTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static Path socket;

    @BeforeAll
    static void start() throws Exception {
        // The socket lives in a directory only its owner can enter, exactly as S3 requires of one the node is given.
        Path dir = Files.createTempDirectory("c93-clustered-worker-socket");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        socket = dir.resolve("workers").resolve("node.sock");
        Path secretFile = dir.resolve("worker-secret");
        Files.writeString(secretFile, "a-pre-shared-worker-secret");
        Files.setPosixFilePermissions(secretFile, PosixFilePermissions.fromString("r--------"));

        node = await(Cluster93.start(ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("C93_NODE_ID", "clustersocket"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", "15781"),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1"),
                // The whole point: a clustered node that is also the listening end for worker containers (D-166).
                Map.entry("C93_WORKER_SOCKET", socket.toString()),
                Map.entry("C93_WORKER_SECRET_FILE", secretFile.toString()))))));
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
    }

    @Test
    @DisplayName("a clustered node binds its worker socket for its own user alone")
    void bindsItsWorkerSocketToItsOwnerAlone() throws Exception {
        assertThat(socket).exists();
        assertThat(Files.getPosixFilePermissions(socket))
                .as("the socket is its owner's alone (0600): the bind completed and the ownership check ran")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisplayName("and still serves, so the bind is not a step it skipped")
    void servesItsApi() throws Exception {
        assertThat(await(client.get(node.httpPort(), "localhost", "/health").send()).statusCode()).isEqualTo(200);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
