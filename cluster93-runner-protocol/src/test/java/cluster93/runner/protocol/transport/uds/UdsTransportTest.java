package cluster93.runner.protocol.transport.uds;

import cluster93.core.runtime.api.HostApis;
import cluster93.core.runtime.api.KvApi;
import cluster93.runner.protocol.client.WorkerClientConnection;
import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.message.CallMessage;
import cluster93.runner.protocol.message.HelloMessage;
import cluster93.runner.protocol.message.ResultMessage;
import cluster93.runner.protocol.message.WelcomeMessage;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UdsTransportTest {

    private Vertx vertx;
    private String socketPath;
    private UdsServerTransport serverTransport;
    private UdsClientTransport clientTransport;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        socketPath = "/tmp/cluster93-test-" + UUID.randomUUID() + ".sock";
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientTransport != null) {
            clientTransport.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (serverTransport != null) {
            serverTransport.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Files.deleteIfExists(Path.of(socketPath));
    }

    @Test
    @DisplayName("Communicates over non-blocking Unix Domain Sockets: Handshake, RPC, and cleanup")
    void testUdsHandshakeAndRpc() throws Exception {
        // Skip on OS if domain sockets aren't supported (Linux & macOS support them natively)
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux") && !os.contains("mac")) {
            return;
        }

        serverTransport = new UdsServerTransport(vertx, socketPath);
        clientTransport = new UdsClientTransport(vertx);

        CompletableFuture<NodeWorkerChannel> serverChannelFuture = new CompletableFuture<>();

        // Start UDS server
        serverTransport.listen(socket -> {
            NodeWorkerChannel channel = new NodeWorkerChannel("worker-uds", socket, socket);
            serverChannelFuture.complete(channel);
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(new File(socketPath).exists(), "Domain socket file should exist on filesystem");

        // Connect UDS client
        var clientSocket = clientTransport.connect(socketPath).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        WorkerClientConnection clientConnection = new WorkerClientConnection(clientSocket, clientSocket);

        NodeWorkerChannel serverChannel = serverChannelFuture.get(5, TimeUnit.SECONDS);

        // 1. Handshake: client sends HELLO, server awaits and responds with WELCOME
        CompletableFuture<WelcomeMessage> welcomeFuture = new CompletableFuture<>();
        clientConnection.setFrameHandler(frame -> {
            if (frame.type() == cluster93.runner.protocol.frame.FrameType.WELCOME) {
                welcomeFuture.complete(WelcomeMessage.fromFrame(frame));
            }
        });

        clientConnection.send(new HelloMessage("javet", "1.0.0", 1234L).toFrame());
        HelloMessage hello = serverChannel.awaitHello().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("javet", hello.engine());
        assertEquals(1234L, hello.pid());

        WelcomeMessage welcome = welcomeFuture.get(5, TimeUnit.SECONDS);
        assertEquals("worker-uds", welcome.workerId());

        // 2. Bind worker
        serverChannel.bind("tenant-alpha", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("tenant-alpha", serverChannel.boundTenantId());

        // Clean shutdown
        clientSocket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        serverTransport.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(new File(socketPath).exists(), "Domain socket file should be deleted on server close");
    }
}
