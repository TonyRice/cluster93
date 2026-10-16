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
 * A worker the node <b>spawned</b>: an in-cluster worker process and its binary IPC channel (D-127, §4). It has a
 * pid, a cgroup the node sets (D-158, D-163), and an exit code that names how it died (D-159).
 *
 * <p>See {@link WorkerHandle} for what it shares with a worker that connected to the node instead (D-166).
 */
public class ProcessWorkerHandle implements WorkerHandle {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessWorkerHandle.class);

    private final Process process;
    private final NodeWorkerChannel channel;
    private final long pid;
    private final String workerId;
    private final Instant createdAt;
    private volatile Instant lastUsedAt;
    private final AtomicReference<State> state = new AtomicReference<>(State.UNBOUND);
    /** The cgroup manager that placed this worker, so its cgroup can be removed when it ends; null in tests. */
    private final WorkerCgroups cgroups;
    /** The node-wide memory this worker's cgroup was sized for (D-163); 0 when the caller did not say. */
    private final long reservedBytes;
    /** The last lines it wrote to stderr, so a worker that dies can say why (D-135). */
    private final StderrTail stderr = new StderrTail(20);

    private String boundTenantId;
    /** How many jobs this worker may run at once (D-155): a count, not a flag, since a worker now holds several. */
    private final int maxJobs;
    private final AtomicInteger runningJobs = new AtomicInteger();

    /** A worker that runs one job at a time, as it always did. */
    public ProcessWorkerHandle(Process process, NodeWorkerChannel channel) {
        this(process, channel, 1);
    }

    public ProcessWorkerHandle(Process process, NodeWorkerChannel channel, int maxJobs) {
        this(process, channel, maxJobs, null, 0L);
    }

    /**
     * @param cgroups       the cgroup manager that placed this worker, or null (tests); used to remove its cgroup
     * @param reservedBytes the node-wide memory its cgroup was sized for (D-163), or 0 when the caller did not say
     */
    public ProcessWorkerHandle(Process process, NodeWorkerChannel channel, int maxJobs, WorkerCgroups cgroups,
                               long reservedBytes) {
        this.process = Objects.requireNonNull(process, "process");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.pid = process.pid();
        this.workerId = channel.workerId();
        this.createdAt = Instant.now();
        this.lastUsedAt = createdAt;
        this.maxJobs = Math.max(1, maxJobs);
        this.cgroups = cgroups;
        this.reservedBytes = Math.max(0L, reservedBytes);
    }

    @Override
    public boolean spawned() {
        return true;
    }

    public Process process() {
        return process;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public String workerId() {
        return workerId;
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
    public State state() {
        return state.get();
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
    public boolean isAlive() {
        return process.isAlive() && state.get() != State.TERMINATED;
    }

    @Override
    public long reservedBytes() {
        return reservedBytes;
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

    @Override
    public NodeWorkerChannel channel() {
        return channel;
    }

    /**
     * Terminates the worker process, closes its IPC channels and removes the cgroup it was placed in.
     */
    @Override
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
        if (cgroups != null) {
            cgroups.cleanup(boundTenantId, pid);
        }
    }

    /**
     * The code the process exited with, or null while it is alive. 137 (SIGKILL: the OOM killer, or a plain
     * {@code kill -9}) and 139 (SIGSEGV) are logged as they are read, so a crash names itself in the log (D-159).
     */
    @Override
    public Integer exitCode() {
        try {
            if (process.isAlive()) {
                return null;
            }
            int code = process.exitValue();
            if (code == 137) {
                LOG.error("Worker {} (pid {}) was killed (exit 137): likely OOM or cgroup memory limit", workerId, pid);
            } else if (code == 139) {
                LOG.error("Worker {} (pid {}) crashed with SIGSEGV (exit 139)", workerId, pid);
            } else if (code != 0) {
                LOG.warn("Worker {} (pid {}) exited with code {}", workerId, pid, code);
            }
            return code;
        } catch (IllegalThreadStateException stillRunning) {
            return null;
        }
    }

    @Override
    public String deathCause() {
        return WorkerExit.describe(process);
    }

    @Override
    public boolean oomKilled() {
        return cgroups != null && cgroups.checkOomKilled(boundTenantId, pid);
    }

    @Override
    public String stderrTail() {
        return stderr.render();
    }

    @Override
    public void addStderr(String line) {
        stderr.add(line);
    }

    /** The last few lines a worker wrote to stderr, so a worker that dies can say why (D-135). */
    static final class StderrTail {
        private final int limit;
        private final java.util.Deque<String> lines = new java.util.ArrayDeque<>();

        StderrTail(int limit) {
            this.limit = limit;
        }

        synchronized void add(String line) {
            lines.addLast(line);
            while (lines.size() > limit) {
                lines.removeFirst();
            }
        }

        /** The held lines as a newline-prefixed block, or "" when there are none. */
        synchronized String render() {
            if (lines.isEmpty()) {
                return "";
            }
            return "\n" + String.join("\n", lines);
        }
    }
}
