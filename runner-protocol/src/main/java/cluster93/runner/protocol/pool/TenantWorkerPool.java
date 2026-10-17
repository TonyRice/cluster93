package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.message.PolicyMessage;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import cluster93.runner.protocol.transport.StreamBridge;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervises a pool of isolated, warm tenant worker processes (D-127, §7).
 * Pre-spawns workers in UNBOUND state and binds them to tenants on demand, enforcing cgroup v2 memory limits.
 */
public class TenantWorkerPool {

    private static final Logger LOG = LoggerFactory.getLogger(TenantWorkerPool.class);

    /** How long to wait for a worker's hello by default; override with {@code cluster93.worker.helloTimeoutMs}. */
    public static final long DEFAULT_HELLO_TIMEOUT_MS = 30_000L;

    public enum ReusePolicy {
        FRESH_PER_JOB,
        REUSE_SAME_TENANT
    }

    private final Vertx vertx;
    private final String binaryPath;
    private final String mode;
    private final int minWarm;
    private final int maxTotal;
    /** How many jobs one worker may run at once (D-155). 1 keeps the old one-job-per-worker behaviour. */
    private final int maxJobsPerWorker;
    private final int heapMb;
    private final ReusePolicy reusePolicy;
    private final WorkerCgroups cgroupLimiter;
    private final cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer;
    /** How long a freshly spawned worker may take to say hello before it is given up on (D-135). */
    private final long helloTimeoutMs;
    /** The node-wide allowance every tenant's pool draws from (D-156); null means this pool is the only one. */
    private final WorkerBudget nodeBudget;
    /** The node-wide memory allowance every tenant's pool draws from (D-163); null means this pool is the only one. */
    private final WorkerMemoryBudget memoryBudget;
    /** Who arbitrates between tenants when capacity is contended (D-161); null when this pool is the only one. */
    private NodeArbiter arbiter;

    private final Queue<WorkerHandle> warmUnboundQueue = new ArrayDeque<>();
    private final Map<String, List<WorkerHandle>> tenantWarmQueue = new ConcurrentHashMap<>();
    /** Active workers by {@link WorkerHandle#workerId()} — a spawned worker's id carries its pid, and a connection has none. */
    private final Map<String, WorkerHandle> activeWorkers = new ConcurrentHashMap<>();
    /**
     * Waiters, per tenant. Insertion-ordered, so the earliest-arrived tenant wins a tie: a single global FIFO let a
     * tenant that queued ten requests be served all ten before a tenant that queued one, which starves it.
     */
    private final Map<String, Deque<PendingAcquire>> pendingByTenant = new LinkedHashMap<>();
    /** How many workers each tenant is currently running, for reporting how the pool is shared out. */
    private final Map<String, Integer> activeByTenant = new HashMap<>();
    /**
     * Jobs in flight per worker. Owned by the pool, not the handle: the handle's own count is decremented when its
     * run future completes, which races {@link #release}, so it cannot answer "does this worker still have work".
     * A worker leaves {@link #activeWorkers} when this reaches zero, and not before (D-155).
     */
    private final Map<String, Integer> jobsInFlight = new HashMap<>();
    private final AtomicInteger totalWorkers = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record PendingAcquire(String tenantId, Promise<WorkerHandle> promise) {}

    /** Signals that the node-wide worker cap is reached and no idle worker could be reaped, so a job must wait. */
    static final class NodeAtCapacityException extends RuntimeException {
        NodeAtCapacityException() {
            super("The node is at its worker cap");
        }
    }

    public TenantWorkerPool(Vertx vertx) {
        this(vertx, System.getProperty("cluster93.worker.binary"), null, 2, 16, 128, ReusePolicy.FRESH_PER_JOB, new WorkerCgroups(), cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, WorkerCgroups cgroupLimiter) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, WorkerCgroups cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, WorkerCgroups cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this(vertx, binaryPath, mode, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer, null, 1);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, WorkerCgroups cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer, WorkerBudget nodeBudget) {
        this(vertx, binaryPath, mode, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget, 1);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, WorkerCgroups cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer, WorkerBudget nodeBudget, int maxJobsPerWorker) {
        this(vertx, binaryPath, mode, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget, maxJobsPerWorker, null);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, WorkerCgroups cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer, WorkerBudget nodeBudget, int maxJobsPerWorker, WorkerMemoryBudget memoryBudget) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.binaryPath = binaryPath;
        this.mode = (mode == null || mode.isBlank()) ? "javet" : mode.toLowerCase(Locale.ROOT);
        this.minWarm = Math.max(0, minWarm);
        this.maxTotal = Math.max(1, maxTotal);
        this.maxJobsPerWorker = Math.max(1, maxJobsPerWorker);
        this.heapMb = Math.max(16, heapMb);
        this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy");
        this.cgroupLimiter = Objects.requireNonNull(cgroupLimiter, "cgroupLimiter");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.nodeBudget = nodeBudget;
        this.memoryBudget = memoryBudget;
        this.helloTimeoutMs = Math.max(1L, Long.getLong("cluster93.worker.helloTimeoutMs", DEFAULT_HELLO_TIMEOUT_MS));

        // Maintain warm unbound pool
        ensureWarmPool();
    }

    /** The memory one worker reserves: its cgroup's hard limit, the same sum {@link #spawnWorker} applies (D-163). */
    private long workerMemoryBytes() {
        return ((long) heapMb * maxJobsPerWorker + 128L) * 1024L * 1024L;
    }

    /**
     * Takes one worker's share of the node's allowances — its slot in the worker count (D-156) and its memory
     * (D-163) — rolling the count back if the memory did not fit, so a partial take cannot leak. Package-private so
     * the memory ceiling can be tested without spawning a process.
     */
    boolean acquireNodeAllowance(long workerBytes) {
        if (nodeBudget != null && !nodeBudget.tryAcquire()) {
            return false;
        }
        if (memoryBudget != null && !memoryBudget.tryAcquire(workerBytes)) {
            if (nodeBudget != null) {
                nodeBudget.release();
            }
            return false;
        }
        return true;
    }

    public int minWarm() {
        return minWarm;
    }

    public int maxTotal() {
        return maxTotal;
    }

    public int totalWorkers() {
        return totalWorkers.get();
    }

    /** How many jobs this pool believes are running on the worker (D-155); 0 once it is idle or retired. */
    synchronized int jobsOn(WorkerHandle worker) {
        return jobsInFlight.getOrDefault(worker.workerId(), 0);
    }

    public synchronized int warmUnboundCount() {
        return warmUnboundQueue.size();
    }

    /** How many workers this pool is running for its tenant right now (D-155), for the snapshot and its gauges. */
    synchronized int activeWorkerCount() {
        int count = 0;
        for (int workers : activeByTenant.values()) {
            count += workers;
        }
        return count;
    }

    /** How many jobs this pool's workers are running at once right now. */
    synchronized int jobsInFlightCount() {
        int count = 0;
        for (int jobs : jobsInFlight.values()) {
            count += jobs;
        }
        return count;
    }

    /** How many acquires are waiting for a worker (a sibling must free a slot, or the node must have room). */
    synchronized int pendingCount() {
        int count = 0;
        for (Deque<PendingAcquire> queue : pendingByTenant.values()) {
            count += queue.size();
        }
        return count;
    }

    /** How many idle workers this pool keeps warm — bound to its tenant, or unbound and still free to bind. */
    synchronized int warmCount() {
        int count = warmUnboundQueue.size();
        for (List<WorkerHandle> warm : tenantWarmQueue.values()) {
            count += warm.size();
        }
        return count;
    }

    /**
     * A reading of this pool for its tenant, or null when it has no tenant yet (only unbound warm workers). A pool is
     * per tenant (D-155), so there is at most one.
     */
    synchronized WorkerPoolSnapshot.TenantWorkers tenantUsage() {
        String tenant = tenantOfPool();
        if (tenant == null) {
            return null;
        }
        long memoryBytes = (long) (activeWorkerCount() + warmCount()) * workerMemoryBytes();
        return new WorkerPoolSnapshot.TenantWorkers(tenant, activeWorkerCount(), jobsInFlightCount(),
                pendingCount(), warmCount(), memoryBytes, budgetEnforcement());
    }

    /** The tenant this pool serves: one has run a job, is waiting for a worker, or is kept warm. */
    private String tenantOfPool() {
        for (String tenant : activeByTenant.keySet()) {
            return tenant;
        }
        for (String tenant : pendingByTenant.keySet()) {
            return tenant;
        }
        for (List<WorkerHandle> warm : tenantWarmQueue.values()) {
            if (!warm.isEmpty()) {
                WorkerHandle worker = warm.get(0);
                return worker.boundTenantId();
            }
        }
        return null;
    }

    /**
     * Which layer enforces this tenant's memory budget (D-157): GraalJS bounds each context in the engine, while
     * Javet's engine limit does not count external memory, so the cgroup is the tenant budget there.
     */
    public String budgetEnforcement() {
        return "graaljs".equalsIgnoreCase(mode) ? "engine" : "cgroup";
    }

    public WorkerCgroups cgroupLimiter() {
        return cgroupLimiter;
    }

    /** The configured (or auto-discovered) binary path, which may be null when JVM fallback is in play. */
    public String binaryPath() {
        return binaryPath;
    }

    /** The engine mode this pool's workers are built for (e.g. "javet" or "graaljs"). */
    public String mode() {
        return mode;
    }

    /**
     * Acquires a worker for the specified tenant, binding it if newly taken from the unbound pool.
     */
    public synchronized Future<WorkerHandle> acquire(String tenantId) {
        if (closed.get()) {
            return Future.failedFuture(new IllegalStateException("Worker pool is closed"));
        }

        // 1. A worker of this tenant that still has room takes the job, so one process runs several jobs at once
        //    (D-155). With a one-job worker hasCapacity() is false while it runs, so this changes nothing until
        //    workers are constructed with a higher limit — which is the config half of this step.
        for (WorkerHandle active : activeWorkers.values()) {
            if (tenantId.equals(active.boundTenantId()) && active.isAlive() && active.hasCapacity()) {
                workerBecameActive(active.workerId(), tenantId);
                // This claim took a free job slot on a shared worker, so the pool may still have room for a claim
                // already queued behind it. Keep the queue moving (D-170).
                drainQueueLater();
                return Future.succeededFuture(active);
            }
        }

        // 2. If reuse is enabled, check if we already have a bound idle worker for this tenant
        if (reusePolicy == ReusePolicy.REUSE_SAME_TENANT) {
            List<WorkerHandle> list = tenantWarmQueue.get(tenantId);
            if (list != null && !list.isEmpty()) {
                WorkerHandle worker = list.remove(list.size() - 1);
                if (worker.isAlive()) {
                    jobStarted(worker, tenantId);
                    return Future.succeededFuture(worker);
                } else {
                    recycleWorker(worker);
                }
            }
        }

        // 2. Try taking from warm unbound pool
        while (!warmUnboundQueue.isEmpty()) {
            WorkerHandle warm = warmUnboundQueue.poll();
            if (warm.isAlive()) {
                jobStarted(warm, tenantId);
                ensureWarmPool();
                // The worker is bound now: a job that queued while it was being taken can use its spare slots.
                return bindAndArm(warm, tenantId)
                        .onSuccess(v -> processNextPending())
                        .onFailure(err -> poison(warm, "bind failed: " + err.getMessage()));
            } else {
                recycleWorker(warm);
            }
        }

        // 2b. An open worker the node accepted from a worker container (D-166): supply a deployment created is used
        //     before the node spawns a process of its own, and it is bound to this tenant here — the worker never
        //     chose one. It holds no worker-process allowance slot, but it is one of this pool's workers (D-169).
        WorkerHandle open = arbiter == null ? null : arbiter.takeOpenWorker(tenantId);
        if (open != null) {
            totalWorkers.incrementAndGet();
            jobStarted(open, tenantId);
            return bindAndArm(open, tenantId)
                    .onSuccess(v -> processNextPending())
                    .onFailure(err -> poison(open, "bind failed: " + err.getMessage()));
        }

        // 2c. An idle worker another tenant is holding: re-bind it rather than start another process (D-172). A worker
        //     whose last run has finished is safe to move — its calls are authorised per run, and its cgroup moves with
        //     it — so an idle process goes back to serving whoever has work instead of being replaced.
        WorkerHandle adopted = arbiter == null ? null : arbiter.takeIdleWorkerFor(tenantId);
        if (adopted != null) {
            adopt(adopted, adopted.boundTenantId(), tenantId);
            return bindAndArm(adopted, tenantId)
                    .onSuccess(v -> processNextPending())
                    .onFailure(err -> poison(adopted, "re-bind failed: " + err.getMessage()));
        }

        // 3. Can we spawn a new worker right now?
        if (totalWorkers.get() < maxTotal) {
            Promise<WorkerHandle> promise = Promise.promise();
            spawnWorker(true, tenantId).onComplete(ar -> {
                if (ar.succeeded()) {
                    WorkerHandle spawned = ar.result();
                    synchronized (TenantWorkerPool.this) {
                        jobStarted(spawned, tenantId);
                    }
                    bindAndArm(spawned, tenantId)
                            .onSuccess(v -> {
                                promise.complete(spawned);
                                // The new worker is bound and has spare slots: a job that queued while it was
                                // starting (because this spawn held the tenant's only worker slot) can now run.
                                processNextPending();
                            })
                            .onFailure(err -> {
                                poison(spawned, "bind failed: " + err.getMessage());
                                promise.fail(err);
                            });
                } else if (ar.cause() instanceof NodeAtCapacityException) {
                    // Every worker on the node is busy and none is idle to reap, so this job waits rather than
                    // failing: the next worker to free capacity serves it (D-156).
                    synchronized (TenantWorkerPool.this) {
                        pendingByTenant.computeIfAbsent(tenantId, k -> new ArrayDeque<>())
                                .add(new PendingAcquire(tenantId, promise));
                        if (arbiter != null) {
                            arbiter.tenantWaiting(tenantId);
                        }
                    }
                } else {
                    promise.fail(ar.cause());
                }
            });
            return promise.future();
        }

        // 4. At capacity: queue the request
        Promise<WorkerHandle> promise = Promise.promise();
        pendingByTenant.computeIfAbsent(tenantId, k -> new ArrayDeque<>()).add(new PendingAcquire(tenantId, promise));
        if (arbiter != null) {
            arbiter.tenantWaiting(tenantId);
        }
        return promise.future();
    }

    /**
     * Releases a worker after one of its jobs finishes.
     *
     * <p>This is the <em>run-level</em> signal: a job that failed, timed out or was cancelled frees only its own
     * slot. A worker holds several jobs at once (D-155), so a job finishing is not the worker finishing — while it
     * has others it stays active, its tenant still counts it, and nothing is freed for the arbiter to hand out.
     * The job's <em>outcome</em> never retires the worker; only the worker's own health does, and that is checked
     * here so a dead worker still cannot answer for the jobs on it.
     */
    public synchronized void release(WorkerHandle worker) {
        if (worker == null) {
            return;
        }
        if (!worker.isAlive()) {
            poison(worker, "worker process is gone");
            return;
        }
        if (worker.oomKilled()) {
            poison(worker, "worker was OOM-killed");
            return;
        }
        int remaining = jobsInFlight.merge(worker.workerId(), -1, Integer::sum);
        if (remaining > 0) {
            // Other jobs are still running on it, so the worker stays active — but a slot just freed, and a job
            // waiting for capacity may fit on this very worker. Serve the queue now: waiting for the worker to go
            // idle is a hang, because a tenant's worker is often kept busy by long-running siblings (D-155).
            processNextPending();
            return;
        }
        retire(worker, false, "job finished");
    }

    /**
     * Retires a worker that is itself unhealthy — its process died, it was OOM-killed, its channel broke, or it
     * failed to bind. Unlike a job finishing, this goes even when the worker still has jobs on it: they are already
     * lost (D-155). This is the only path that may take down a worker with live siblings, so it is named for the
     * worker, not the job.
     */
    public synchronized void poison(WorkerHandle worker, String reason) {
        retire(worker, true, reason);
    }

    /**
     * Takes a worker out of service, freeing its tenant's slot and either keeping it warm or reaping it. Idempotent:
     * a worker already retired is not counted down twice, so a stray second release cannot drive the tenant's
     * worker count negative.
     */
    private void retire(WorkerHandle worker, boolean poisoned, String reason) {
        if (worker == null) {
            return;
        }
        Integer held = jobsInFlight.remove(worker.workerId());
        boolean wasActive = activeWorkers.remove(worker.workerId()) != null;
        if (!wasActive && !poisoned) {
            // A plain release for a worker this pool is not tracking: nothing to free.
            return;
        }
        if (wasActive) {
            countActive(worker.boundTenantId(), -1);
        }
        boolean recycle = poisoned || reusePolicy == ReusePolicy.FRESH_PER_JOB;
        if (poisoned) {
            LOG.warn("Recycling worker {} (tenant {}): {} ({} job(s) were still on it)",
                    worker.workerId(), tenantOf(worker), reason, held == null ? 0 : held);
        } else if (recycle) {
            LOG.debug("Recycling worker {}: {} (reuse policy {})", worker.workerId(), reason, reusePolicy);
        }
        if (recycle) {
            recycleWorker(worker);
        } else {
            // Keep warm: an idle worker is a spare process this tenant may use again, and one the arbiter may hand to
            // another tenant that has work (D-172) — it is only ever re-bound while it has nothing running.
            tenantWarmQueue.computeIfAbsent(worker.boundTenantId(), k -> new ArrayList<>()).add(worker);
        }

        if (arbiter != null) {
            // The arbiter orders tenants between pools; this pool still serves its own queue, so a tenant that is
            // not in the rotation cannot strand a request here. Replacing this with the arbiter alone needs the
            // per-tenant worker (work order step 2), or a waiting request can go unserved.
            arbiter.tenantWaiting(worker.boundTenantId());
        }
        processNextPending();
        ensureWarmPool();
    }

    private synchronized void processNextPending() {
        if (pendingByTenant.isEmpty()) {
            return;
        }
        // One tenant per pool, so its own backlog is plain FIFO; who wins *between* tenants is the arbiter's job.
        Map.Entry<String, Deque<PendingAcquire>> head = pendingByTenant.entrySet().iterator().next();
        String tenantId = head.getKey();
        Deque<PendingAcquire> queue = head.getValue();
        PendingAcquire next = queue.poll();
        if (queue.isEmpty()) {
            pendingByTenant.remove(tenantId);
        }
        acquire(next.tenantId()).onComplete(ar -> {
            if (ar.succeeded()) {
                next.promise().complete(ar.result());
            } else {
                next.promise().fail(ar.cause());
            }
        });
    }

    /**
     * Binds a worker to a tenant and hands it the egress policy it is to decide under (D-173), in that order: a worker
     * must never run a job before it knows the rules it is meant to apply.
     */
    private Future<WorkerHandle> bindAndArm(WorkerHandle worker, String tenantId) {
        return worker.bind(tenantId, new JsonObject().put("heapMb", heapMb), new JsonObject())
                .map(v -> {
                    sendPolicy(worker, tenantId);
                    return worker;
                });
    }

    /**
     * Hands a bound worker the policy for {@code tenantId} (D-173), when this node has one to send. A node that decides
     * egress itself sends nothing: a worker given no policy refuses to connect anywhere on its own.
     */
    private void sendPolicy(WorkerHandle worker, String tenantId) {
        NodeArbiter arbiterOf = arbiter;
        if (arbiterOf == null || worker == null || !worker.isAlive()) {
            return;
        }
        PolicyMessage policy = arbiterOf.policyFor(tenantId);
        if (policy == null) {
            return;
        }
        worker.channel().send(policy.toFrame()).onFailure(err ->
                LOG.debug("Worker {} did not take its egress policy: {}", worker.workerId(), err.getMessage()));
    }

    /**
     * Re-sends the policy to every worker this pool has bound (D-173): after a rule change, a membership change, or as
     * a policy's expiry nears. {@code tenantId} limits it to one tenant, or null for all of them.
     */
    synchronized void refreshPolicy(String tenantId) {
        java.util.Set<WorkerHandle> bound = new java.util.HashSet<>(activeWorkers.values());
        for (List<WorkerHandle> warm : tenantWarmQueue.values()) {
            bound.addAll(warm);
        }
        for (WorkerHandle worker : bound) {
            if (tenantId == null || tenantId.equals(worker.boundTenantId())) {
                sendPolicy(worker, worker.boundTenantId());
            }
        }
    }

    /**
     * Takes an idle worker <b>from another tenant</b> into this pool and re-binds it (D-172). The node-wide allowances
     * (worker count and memory, D-156/D-165) stay with the worker — they were taken when it was spawned and are given
     * back when it dies — so only this pool's own count and the worker's cgroup move.
     */
    synchronized void adopt(WorkerHandle worker, String fromTenant, String tenantId) {
        totalWorkers.incrementAndGet();
        if (worker instanceof ProcessWorkerHandle process && fromTenant != null) {
            // The tenant is the CPU boundary (D-158), so the worker moves cgroups with its new tenant.
            process.movedToTenant(tenantId);
            cgroupLimiter.cleanup(fromTenant, process.pid());
        }
        jobStarted(worker, tenantId);
    }

    /**
     * Hands one idle worker of this pool to the manager, so another tenant's pool can re-bind it (D-172), or null when
     * this pool has none to spare. Only a worker with nothing left on it is offered: the warm queue holds workers whose
     * last run has finished, which is exactly the drain the re-bind requires. One that died while it sat idle is
     * recycled here — where its own books are — rather than handed over.
     */
    synchronized WorkerHandle offerIdleWorker() {
        for (List<WorkerHandle> warm : tenantWarmQueue.values()) {
            while (!warm.isEmpty()) {
                WorkerHandle idle = warm.remove(warm.size() - 1);
                if (idle.isAlive() && idle.state() != WorkerHandle.State.TERMINATED) {
                    totalWorkers.decrementAndGet();
                    LOG.info("Handing idle worker {} to another tenant that has work for it", idle.workerId());
                    return idle;
                }
                recycleWorker(idle);
            }
        }
        return null;
    }

    /**
     * Serves one more waiting claim, on this pool's context, when any are queued. A claim can be satisfied by taking a
     * free job slot on a shared worker ({@link #acquire}, step 1) without freeing anything, so serving it may leave room
     * for a claim queued behind it. Without this drain, the claim just served was the last one until something
     * <em>released</em> capacity — and a worker whose siblings are long-running (a service, say) never releases any, so
     * the rest of the queue waited for good (D-170). Deferred rather than immediate: the claim just served takes its
     * slot only when its job starts, which the handle counts then, so draining inline could hand the same slot out twice.
     */
    private void drainQueueLater() {
        synchronized (this) {
            if (pendingByTenant.isEmpty() || closed.get()) {
                return;
            }
        }
        vertx.runOnContext(v -> {
            synchronized (TenantWorkerPool.this) {
                processNextPending();
            }
        });
    }


    /**
     * Records a worker starting a job for a tenant: it becomes the tenant's active worker, counting the worker once
     * however many jobs it holds (D-155). Shared by every path that hands out a worker, so the bookkeeping cannot
     * drift; package-private so the release/poison rules can be tested without spawning a process.
     */
    synchronized void jobStarted(WorkerHandle handle, String tenantId) {
        activeWorkers.put(handle.workerId(), handle);
        workerBecameActive(handle.workerId(), tenantId);
    }

    /** Records a job starting on a worker, counting the worker itself once however many jobs it holds (D-155). */
    private void workerBecameActive(String workerId, String tenantId) {
        if (jobsInFlight.merge(workerId, 1, Integer::sum) == 1) {
            countActive(tenantId, 1);
        }
    }

    /** Tracks how many workers a tenant is running, for reporting how the pool is shared out. */
    private void countActive(String tenantId, int delta) {
        if (tenantId == null) {
            return;
        }
        activeByTenant.merge(tenantId, delta, Integer::sum);
        if (activeByTenant.get(tenantId) <= 0) {
            activeByTenant.remove(tenantId);
        }
    }

    private synchronized void ensureWarmPool() {
        if (closed.get()) {
            return;
        }
        while (warmUnboundQueue.size() < minWarm && totalWorkers.get() < maxTotal) {
            // spawnWorker counts the attempt against maxTotal and logs its own failure (with the worker's stderr).
            spawnWorker(false, null).onComplete(ar -> {
                if (ar.succeeded()) {
                    synchronized (TenantWorkerPool.this) {
                        if (closed.get()) {
                            recycleWorker(ar.result());
                        } else {
                            warmUnboundQueue.add(ar.result());
                            processNextPending();
                        }
                    }
                }
            });
        }
    }

    private Future<WorkerHandle> spawnWorker(boolean forWork, String tenantId) {
        Promise<WorkerHandle> promise = Promise.promise();
        // Counted from the moment the spawn starts, so maxTotal bounds in-flight workers too. Released by
        // recycleWorker on failure, or when the handle is recycled after use (D-135).
        totalWorkers.incrementAndGet();
        long workerBytes = workerMemoryBytes();
        if (!acquireNodeAllowance(workerBytes)) {
            // The node is at its worker cap or its memory cap (D-156, D-163). A spawn for work preempts warmth: reap
            // an idle worker from any tenant's pool and take its slot and memory, so an idle worker can never block
            // a job. A spawn for warmth does not — reaping one idle worker to make another would be pointless. If
            // every worker is busy, the acquire queues the job (NodeAtCapacityException) rather than failing it.
            boolean freed = forWork && arbiter != null && arbiter.freeIdleWorker();
            if (!freed || !acquireNodeAllowance(workerBytes)) {
                totalWorkers.decrementAndGet();
                promise.fail(new NodeAtCapacityException());
                return promise.future();
            }
        }
        try {
            List<String> command = new ArrayList<>();
            String resolvedBinary = (binaryPath != null && !binaryPath.isBlank()) ? resolveBinary(binaryPath, mode) : null;
            if (resolvedBinary == null) {
                resolvedBinary = resolveBinary(null, mode);
            }
            if (resolvedBinary != null) {
                command.add(resolvedBinary);
                command.add("ipc");
                command.add("--heap-mb");
                command.add(String.valueOf(heapMb));
                // The worker may run this many jobs at once, each in its own context (D-155). WorkerMain defaults
                // to 2, so this is what actually decides it.
                command.add("--threads");
                command.add(String.valueOf(maxJobsPerWorker));
            } else {
                String javaBin = ProcessHandle.current().info().command()
                        .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
                command.add(javaBin);
                command.add("-cp");
                command.add(resolveWorkerClasspath());
                command.add("cluster93.worker.WorkerMain");
                command.add("ipc");
                command.add("--heap-mb");
                command.add(String.valueOf(heapMb));
                // The worker may run this many jobs at once, each in its own context (D-155). WorkerMain defaults
                // to 2, so this is what actually decides it.
                command.add("--threads");
                command.add(String.valueOf(maxJobsPerWorker));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            long pid = process.pid();

            // Apply Linux cgroup memory bounds. A worker runs up to maxJobsPerWorker jobs at once, each with its own
            // heapMb budget (D-155, D-157), so the process needs room for all of them plus native overhead. Sizing
            // it for a single job OOM-killed a shared worker (exit 137) the moment a second job ran — the engine
            // reports a per-job budget and the cgroup has to hold the sum.
            // A worker's cgroup is per tenant because the tenant is the CPU boundary (D-158): its cpu.max bounds the
            // tenant's aggregate, and the worker is a child holding the memory bound (D-163). A warm spawn (no
            // tenant yet) applies nothing; with minWarm 0 that does not happen in practice.
            if (tenantId != null) {
                long jobsMb = (long) heapMb * maxJobsPerWorker;
                long maxBytes = (jobsMb + 128L) * 1024L * 1024L;
                long highBytes = (jobsMb + 64L) * 1024L * 1024L;
                cgroupLimiter.applyTenantCpu(tenantId);
                cgroupLimiter.applyWorker(tenantId, pid, maxBytes, highBytes);
            }

            WriteStream<io.vertx.core.buffer.Buffer> output = StreamBridge.writeStream(vertx, process.getOutputStream());
            ReadStream<io.vertx.core.buffer.Buffer> input = StreamBridge.readStream(vertx, process.getInputStream());

            String workerId = "worker-" + pid;
            NodeWorkerChannel channel = new NodeWorkerChannel(workerId, output, input, new cluster93.runner.protocol.flow.CreditManager(), authorizer);
            ProcessWorkerHandle handle = new ProcessWorkerHandle(process, channel, maxJobsPerWorker, cgroupLimiter, workerBytes);
            // Drain stderr immediately: the OS pipe buffer is 64 KiB, and a worker that logs more than that before
            // its first run would block forever writing to a full pipe (D-128). The tail is kept so a worker that
            // dies before its hello can say why (D-135).
            drainStderr(handle, process.getErrorStream());
            // Reap the worker when its connection ends, whatever the run bookkeeping says. Without this a worker that
            // dies holds its slot (and the node's budget) for good, and with a small allowance the queue stalls.
            channel.onClosed(err -> {
                synchronized (TenantWorkerPool.this) {
                    boolean reaped = handle.state() != WorkerHandle.State.TERMINATED;
                    recycleWorker(handle);
                    if (reaped) {
                        LOG.warn("Worker {} closed; its slot is freed", workerId);
                        if (arbiter != null) {
                            arbiter.capacityFreed();
                        } else {
                            processNextPending();
                        }
                    }
                }
            });

            // A worker must say hello; if it dies first, or never answers, fail the acquire instead of waiting forever.
            AtomicBoolean settled = new AtomicBoolean(false);
            long timerId = vertx.setTimer(helloTimeoutMs, id -> {
                if (settled.compareAndSet(false, true)) {
                    LOG.warn("Worker {} (pid {}) didn't say hello within {} ms{}", workerId, pid, helloTimeoutMs, handle.stderrTail());
                    recycleWorker(handle);
                    promise.fail(new IllegalStateException("Worker " + workerId + " didn't say hello within " + helloTimeoutMs + " ms"));
                }
            });
            channel.awaitHello().onComplete(ar -> {
                if (settled.compareAndSet(false, true)) {
                    vertx.cancelTimer(timerId);
                    if (ar.succeeded()) {
                        promise.complete(handle);
                    } else {
                        // Name the death, not just the pipe: the worker is gone, so its exit status says why (D-159).
                        // Read it before recycleWorker, which destroys the process and takes the exit code with it.
                        String reason = WorkerExit.runFailure(process, ar.cause());
                        LOG.warn("Worker {} (pid {}) failed to start: {}{}", workerId, pid, reason, handle.stderrTail());
                        recycleWorker(handle);
                        promise.fail(new IllegalStateException(reason));
                    }
                }
            });

        } catch (Exception e) {
            totalWorkers.decrementAndGet();
            if (nodeBudget != null) {
                nodeBudget.release();
            }
            if (memoryBudget != null) {
                memoryBudget.release(workerBytes);
            }
            LOG.warn("Worker didn't start: {}", e.getMessage());
            promise.fail(e);
        }
        return promise.future();
    }

    /**
     * Reaps one idle worker so a job that needs a worker can spawn, releasing its slot to the node-wide budget. Only
     * a worker with no jobs on it is ever taken: a bound worker goes to its tenant's warm queue when its last job
     * ends ({@link #retire}), and an unbound warm worker has never run one. Returns false when nothing is idle —
     * every live worker is running a job, so the caller queues the job instead.
     */
    synchronized boolean reapIdleWorker() {
        WorkerHandle idle = warmUnboundQueue.poll();
        if (idle == null) {
            for (List<WorkerHandle> warm : tenantWarmQueue.values()) {
                if (!warm.isEmpty()) {
                    idle = warm.remove(warm.size() - 1);
                    break;
                }
            }
        }
        if (idle == null) {
            return false;
        }
        LOG.info("Reaping idle worker {} to make room for work (node is at its worker cap)", idle.workerId());
        recycleWorker(idle);
        return true;
    }

    private void recycleWorker(WorkerHandle worker) {
        // Each handle is counted once (in spawnWorker) and released once; terminate() is idempotent and marks the
        // handle, so a repeat recycle is a no-op (D-135).
        if (worker == null || worker.state() == WorkerHandle.State.TERMINATED) {
            return;
        }
        totalWorkers.decrementAndGet();
        // A spawned worker holds a slot of the node's worker-count allowance; a connected one does not, because the
        // node cannot refuse it without its supervisor reconnecting — connections are bounded by their own accept cap
        // (D-166). Memory is the unit both share, so both give that back (D-163, D-169).
        if (worker.holdsWorkerSlot() && nodeBudget != null) {
            nodeBudget.release();
        }
        if (memoryBudget != null) {
            memoryBudget.release(worker.reservedBytes());
        }
        // A worker that died on its own is a crash, and it is counted against its tenant (D-159). One we terminate
        // ourselves — an idle reap, a close — is still alive here, so nothing is counted.
        Integer exit = worker.exitCode();
        if (exit != null && exit != 0 && worker.boundTenantId() != null) {
            cluster93.core.metrics.Metrics.of(vertx).workerCrashed(worker.boundTenantId());
        }
        String rendered = worker.stderrTail();
        if (!rendered.isEmpty()) {
            LOG.warn("Worker {} (tenant {}) stderr tail:{}", worker.workerId(), tenantOf(worker), rendered);
        }
        worker.terminate();
    }

    /** The tenant a worker is bound to, or "unbound" for one that never took a job. */
    private static String tenantOf(WorkerHandle worker) {
        String tenant = worker.boundTenantId();
        return tenant == null ? "unbound" : tenant;
    }

    /** Drains a spawned worker's stderr into its tail, so a worker that dies before its hello can say why (D-135). */
    private static void drainStderr(ProcessWorkerHandle handle, java.io.InputStream stderr) {
        Thread.ofVirtual().name("worker-stderr-" + handle.pid()).start(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stderr))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handle.addStderr(line);
                    LOG.debug("[worker {}] {}", handle.workerId(), line);
                }
            } catch (IOException ignored) {
                // the pipe closed
            }
        });
    }


    /** The arbiter that decides between tenants (D-161); set once, by the manager that owns this pool. */
    TenantWorkerPool arbiter(NodeArbiter arbiter) {
        this.arbiter = arbiter;
        return this;
    }

    /** Whether this tenant has requests waiting for node capacity. */
    synchronized boolean hasPending() {
        return !pendingByTenant.isEmpty();
    }

    /** Serves this tenant's next waiting request, because the arbiter chose it (D-161). */
    synchronized void pump() {
        processNextPending();
    }

    /**
     * Closes the pool and terminates all warm and active worker processes.
     */
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            for (WorkerHandle warm : warmUnboundQueue) {
                recycleWorker(warm);
            }
            warmUnboundQueue.clear();

            for (List<WorkerHandle> list : tenantWarmQueue.values()) {
                for (WorkerHandle w : list) {
                    recycleWorker(w);
                }
            }
            tenantWarmQueue.clear();

            for (WorkerHandle active : activeWorkers.values()) {
                recycleWorker(active);
            }
            activeWorkers.clear();
            jobsInFlight.clear();

            for (Deque<PendingAcquire> queue : pendingByTenant.values()) {
                for (PendingAcquire pending : queue) {
                    pending.promise().fail(new IllegalStateException("Worker pool closed"));
                }
            }
            pendingByTenant.clear();
        }
    }

    // --- Binary auto-discovery and child-JVM classpath resolution (D-129) ---

    /**
     * Resolves the executable worker binary for a mode. An explicit binary is honoured first; otherwise the native
     * worker binaries laid out by the build script ({@code bin/worker-<engine>},
     * {@code benchmarks/engines/bin/worker-<engine>}, {@code worker/target/worker-<engine>}) are searched,
     * walking up from the working directory so a server launched from any subdirectory still finds the repository root.
     *
     * @param explicitBinary an operator-configured binary path, or null/blank to auto-discover
     * @param mode           the worker engine mode (e.g. "javet" or "graaljs"); null/blank defaults to "javet"
     * @return the resolved executable path, or null when none was found
     */
    public static String resolveBinary(String explicitBinary, String mode) {
        return resolveBinary(explicitBinary, mode, Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    static String resolveBinary(String explicitBinary, String mode, Path baseDir) {
        String normalized = (mode == null || mode.isBlank()) ? "javet" : mode.toLowerCase(Locale.ROOT);
        if (explicitBinary != null && !explicitBinary.isBlank()) {
            Path resolved = firstExecutable(baseDir, explicitBinary.trim());
            return resolved == null ? null : resolved.toString();
        }
        for (String candidate : List.of(
                "bin/worker-" + normalized,
                "bin/worker-javet",
                "benchmarks/engines/bin/worker-" + normalized,
                "worker/target/worker-" + normalized)) {
            Path resolved = firstExecutable(baseDir, candidate);
            if (resolved != null) {
                return resolved.toString();
            }
        }
        return null;
    }

    /**
     * Resolves a single candidate path (absolute, or relative to the working directory) to an executable regular file,
     * walking up parent directories from the working directory. Returns null when no executable file is found.
     */
    public static String resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path resolved = firstExecutable(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(), path);
        return resolved == null ? null : resolved.toString();
    }

    /**
     * Builds the {@code -cp} value a child JVM needs to run {@code cluster93.worker.WorkerMain}: the current JVM's
     * classpath, plus the worker module's jar (or its compiled classes) when not already present.
     *
     * @return the classpath for the child JVM
     */
    public static String resolveWorkerClasspath() {
        return resolveWorkerClasspath(System.getProperty("java.class.path", ""),
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    static String resolveWorkerClasspath(String baseClassPath, Path baseDir) {
        List<String> entries = new ArrayList<>();
        // The worker's own classes and resources go first, so its logback.xml (which routes logging to stderr and keeps
        // stdout free for frames) shadows the server's when the worker runs as a child JVM on the server's classpath.
        Path workerTarget = findWorkerTargetDir(baseDir);
        if (workerTarget != null) {
            List<Path> jars = workerJars(workerTarget);
            if (!jars.isEmpty()) {
                for (Path jar : jars) {
                    addIfAbsent(entries, jar.toAbsolutePath().normalize().toString());
                }
            } else {
                Path classes = workerTarget.resolve("classes");
                if (Files.isDirectory(classes)) {
                    addIfAbsent(entries, classes.toAbsolutePath().normalize().toString());
                }
            }
        }
        if (baseClassPath != null && !baseClassPath.isBlank()) {
            for (String entry : baseClassPath.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    addIfAbsent(entries, entry);
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Path firstExecutable(Path baseDir, String candidate) {
        Path candidatePath;
        try {
            candidatePath = Path.of(candidate);
        } catch (RuntimeException e) {
            return null;
        }
        if (candidatePath.isAbsolute()) {
            return isExecutable(candidatePath) ? candidatePath.normalize() : null;
        }
        Path dir = baseDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path path = dir.resolve(candidate).normalize();
            if (isExecutable(path)) {
                return path;
            }
            Path parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                return null;
            }
            dir = parent;
        }
        return null;
    }

    private static boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static Path findWorkerTargetDir(Path baseDir) {
        Path dir = baseDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path target = dir.resolve("worker").resolve("target");
            if (Files.isDirectory(target)) {
                return target;
            }
            Path parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                return null;
            }
            dir = parent;
        }
        return null;
    }

    private static List<Path> workerJars(Path workerTarget) {
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(workerTarget)) {
            stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("cluster93-worker-") && name.endsWith(".jar")
                        && !name.contains("-sources") && !name.contains("-javadoc");
            }).sorted().forEach(jars::add);
        } catch (IOException ignored) {
            // The worker module hasn't been built yet; the JVM fallback will simply be missing its main class.
        }
        return jars;
    }

    private static void addIfAbsent(List<String> entries, String path) {
        String normalized = normalize(path);
        if (normalized == null) {
            return;
        }
        for (String entry : entries) {
            if (normalized.equals(normalize(entry))) {
                return;
            }
        }
        entries.add(normalized);
    }

    private static String normalize(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return path;
        }
    }
}
