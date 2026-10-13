package cluster93.runner.protocol.transport.uds;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Server-side Unix Domain Socket (UDS) transport for high-throughput, non-blocking in-cluster worker IPC (D-127, §4).
 * Binds a Netty NetServer directly to a filesystem .sock node.
 */
public class UdsServerTransport {

    private static final Logger LOG = LoggerFactory.getLogger(UdsServerTransport.class);

    private final Vertx vertx;
    private final String socketPath;
    private NetServer server;

    public UdsServerTransport(Vertx vertx, String socketPath) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
    }

    public String socketPath() {
        return socketPath;
    }

    /**
     * Binds to the domain socket and invokes the handler for each connected worker socket.
     */
    public Future<Void> listen(Handler<NetSocket> socketHandler) {
        Objects.requireNonNull(socketHandler, "socketHandler");

        // Clean up any stale socket file left from previous process termination
        cleanupSocketFile();

        // Ensure parent directory exists
        File socketFile = new File(socketPath);
        if (socketFile.getParentFile() != null) {
            socketFile.getParentFile().mkdirs();
        }

        server = vertx.createNetServer(new NetServerOptions());
        server.connectHandler(socketHandler);

        SocketAddress address = SocketAddress.domainSocketAddress(socketPath);
        return server.listen(address).mapEmpty();
    }

    /**
     * Closes the server and deletes the socket file from the filesystem.
     */
    public Future<Void> close() {
        if (server != null) {
            return server.close().onComplete(ar -> cleanupSocketFile());
        } else {
            cleanupSocketFile();
            return Future.succeededFuture();
        }
    }

    private void cleanupSocketFile() {
        try {
            Files.deleteIfExists(Path.of(socketPath));
        } catch (Exception e) {
            LOG.debug("Could not remove socket file {}: {}", socketPath, e.getMessage());
        }
    }
}
