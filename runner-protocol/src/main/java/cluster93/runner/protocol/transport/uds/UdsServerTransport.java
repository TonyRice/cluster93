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
import java.io.IOException;
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

        // Ensure parent directory exists, and 0700 when we are the ones making it (S3): the socket's directory is the
        // first of the layers that bind a connection, so only the node's user may reach it.
        File socketFile = new File(socketPath);
        if (socketFile.getParentFile() != null) {
            boolean created = socketFile.getParentFile().mkdirs();
            if (created) {
                restrictToOwner(socketFile.getParentFile().toPath());
            }
        }

        server = vertx.createNetServer(new NetServerOptions());
        server.connectHandler(socketHandler);

        SocketAddress address = SocketAddress.domainSocketAddress(socketPath);
        return server.listen(address).<Void>mapEmpty().onSuccess(v -> restrictSocketFile());
    }

    /**
     * Closes the server and deletes the socket file from the filesystem.
     */
    public Future<Void> close() {        if (server != null) {
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

    /** {@code 0700}: the directory the node listens in is reachable only by the node's user (S3). Best effort. */
    private static void restrictToOwner(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | RuntimeException ignored) {
            // Not POSIX, or not ours to change: the operator's install set it (the container path does it up front).
        }
    }

    /**
     * {@code 0600} on the socket itself: the kernel creates it with the process umask, which is often group- or
     * world-connectable — and the socket is the boundary a worker connection crosses, so it must be its owner's
     * alone (S3). Best effort, and after the bind, which is when the file exists.
     */
    private void restrictSocketFile() {
        try {
            Files.setPosixFilePermissions(Path.of(socketPath), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | RuntimeException ignored) {
            LOG.warn("Could not restrict the worker socket {} to its owner; check its mode by hand (S3)", socketPath);
        }
    }
}
