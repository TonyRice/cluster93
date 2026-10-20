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
    /** Set when the node has asked this worker to leave (D-187): it is no longer placeable, and not yet closed. */
    private final AtomicBoolean releasing = new AtomicBoolean(false);
    private volatile String releaseReason;
    private volatile String releaseTrigger;
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
     * Whether the node has asked this worker to leave (D-187). Such a worker is marked unplaceable before the request
     * is sent, so a job cannot be dispatched between the decision and the GOODBYE.
     */
    public boolean releasing() {
        return releasing.get();
    }

    /** The reason this worker was released, or null when it was not. */
    public String releaseReason() {
        return releaseReason;
    }

    /** {@code manual} or {@code automatic}: a bounded label for the release metric (D-187). */
    public String releaseTrigger() {
        return releaseTrigger;
    }

    /**
     * Marks this worker for release before anything is sent. Atomic with {@link #admitJob()}: either a job is admitted
     * first (and the release refuses), or the release is claimed first (and no job can start). A worker with a run
     * already on it is never released.
     *
     * @return whether this call claimed the release; false when it is busy, already releasing, or terminated
     */
    public synchronized boolean beginRelease(String reason, String trigger) {
        if (releasing.get() || runningJobs() > 0 || state() == WorkerHandle.State.TERMINATED) {
            return false;
        }
        releaseReason = reason;
        releaseTrigger = trigger;
        releasing.set(true);
        return true;
    }

    /** Undoes {@link #beginRelease} when the worker turned out not to be in a releasable place after all. */
    public synchronized void cancelRelease() {
        if (runningJobs() == 0) {
            releasing.set(false);
            releaseReason = null;
            releaseTrigger = null;
        }
    }

    @Override
    public synchronized boolean admitJob() {
        // The release marks the worker unplaceable before it sends GOODBYE (D-187), so a run cannot start in between.
        return !releasing.get() && super.admitJob();
    }

    @Override
    public boolean hasCapacity() {
        return !releasing.get() && super.hasCapacity();
    }

    /**
     * Ends a released worker that did not leave on its own. This closes the connection <b>without</b> marking the
     * handle terminated: the connection's close handler is what reaps it through its pool, and that path is what
     * gives the memory and the connection slot back exactly once (D-187).
     */
    public void closeAfterRelease() {
        if (!releasing.get() || connectionEnded.get()) {
            return;
        }
        connectionEnded.set(true);
        try {
            closeConnection.run();
        } catch (Exception e) {
            LOG.debug("Could not close released worker connection {}: {}", workerId(), e.getMessage());
        }
    }

    /**
     * Closes the connection. The worker's supervisor will start another one, which reconnects — the node cannot shed
     * a container worker for good, which is why idle control lives with the deployment (D-166, §2.7). A cooperative
     * release uses {@link #closeAfterRelease()} instead (D-187).
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
        if (isAlive()) {
            return null;
        }
        String reason = releaseReason;
        return reason == null ? "connection ended" : "released by the node: " + reason;
    }
}
