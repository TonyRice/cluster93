package cluster93.runner.protocol.pool;

import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.LogSink;
import cluster93.core.runtime.StateSink;
import cluster93.core.runtime.api.HostApis;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The bookkeeping every worker shares, whatever carries its bytes: the state machine (UNBOUND → BOUND →
 * TERMINATED), the tenant it is bound to, and the job slots it admits (D-155). A subclass says only what makes it a
 * process or a connection — see {@link WorkerHandle}.
 *
 * <p>One implementation on purpose: admission and the bound tenant are where a spawned worker and a connected one
 * must behave identically, and two copies would be two places for them to drift (warned about in the work order's
 * §2.5 as the refactor's main correctness risk).
 */
abstract class AbstractWorkerHandle implements WorkerHandle {

    private final NodeWorkerChannel channel;
    private final String workerId;
    private final int maxJobs;
    private final long reservedBytes;
    private final Instant createdAt;
    /** How many jobs it is running right now (D-155): a count, not a flag, since a worker now holds several. */
    private final AtomicInteger runningJobs = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.UNBOUND);
    private volatile Instant lastUsedAt;
    private String boundTenantId;

    AbstractWorkerHandle(NodeWorkerChannel channel, int maxJobs, long reservedBytes) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.workerId = channel.workerId();
        this.createdAt = Instant.now();
        this.lastUsedAt = createdAt;
        this.maxJobs = Math.max(1, maxJobs);
        this.reservedBytes = Math.max(0L, reservedBytes);
    }

    @Override
    public String workerId() {
        return workerId;
    }

    @Override
    public NodeWorkerChannel channel() {
        return channel;
    }

    @Override
    public State state() {
        return state.get();
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    @Override
    public String boundTenantId() {
        return boundTenantId;
    }

    @Override
    public int maxJobs() {
        return maxJobs;
    }

    @Override
    public int runningJobs() {
        return runningJobs.get();
    }

    @Override
    public boolean hasCapacity() {
        return state.get() == State.BOUND && runningJobs.get() < maxJobs;
    }

    @Override
    public long reservedBytes() {
        return reservedBytes;
    }

    @Override
    public boolean isAlive() {
        return state.get() != State.TERMINATED && aliveOnHost();
    }

    @Override
    public boolean admitJob() {
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

    @Override
    public void jobFinished() {
        runningJobs.decrementAndGet();
    }

    @Override
    public Future<Void> bind(String tenantId, JsonObject limits, JsonObject env) {
        if (!state.compareAndSet(State.UNBOUND, State.BOUND)) {
            return Future.failedFuture(new IllegalStateException("Worker " + workerId + " is already in state " + state.get()));
        }
        this.boundTenantId = tenantId;
        this.lastUsedAt = Instant.now();
        return channel.bind(tenantId, limits, env);
    }

    @Override
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

    @Override
    public void stop(int channelId, String reason, long graceMs) {
        channel.stop(channelId, reason, graceMs);
    }

    @Override
    public void cancel(int channelId, String reason) {
        channel.cancel(channelId, reason);
    }

    /** Marks the worker terminated, answering whether this call was the one that did it. */
    final boolean markTerminated() {
        return state.getAndSet(State.TERMINATED) != State.TERMINATED;
    }

    /** Whether the host side of this worker is still there (a live process, an open connection). */
    abstract boolean aliveOnHost();
}
