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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encapsulates an active in-cluster tenant worker process and its binary IPC channel (D-127, §4).
 */
public class WorkerProcessHandle {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerProcessHandle.class);

    public enum State {
        UNBOUND,
        BOUND,
        BUSY,
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

    public WorkerProcessHandle(Process process, NodeWorkerChannel channel) {
        this.process = Objects.requireNonNull(process, "process");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.pid = process.pid();
        this.workerId = channel.workerId();
        this.createdAt = Instant.now();
        this.lastUsedAt = createdAt;
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

    public boolean isAlive() {
        return process.isAlive() && state.get() != State.TERMINATED;
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
     * Executes a job on the worker. Transitions state from BOUND to BUSY and back to BOUND on completion.
     */
    public Future<ExecutionResult> run(int channelId, Execution execution, HostApis hostApis, LogSink logs, StateSink stateSink) {
        if (!state.compareAndSet(State.BOUND, State.BUSY)) {
            return Future.failedFuture(new IllegalStateException("Worker " + workerId + " cannot run: state is " + state.get()));
        }
        this.lastUsedAt = Instant.now();
        return channel.run(channelId, execution, hostApis, logs, stateSink)
                .onComplete(ar -> {
                    this.lastUsedAt = Instant.now();
                    this.state.compareAndSet(State.BUSY, State.BOUND);
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
