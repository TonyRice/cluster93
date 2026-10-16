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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side Unix Domain Socket (UDS) transport for high-throughput, non-blocking in-cluster worker IPC (D-127, §4).
 * Binds a Netty NetServer directly to a filesystem .sock node.
 *
 * <p>The socket is the boundary every worker connection crosses, and this node has no peer-credential check to fall
 * back on (S3's {@code SO_PEERCRED} layer is not implemented — D-171), so its filesystem mode <em>is</em> the binding.
 * This transport therefore does not merely try to tighten it: {@link #listen} fails unless the socket ends up its
 * owner's alone in a directory no other user can replace it in. Where that cannot be guaranteed, the node refuses to
 * accept worker containers at all rather than accepting peers it cannot tell apart.
 */
public class UdsServerTransport {

    private static final Logger LOG = LoggerFactory.getLogger(UdsServerTransport.class);

    /** The socket's mode: its owner's read and write, and nobody else's (S3). */
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    /** The directory's mode when this node creates it: only its owner may enter it or replace the socket in it. */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");
    /** The mode bits that let another user write in a directory. */
    private static final int OTHERS_WRITE = 0_022;
    /** The sticky bit: a directory like {@code /tmp}, where another user cannot remove a file it does not own. */
    private static final int STICKY = 0_1000;

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
     *
     * <p>The returned future fails, and the socket is closed again, when the node cannot hold the socket to its own
     * user — see {@link #enforceOwnerOnly()}.
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
        return server.listen(address).<Void>mapEmpty().compose(v -> enforceOwnerOnly());
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

    /** {@code 0700} for a directory this node creates: only its owner may reach the socket (S3). Best effort. */
    private static void restrictToOwner(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR);
        } catch (IOException | RuntimeException ignored) {
            // Left to the check below: unverifiable modes are refused there, not waved through here.
        }
    }

    /**
     * The socket must be its owner's alone, and the bind fails when that cannot be established: the kernel creates it
     * with the process umask (often group- or world-connectable, which is exactly the hole this closes), so it is set
     * to {@code 0600} and then read back and checked. Nothing but the owner may be able to write in its directory
     * either — that is what would let another user remove the socket and bind their own in its place.
     *
     * <p>A failure here means this host cannot tell its own workers from anyone else's, so the node refuses to serve
     * worker containers rather than accepting peers it cannot distinguish. A process node (no
     * {@code C93_WORKER_SOCKET}) is unaffected.
     */
    private Future<Void> enforceOwnerOnly() {
        try {
            Path socket = Path.of(socketPath);
            Files.setPosixFilePermissions(socket, OWNER_ONLY_FILE);
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(socket);
            if (!actual.equals(OWNER_ONLY_FILE)) {
                throw new IllegalStateException("The worker socket " + socketPath + " is "
                        + PosixFilePermissions.toString(actual) + ", not 0600: another user could connect to it, and "
                        + "this node has no peer-credential check to fall back on (S3). Fix its permissions or the "
                        + "filesystem it lives on, or run a process node (don't set C93_WORKER_SOCKET).");
            }
            requireDirectoryIsNotReplaceable(socket.getParent());
            LOG.info("Worker socket {} is {} (S3): only this node's user may connect to it", socketPath,
                    PosixFilePermissions.toString(actual));
            return Future.succeededFuture();
        } catch (RuntimeException | IOException e) {
            // The bind succeeded, so take it back: a node must not serve on a socket it cannot hold.
            return close().compose(ignored -> Future.failedFuture(e));
        }
    }

    /**
     * Nobody but the owner may write in the socket's directory: write permission there is what lets another user
     * remove the socket and bind their own over it. A <b>sticky</b> directory (like {@code /tmp}) is fine, because the
     * kernel then refuses to remove a file the writer does not own.
     */
    private static void requireDirectoryIsNotReplaceable(Path dir) throws IOException {
        if (dir == null) {
            return;
        }
        int mode = unixMode(dir);
        if ((mode & OTHERS_WRITE) != 0 && (mode & STICKY) == 0) {
            throw new IllegalStateException("The worker socket's directory " + dir + " is writable by other users "
                    + "(mode 0" + Integer.toOctalString(mode) + ") and is not sticky, so they could replace the socket "
                    + "with one of their own: make it 0700 and owned by this node's user (S3), or run a process node "
                    + "(don't set C93_WORKER_SOCKET).");
        }
    }

    /** A path's Unix mode, or a refusal: a host that cannot report it cannot prove the socket is its owner's. */
    private static int unixMode(Path path) throws IOException {
        try {
            return ((Number) Files.getAttribute(path, "unix:mode")).intValue();
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IllegalStateException("This filesystem does not report Unix permissions for " + path + ", so the "
                    + "node cannot establish that only its user may reach the worker socket (S3). Run a process node "
                    + "instead (don't set C93_WORKER_SOCKET).", e);
        }
    }
}
