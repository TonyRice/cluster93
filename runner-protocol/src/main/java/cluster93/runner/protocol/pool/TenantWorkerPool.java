package cluster93.runner.protocol.pool;

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
    private final CgroupMemoryLimiter cgroupLimiter;
    private final cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer;
    /** How long a freshly spawned worker may take to say hello before it is given up on (D-135). */
    private final long helloTimeoutMs;
    /** The node-wide allowance every tenant's pool draws from (D-156); null means this pool is the only one. */
    private final WorkerBudget nodeBudget;
    /** Who arbitrates between tenants when capacity is contended (D-161); null when this pool is the only one. */
    private NodeArbiter arbiter;

    private final Queue<WorkerProcessHandle> warmUnboundQueue = new ArrayDeque<>();
    private final Map<String, List<WorkerProcessHandle>> tenantWarmQueue = new ConcurrentHashMap<>();
    private final Map<Long, WorkerProcessHandle> activeWorkers = new ConcurrentHashMap<>();
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
    private final Map<Long, Integer> jobsInFlight = new HashMap<>();
    /** The last few stderr lines each worker wrote, so a worker that dies can say why (D-135, D-159). */
    private final Map<Long, StderrTail> stderrByPid = new ConcurrentHashMap<>();
    private final AtomicInteger totalWorkers = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record PendingAcquire(String tenantId, Promise<WorkerProcessHandle> promise) {}

    /** Signals that the node-wide worker cap is reached and no idle worker could be reaped, so a job must wait. */
    static final class NodeAtCapacityException extends RuntimeException {
        NodeAtCapacityException() {
            super("The node is at its worker cap");
        }
    }

    public TenantWorkerPool(Vertx vertx) {
        this(vertx, System.getProperty("cluster93.worker.binary"), null, 2, 16, 128, ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this(vertx, binaryPath, mode, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer, null, 1);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer, WorkerBudget nodeBudget) {
        this(vertx, binaryPath, mode, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget, 1);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer, WorkerBudget nodeBudget, int maxJobsPerWorker) {
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
        this.helloTimeoutMs = Math.max(1L, Long.getLong("cluster93.worker.helloTimeoutMs", DEFAULT_HELLO_TIMEOUT_MS));

        // Maintain warm unbound pool
        ensureWarmPool();
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
    synchronized int jobsOn(WorkerProcessHandle worker) {
        return jobsInFlight.getOrDefault(worker.pid(), 0);
    }

    public synchronized int warmUnboundCount() {
        return warmUnboundQueue.size();
    }

    public CgroupMemoryLimiter cgroupLimiter() {
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
    public synchronized Future<WorkerProcessHandle> acquire(String tenantId) {
        if (closed.get()) {
            return Future.failedFuture(new IllegalStateException("Worker pool is closed"));
        }

        // 1. A worker of this tenant that still has room takes the job, so one process runs several jobs at once
        //    (D-155). With a one-job worker hasCapacity() is false while it runs, so this changes nothing until
        //    workers are constructed with a higher limit — which is the config half of this step.
        for (WorkerProcessHandle active : activeWorkers.values()) {
            if (tenantId.equals(active.boundTenantId()) && active.isAlive() && active.hasCapacity()) {
                workerBecameActive(active.pid(), tenantId);
                return Future.succeededFuture(active);
            }
        }

        // 2. If reuse is enabled, check if we already have a bound idle worker for this tenant
        if (reusePolicy == ReusePolicy.REUSE_SAME_TENANT) {
            List<WorkerProcessHandle> list = tenantWarmQueue.get(tenantId);
            if (list != null && !list.isEmpty()) {
                WorkerProcessHandle worker = list.remove(list.size() - 1);
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
            WorkerProcessHandle warm = warmUnboundQueue.poll();
            if (warm.isAlive()) {
                jobStarted(warm, tenantId);
                ensureWarmPool();
                return warm.bind(tenantId, new JsonObject().put("heapMb", heapMb), new JsonObject())
                        .map(v -> warm)
                        .onFailure(err -> poison(warm, "bind failed: " + err.getMessage()));
            } else {
                recycleWorker(warm);
            }
        }

        // 3. Can we spawn a new worker right now?
        if (totalWorkers.get() < maxTotal) {
            Promise<WorkerProcessHandle> promise = Promise.promise();
            spawnWorker(true).onComplete(ar -> {
                if (ar.succeeded()) {
                    WorkerProcessHandle spawned = ar.result();
                    synchronized (TenantWorkerPool.this) {
                        jobStarted(spawned, tenantId);
                    }
                    spawned.bind(tenantId, new JsonObject().put("heapMb", heapMb), new JsonObject())
                            .onSuccess(v -> promise.complete(spawned))
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
        Promise<WorkerProcessHandle> promise = Promise.promise();
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
    public synchronized void release(WorkerProcessHandle worker) {
        if (worker == null) {
            return;
        }
        if (!worker.isAlive()) {
            poison(worker, "worker process is gone");
            return;
        }
        if (cgroupLimiter.checkOomKilled(worker.pid())) {
            poison(worker, "worker was OOM-killed");
            return;
        }
        int remaining = jobsInFlight.merge(worker.pid(), -1, Integer::sum);
        if (remaining > 0) {
            // Other jobs are still running on it: it stays active, and its slot is not free.
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
    public synchronized void poison(WorkerProcessHandle worker, String reason) {
        retire(worker, true, reason);
    }

    /**
     * Takes a worker out of service, freeing its tenant's slot and either keeping it warm or reaping it. Idempotent:
     * a worker already retired is not counted down twice, so a stray second release cannot drive the tenant's
     * worker count negative.
     */
    private void retire(WorkerProcessHandle worker, boolean poisoned, String reason) {
        if (worker == null) {
            return;
        }
        Integer held = jobsInFlight.remove(worker.pid());
        boolean wasActive = activeWorkers.remove(worker.pid()) != null;
        if (!wasActive && !poisoned) {
            // A plain release for a worker this pool is not tracking: nothing to free.
            return;
        }
        if (wasActive) {
            countActive(worker.boundTenantId(), -1);
        }
        boolean recycle = poisoned || reusePolicy == ReusePolicy.FRESH_PER_JOB;
        if (poisoned) {
            LOG.warn("Recycling worker {} (pid {}): {} ({} job(s) were still on it)",
                    worker.workerId(), worker.pid(), reason, held == null ? 0 : held);
        } else if (recycle) {
            LOG.debug("Recycling worker {} (pid {}): {} (reuse policy {})",
                    worker.workerId(), worker.pid(), reason, reusePolicy);
        }
        if (recycle) {
            recycleWorker(worker);
        } else {
            // Keep warm for the same tenant: a worker is never handed to another tenant.
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
     * Records a worker starting a job for a tenant: it becomes the tenant's active worker, counting the worker once
     * however many jobs it holds (D-155). Shared by every path that hands out a worker, so the bookkeeping cannot
     * drift; package-private so the release/poison rules can be tested without spawning a process.
     */
    synchronized void jobStarted(WorkerProcessHandle handle, String tenantId) {
        activeWorkers.put(handle.pid(), handle);
        workerBecameActive(handle.pid(), tenantId);
    }

    /** Records a job starting on a worker, counting the worker itself once however many jobs it holds (D-155). */
    private void workerBecameActive(long pid, String tenantId) {
        if (jobsInFlight.merge(pid, 1, Integer::sum) == 1) {
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
            spawnWorker(false).onComplete(ar -> {
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

    private Future<WorkerProcessHandle> spawnWorker(boolean forWork) {
        Promise<WorkerProcessHandle> promise = Promise.promise();
        // Counted from the moment the spawn starts, so maxTotal bounds in-flight workers too. Released by
        // recycleWorker on failure, or when the handle is recycled after use (D-135).
        totalWorkers.incrementAndGet();
        if (nodeBudget != null && !nodeBudget.tryAcquire()) {
            // The node is at its worker cap (D-156). A spawn for work preempts warmth: reap an idle worker from any
            // tenant's pool and take its slot, so an idle worker can never block a job. A spawn for warmth does not —
            // reaping one idle worker to make another would be pointless. If every worker is busy, the acquire queues
            // the job (NodeAtCapacityException) rather than failing it.
            boolean freed = forWork && arbiter != null && arbiter.freeIdleWorker();
            if (!freed || !nodeBudget.tryAcquire()) {
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

            // Drain stderr immediately: the OS pipe buffer is 64 KiB, and a worker that logs more than that before
            // its first run would block forever writing to a full pipe (D-128). The tail is kept so a worker that
            // dies before its hello can say why (D-135).
            StderrTail stderrTail = new StderrTail(20);
            stderrByPid.put(pid, stderrTail);
            drainStderr(pid, process.getErrorStream(), stderrTail);

            // Apply Linux cgroup memory bounds. A worker runs up to maxJobsPerWorker jobs at once, each with its own
            // heapMb budget (D-155, D-157), so the process needs room for all of them plus native overhead. Sizing
            // it for a single job OOM-killed a shared worker (exit 137) the moment a second job ran — the engine
            // reports a per-job budget and the cgroup has to hold the sum.
            long jobsMb = (long) heapMb * maxJobsPerWorker;
            long maxBytes = (jobsMb + 128L) * 1024L * 1024L;
            long highBytes = (jobsMb + 64L) * 1024L * 1024L;
            cgroupLimiter.applyMemoryLimit(pid, maxBytes, highBytes);

            WriteStream<io.vertx.core.buffer.Buffer> output = StreamBridge.writeStream(vertx, process.getOutputStream());
            ReadStream<io.vertx.core.buffer.Buffer> input = StreamBridge.readStream(vertx, process.getInputStream());

            String workerId = "worker-" + pid;
            NodeWorkerChannel channel = new NodeWorkerChannel(workerId, output, input, new cluster93.runner.protocol.flow.CreditManager(), authorizer);
            WorkerProcessHandle handle = new WorkerProcessHandle(process, channel, maxJobsPerWorker);
            // Reap the worker when its connection ends, whatever the run bookkeeping says. Without this a worker that
            // dies holds its slot (and the node's budget) for good, and with a small allowance the queue stalls.
            channel.onClosed(err -> {
                synchronized (TenantWorkerPool.this) {
                    boolean reaped = handle.state() != WorkerProcessHandle.State.TERMINATED;
                    recycleWorker(handle);
                    if (reaped) {
                        LOG.warn("Worker {} (pid {}) closed; its slot is freed", workerId, pid);
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
                    LOG.warn("Worker {} (pid {}) didn't say hello within {} ms{}", workerId, pid, helloTimeoutMs, stderrTail.render());
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
                        LOG.warn("Worker {} (pid {}) failed to start: {}{}", workerId, pid,
                                ar.cause() == null ? "unknown error" : ar.cause().getMessage(), stderrTail.render());
                        recycleWorker(handle);
                        promise.fail(ar.cause() == null ? new IllegalStateException("Worker " + workerId + " failed to start") : ar.cause());
                    }
                }
            });

        } catch (Exception e) {
            totalWorkers.decrementAndGet();
            if (nodeBudget != null) {
                nodeBudget.release();
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
        WorkerProcessHandle idle = warmUnboundQueue.poll();
        if (idle == null) {
            for (List<WorkerProcessHandle> warm : tenantWarmQueue.values()) {
                if (!warm.isEmpty()) {
                    idle = warm.remove(warm.size() - 1);
                    break;
                }
            }
        }
        if (idle == null) {
            return false;
        }
        LOG.info("Reaping idle worker {} (pid {}) to make room for work (node is at its worker cap)",
                idle.workerId(), idle.pid());
        recycleWorker(idle);
        return true;
    }

    private void recycleWorker(WorkerProcessHandle worker) {
        // Each handle is counted once (in spawnWorker) and released once; terminate() is idempotent and marks the
        // handle, so a repeat recycle is a no-op (D-135).
        if (worker == null || worker.state() == WorkerProcessHandle.State.TERMINATED) {
            return;
        }
        totalWorkers.decrementAndGet();
        if (nodeBudget != null) {
            nodeBudget.release();
        }
        cgroupLimiter.cleanup(worker.pid());
        inspectExit(worker);
        StderrTail tail = stderrByPid.remove(worker.pid());
        String rendered = tail == null ? "" : tail.render();
        if (!rendered.isEmpty()) {
            LOG.warn("Worker {} (pid {}) stderr tail:{}", worker.workerId(), worker.pid(), rendered);
        }
        worker.terminate();
    }

    /** A worker that died on its own leaves an exit code that says how: 137 is SIGKILL/OOM, 139 is SIGSEGV. */
    private static void inspectExit(WorkerProcessHandle worker) {
        try {
            if (!worker.process().isAlive()) {
                int code = worker.process().exitValue();
                if (code == 137) {
                    LOG.error("Worker {} (pid {}) was killed (exit 137): likely OOM or cgroup memory limit", worker.workerId(), worker.pid());
                } else if (code == 139) {
                    LOG.error("Worker {} (pid {}) crashed with SIGSEGV (exit 139)", worker.workerId(), worker.pid());
                } else if (code != 0) {
                    LOG.warn("Worker {} (pid {}) exited with code {}", worker.workerId(), worker.pid(), code);
                }
            }
        } catch (IllegalThreadStateException ignored) {
            // still alive when we looked
        }
    }

    private static void drainStderr(long pid, java.io.InputStream stderr, StderrTail tail) {
        Thread.ofVirtual().name("worker-stderr-" + pid).start(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stderr))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.add(line);
                    LOG.debug("[worker {}] {}", pid, line);
                }
            } catch (IOException ignored) {
                // the pipe closed
            }
        });
    }

    /**
     * The last few lines a worker wrote to stderr. A worker that dies before its hello leaves nothing in the log
     * (its stderr is only at debug level), so the reason travels with the failure instead (D-135).
     */
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
            for (WorkerProcessHandle warm : warmUnboundQueue) {
                recycleWorker(warm);
            }
            warmUnboundQueue.clear();

            for (List<WorkerProcessHandle> list : tenantWarmQueue.values()) {
                for (WorkerProcessHandle w : list) {
                    recycleWorker(w);
                }
            }
            tenantWarmQueue.clear();

            for (WorkerProcessHandle active : activeWorkers.values()) {
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
