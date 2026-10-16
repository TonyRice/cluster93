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

/**
 * One worker the node may run jobs on: a process it <b>spawned</b> (D-127), or — with the container tier — a
 * <b>connection</b> a deployment-created worker container opened to the node's listening socket (D-166, D-167).
 *
 * <p>The pool treats both the same way. A worker starts {@code UNBOUND} (it has claimed no tenant), the node binds
 * it to whichever tenant has work (D-155), and it runs jobs until it ends. What differs is the host side, and
 * {@link #spawned()} says which: a spawned worker has a {@link #process() process} whose exit status names its death
 * (D-159) and a cgroup the node sets (D-158); a <b>connected</b> worker has neither — it has a connection id, its
 * death is "the connection ended", and its container's {@code --memory}/{@code --cpus} are the deployment's to read
 * and to size (D-166, D-169).
 *
 * <p>So the members only a spawned worker can answer return a harmless default for a connected one
 * ({@link #process()} null, {@link #exitCode()} null, {@link #stderrTail()} empty), and the callers that need them
 * guard with {@link #spawned()}. Everything above a worker's bytes is the same for both: admission, binding,
 * {@code RUN}/{@code RESULT_RUN} and the host-API RPCs do not care what carries them.
 */
public interface WorkerHandle {

    enum State {
        UNBOUND,
        BOUND,
        TERMINATED
    }

    /** How this worker is identified in logs and metrics: {@code worker-<pid>}, or {@code worker-<connection id>}. */
    String workerId();

    /** Whether the node spawned this worker (true) or a worker container connected to it (false) — D-166. */
    boolean spawned();

    State state();

    Instant createdAt();

    Instant lastUsedAt();

    /**
     * The tenant this worker is serving now, or null while it is still open (unbound). A worker serves one tenant at a
     * time, and is re-bound to another when it goes idle (D-172).
     */
    String boundTenantId();

    /** How many jobs this worker may run at once (D-155). */
    int maxJobs();

    /** How many jobs it is running right now. */
    int runningJobs();

    /** Whether this worker is bound and has room for another job (D-155). */
    boolean hasCapacity();

    boolean isAlive();

    /**
     * Takes one of this worker's job slots, or reports that it has none free (D-155). A count rather than a flag,
     * because a worker runs several jobs at once; refused outright when it is not bound or has been terminated.
     *
     * <p>Separate from {@link #run} so the admission rule can be tested without a channel round-trip.
     */
    boolean admitJob();

    /** Gives a job slot back, when a job finishes. */
    void jobFinished();

    /** Binds the worker to a tenant. Transitions state from UNBOUND to BOUND, once. */
    Future<Void> bind(String tenantId, JsonObject limits, JsonObject env);

    /**
     * Executes a job on the worker, alongside any others it is already running — each job gets its own context on
     * the worker, so admission is a count rather than a flag (D-155). Refused when the worker is at its job limit.
     */
    Future<ExecutionResult> run(int channelId, Execution execution, HostApis hostApis, LogSink logs, StateSink stateSink);

    /** Asks the worker to stop a run gracefully. */
    void stop(int channelId, String reason, long graceMs);

    /** Asks the worker to cancel a run. */
    void cancel(int channelId, String reason);

    /** The protocol channel carrying this worker's frames. */
    NodeWorkerChannel channel();

    /** Terminates the worker and frees what it holds on the host: destroys the process, or closes the connection. */
    void terminate();

    /**
     * The node-wide worker memory this worker reserves while it is alive (D-163, D-169): its cgroup size for a
     * spawned worker, and the size a connected worker declared in its {@code HELLO} — its container's cgroup is the
     * deployment's, so the node reserves what it is told rather than what it measures.
     */
    long reservedBytes();

    /**
     * Whether this worker holds a slot of the node's worker-<b>count</b> allowance (D-156). A spawned worker does; a
     * connected one does not, because the node cannot refuse one without its supervisor immediately reconnecting —
     * connections are bounded by {@code C93_WORKER_CONNECTIONS_MAX} instead (D-166).
     */
    default boolean holdsWorkerSlot() {
        return spawned();
    }

    /**
     * How this worker ended, as a phrase a tenant can read (D-159): a spawned worker names its exit
     * ("out of memory or force-killed (SIGKILL)", "segmentation fault (SIGSEGV)", "exited with code N"), and a
     * connected one says only that its connection ended — its container's exit status is the deployment's to read,
     * so no cause is invented for it (D-166, §2.5). Null while the worker is alive, and for a clean exit.
     */
    String deathCause();

    /**
     * Whether the node's cgroup for this worker shows it was OOM-killed (D-163). Always false for a connected
     * worker: it has no cgroup of the node's.
     */
    default boolean oomKilled() {
        return false;
    }

    /**
     * The process the node started, or null for a connected worker. Guard with {@link #spawned()}.
     */
    default Process process() {
        return null;
    }

    /** The process id, or 0 for a connected worker. Guard with {@link #spawned()}. */
    default long pid() {
        return 0L;
    }

    /**
     * The code the process exited with, or null while it is alive — and always null for a connected worker, which
     * has no code to read. Guard with {@link #spawned()}.
     */
    default Integer exitCode() {
        return null;
    }

    /** The last lines this worker wrote to stderr, for a crash log (D-135); "" when it wrote none. */
    default String stderrTail() {
        return "";
    }

    /** Records one line of this worker's stderr (the pool drains it before its first job). */
    default void addStderr(String line) {
    }
}
