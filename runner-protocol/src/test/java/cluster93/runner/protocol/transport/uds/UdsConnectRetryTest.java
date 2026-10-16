package cluster93.runner.protocol.transport.uds;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The worker's bounded connect retry (D-166, work order §2.3): a deployment starts the node and its worker containers
 * together, so a worker that loses the race waits for the socket instead of dying — but only for so long, and then it
 * fails visibly so the supervisor's restart and the node's log both show it.
 */
class UdsConnectRetryTest {

    private Vertx vertx;
    private UdsClientTransport client;
    private Path dir;
    private String socketPath;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        client = new UdsClientTransport(vertx);
        dir = Files.createTempDirectory("c93-connect-retry");
        socketPath = dir.resolve("node.sock").toString();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        deleteRecursively(dir);
    }

    @Test
    @DisplayName("A worker connects when the node starts listening after it does (D-166)")
    void retriesUntilTheNodeListens() throws Exception {
        // The node is not listening yet: a plain connect would fail here.
        UdsServerTransport server = new UdsServerTransport(vertx, socketPath);
        vertx.setTimer(200, id -> server.listen(socket -> { }));

        NetSocket socket = client.connectRetrying(socketPath, 40, Duration.ofMillis(50), Duration.ofMillis(200))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(socket).isNotNull();
        socket.close();
        server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A worker's retry is bounded: it fails, naming the socket, once its attempts are spent (§2.3)")
    void failsOnceItsAttemptsAreSpent() {
        assertThatThrownBy(() -> client.connectRetrying(socketPath, 3, Duration.ofMillis(10), Duration.ofMillis(20))
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS))
                .hasMessageContaining(socketPath)
                .hasMessageContaining("C93_WORKER_SOCKET");
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
