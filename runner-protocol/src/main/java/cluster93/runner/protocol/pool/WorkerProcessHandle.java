package cluster93.runner.protocol.pool;

import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.LogSink;
import cluster93.core.runtime.StateSink;
import cluster93.core.runtime.api.HostApis;
import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encapsulates an active in-cluster tenant worker process and its binary IPC channel (D-127, §4).
 */
public class WorkerProcessHandle {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerProcessHandle.class);

    public enum State {
        UNBOUND,
        BOUND,
        TERMINATED
    }

    private final Process process;
    private final NodeWorkerChannel channel;
    private final long pid;
    private final String workerId;
    private final Instant createdAt;
    private volatile Instant lastUsedAt;
    private final AtomicReference<State> state = new AtomicReference<>(State.UNBOUND);

    private String boundTenantId;
    /** How many jobs this worker may run at once (D-155): a count, not a flag, since a worker now holds several. */
    private final int maxJobs;
    private final AtomicInteger runningJobs = new AtomicInteger();

    /** A worker that runs one job at a time, as it always did. */
    public WorkerProcessHandle(Process process, NodeWorkerChannel channel) {
        this(process, channel, 1);
    }

    public WorkerProcessHandle(Process process, NodeWorkerChannel channel, int maxJobs) {
        this.process = Objects.requireNonNull(process, "process");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.pid = process.pid();
        this.workerId = channel.workerId();
        this.createdAt = Instant.now();
        this.lastUsedAt = createdAt;
        this.maxJobs = Math.max(1, maxJobs);
    }

    public Process process() {
        return process;
    }

    public NodeWorkerChannel channel() {
        return channel;
    }

    public long pid() {
        return pid;
    }

    public String workerId() {
        return workerId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public State state() {
        return state.get();
    }

    public String boundTenantId() {
        return boundTenantId;
    }

    /** How many jobs this worker may run at once. */
    public int maxJobs() {
        return maxJobs;
    }

    /** How many jobs it is running right now. */
    public int runningJobs() {
        return runningJobs.get();
    }

    /** Whether this worker is bound and has room for another job (D-155). */
    public boolean hasCapacity() {
        return state.get() == State.BOUND && runningJobs.get() < maxJobs;
    }

    public boolean isAlive() {
        return process.isAlive() && state.get() != State.TERMINATED;
    }

    /**
     * Takes one of this worker's job slots, or reports that it has none free (D-155). A count rather than a flag,
     * because a worker runs several jobs at once; refused outright when it is not bound or has been terminated.
     *
     * <p>Separate from {@link #run} so the admission rule can be tested without a channel round-trip.
     */
    boolean admitJob() {
        if (state.get() != State.BOUND) {
            return false;
        }
        while (true) {
            int current = runningJobs.get();
            if (current >= maxJobs) {
                return false;
            }
            if (runningJobs.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Gives a job slot back, when a job finishes. */
    void jobFinished() {
        runningJobs.decrementAndGet();
    }

    /**
     * Binds the worker to a tenant. Transitions state from UNBOUND to BOUND.
     */
    public Future<Void> bind(String tenantId, JsonObject limits, JsonObject env) {
        if (!state.compareAndSet(State.UNBOUND, State.BOUND)) {
            return Future.failedFuture(new IllegalStateException("Worker " + workerId + " is already in state " + state.get()));
        }
        this.boundTenantId = tenantId;
        this.lastUsedAt = Instant.now();
        return channel.bind(tenantId, limits, env);
    }

    /**
     * Executes a job on the worker, alongside any others it is already running — each job gets its own context on
     * the worker, so admission is a count rather than a flag (D-155). Refused when the worker is at its job limit.
     */
    public Future<ExecutionResult> run(int channelId, Execution execution, HostApis hostApis, LogSink logs, StateSink stateSink) {
        if (!admitJob()) {
            return Future.failedFuture(new IllegalStateException(state.get() != State.BOUND
                    ? "Worker " + workerId + " cannot run: state is " + state.get()
                    : "Worker " + workerId + " is at its job limit (" + maxJobs + ")"));
        }
        this.lastUsedAt = Instant.now();
        return channel.run(channelId, execution, hostApis, logs, stateSink)
                .onComplete(ar -> {
                    this.lastUsedAt = Instant.now();
                    jobFinished();
                });
    }

    /**
     * Stops or cancels execution.
     */
    public void stop(int channelId, String reason, long graceMs) {
        channel.stop(channelId, reason, graceMs);
    }

    public void cancel(int channelId, String reason) {
        channel.cancel(channelId, reason);
    }

    /**
     * Terminates the worker process and closes its IPC channels.
     */
    public void terminate() {
        if (state.getAndSet(State.TERMINATED) == State.TERMINATED) {
            return;
        }
        try {
            channel.send(Frame.control(FrameType.GOODBYE, new JsonObject()));
        } catch (Exception ignored) {
        }
        try {
            if (process.isAlive()) {
                process.destroy();
                process.destroyForcibly();
            }
        } catch (Exception e) {
            LOG.debug("Error stopping worker process PID {}: {}", pid, e.getMessage());
        }
    }
}
