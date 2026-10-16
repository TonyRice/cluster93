package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.server.NodeWorkerChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A worker that <b>connected</b> to the node: a worker-container process reaching the node's listening socket
 * (D-166, D-167). It has no {@link Process} — the deployment created the container, and the node holds no engine
 * socket — so it has a connection id instead of a pid, its death is "the connection ended" rather than an exit
 * status, and its memory is the container's cgroup rather than one the node sets.
 *
 * <p>Everything else is a worker like any other: the node binds it to whichever tenant has work (D-155), and it
 * runs jobs over the same frames. See {@link WorkerHandle}.
 */
public class SocketWorkerHandle extends AbstractWorkerHandle {

    private static final Logger LOG = LoggerFactory.getLogger(SocketWorkerHandle.class);

    /** Set when the connection ends, however it ends: the remote closing, a transport error, or this node closing it. */
    private final AtomicBoolean connectionEnded = new AtomicBoolean(false);
    /** Closes the connection: the node's own end of it (the socket). A {@link Runnable} so a test needs no socket. */
    private final Runnable closeConnection;

    /**
     * @param connectionId the node's own name for this connection, unique on the node and stable in logs
     * @param closeConnection closes the connection (the socket the node accepted)
     * @param maxJobs      the jobs this worker may run at once, as the worker announced in {@code HELLO} (its
     *                     {@code --threads}); the deployment sized it, not the node
     * @param reservedBytes the node-wide memory to reserve for it (D-169), from what the worker declared
     */
    public SocketWorkerHandle(long connectionId, Runnable closeConnection, NodeWorkerChannel channel, int maxJobs,
                              long reservedBytes) {
        super(channel, maxJobs, reservedBytes);
        this.closeConnection = closeConnection;
    }

    @Override
    public boolean spawned() {
        return false;
    }

    @Override
    boolean aliveOnHost() {
        return !connectionEnded.get();
    }

    /**
     * Closes the connection. The worker's supervisor will start another one, which reconnects — the node cannot shed
     * a container worker for good, which is why idle control lives with the deployment (D-166, §2.7).
     */
    @Override
    public void terminate() {
        if (!markTerminated()) {
            return;
        }
        connectionEnded.set(true);
        try {
            closeConnection.run();
        } catch (Exception e) {
            LOG.debug("Could not close worker connection {}: {}", workerId(), e.getMessage());
        }
    }

    /** Called when the connection ends, whatever ends it, so the worker stops being reported as alive. */
    public void connectionEnded() {
        connectionEnded.set(true);
    }

    @Override
    public String deathCause() {
        return isAlive() ? null : "connection ended";
    }
}
