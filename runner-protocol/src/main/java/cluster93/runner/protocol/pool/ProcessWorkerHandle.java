package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A worker the node <b>spawned</b>: an in-cluster worker process and its binary IPC channel (D-127, §4). It has a
 * pid, a cgroup the node sets (D-158, D-163), and an exit code that names how it died (D-159).
 *
 * <p>See {@link WorkerHandle} for what it shares with a worker that connected to the node instead (D-166).
 */
public class ProcessWorkerHandle extends AbstractWorkerHandle {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessWorkerHandle.class);

    private final Process process;
    /** The cgroup manager that placed this worker, so its cgroup can be removed when it ends; null in tests. */
    private final WorkerCgroups cgroups;
    /** The last lines it wrote to stderr, so a worker that dies can say why (D-135). */
    private final StderrTail stderr = new StderrTail(20);

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
        super(channel, maxJobs, reservedBytes);
        this.process = Objects.requireNonNull(process, "process");
        this.cgroups = cgroups;
    }

    @Override
    public boolean spawned() {
        return true;
    }

    @Override
    public Process process() {
        return process;
    }

    @Override
    public long pid() {
        return process.pid();
    }

    @Override
    boolean aliveOnHost() {
        return process.isAlive();
    }

    /**
     * Terminates the worker process, closes its IPC channels and removes the cgroup it was placed in.
     */
    @Override
    public void terminate() {
        if (!markTerminated()) {
            return;
        }
        try {
            channel().send(Frame.control(FrameType.GOODBYE, new JsonObject()));
        } catch (Exception ignored) {
        }
        try {
            if (process.isAlive()) {
                process.destroy();
                process.destroyForcibly();
            }
        } catch (Exception e) {
            LOG.debug("Error stopping worker process PID {}: {}", pid(), e.getMessage());
        }
        if (cgroups != null) {
            cgroups.cleanup(boundTenantId(), pid());
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
                LOG.error("Worker {} (pid {}) was killed (exit 137): likely OOM or cgroup memory limit", workerId(), pid());
            } else if (code == 139) {
                LOG.error("Worker {} (pid {}) crashed with SIGSEGV (exit 139)", workerId(), pid());
            } else if (code != 0) {
                LOG.warn("Worker {} (pid {}) exited with code {}", workerId(), pid(), code);
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
        return cgroups != null && cgroups.checkOomKilled(boundTenantId(), pid());
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
