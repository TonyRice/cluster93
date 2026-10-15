package cluster93.runner.protocol.pool;

import cluster93.core.metrics.Metrics;
import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supervises per-tenant {@link TenantWorkerPool} instances, isolating warm worker pools,
 * memory bounds, and recycling lifecycles across tenants (D-127).
 */
public class TenantWorkerPoolManager implements NodeArbiter {

    private final Vertx vertx;
    private final Map<String, String> modeBinaries;
    private final String defaultMode;
    private final int minWarmPerTenant;
    private final int maxTotalPerTenant;
    /** How many jobs one worker runs at once (D-155), passed to every pool this manager creates. */
    private final int maxJobsPerWorker;
    /** The node-wide allowance shared by every tenant's pool, so n tenants cannot spawn n × capacity (D-156). */
    private final WorkerBudget nodeBudget;
    /** The node-wide memory allowance shared by every tenant's pool (D-163), next to the worker count. */
    private final WorkerMemoryBudget memoryBudget;
    /**
     * Who gets a freed worker when tenants contend (D-161). This is the only place more than one tenant is visible:
     * a per-tenant pool sees just itself, so fairness installed there could never fire.
     */
    private final TenantRotation rotation = new TenantRotation();
    private final int heapMb;
    private final TenantWorkerPool.ReusePolicy reusePolicy;
    private final CgroupMemoryLimiter cgroupLimiter;
    private final NodeRpcAuthorizer authorizer;

    private final Map<String, TenantWorkerPool> tenantPools = new ConcurrentHashMap<>();

    public TenantWorkerPoolManager(Vertx vertx, Map<String, String> modeBinaries, String defaultMode) {
        this(vertx, modeBinaries, defaultMode, 1, 8, 8, 4, 128, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPoolManager(Vertx vertx,
                                   Map<String, String> modeBinaries,
                                   String defaultMode,
                                   int minWarmPerTenant,
                                   int maxTotalPerTenant,
                                   int maxWorkers,
                                   int maxJobsPerWorker,
                                   int heapMb,
                                   TenantWorkerPool.ReusePolicy reusePolicy,
                                   CgroupMemoryLimiter cgroupLimiter,
                                   NodeRpcAuthorizer authorizer) {
        this(vertx, modeBinaries, defaultMode, minWarmPerTenant, maxTotalPerTenant, maxWorkers, maxJobsPerWorker,
                heapMb, reusePolicy, cgroupLimiter, authorizer, 0);
    }

    public TenantWorkerPoolManager(Vertx vertx,
                                   Map<String, String> modeBinaries,
                                   String defaultMode,
                                   int minWarmPerTenant,
                                   int maxTotalPerTenant,
                                   int maxWorkers,
                                   int maxJobsPerWorker,
                                   int heapMb,
                                   TenantWorkerPool.ReusePolicy reusePolicy,
                                   CgroupMemoryLimiter cgroupLimiter,
                                   NodeRpcAuthorizer authorizer,
                                   long maxWorkerMemoryBytes) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.modeBinaries = modeBinaries != null ? Map.copyOf(modeBinaries) : Map.of();
        this.defaultMode = defaultMode != null ? defaultMode : "javet";
        this.minWarmPerTenant = Math.max(0, minWarmPerTenant);
        this.maxTotalPerTenant = Math.max(1, maxTotalPerTenant);
        this.maxJobsPerWorker = Math.max(1, maxJobsPerWorker);
        // Bounded at last (D-156). Gating spawns on the shared allowance used to deadlock: every pool held minWarm
        // against the same budget, so several tenants filled it with idle workers and a tenant that then could not
        // spawn queued forever (PackagesTest hung). Two things changed: pools no longer pre-warm (minWarm 0), and a
        // spawn that finds the budget full first asks the manager to reap an IDLE worker node-wide and retries
        // (freeIdleWorker). Work preempts warmth, so an idle worker can never block a job.
        this.nodeBudget = new WorkerBudget(Math.max(1, maxWorkers));
        this.memoryBudget = new WorkerMemoryBudget(maxWorkerMemoryBytes);
        this.heapMb = Math.max(16, heapMb);
        this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy");
        this.cgroupLimiter = Objects.requireNonNull(cgroupLimiter, "cgroupLimiter");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    }

    /**
     * Resolves the dedicated worker pool for a given tenant, creating it if not present.
     */
    public TenantWorkerPool poolFor(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return tenantPools.computeIfAbsent(tenantId, t -> {
            String binary = resolveModeBinary(defaultMode);
            TenantWorkerPool pool = new TenantWorkerPool(vertx, binary, defaultMode, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget, maxJobsPerWorker, memoryBudget)
                    .arbiter(this);
            return registerTenantGauges(t, pool);
        });
    }

    /**
     * Acquires a worker for a specific tenant and execution mode.
     *
     * <p>This is <b>not</b> where the node-wide concurrent-<em>job</em> bound lives. That bound is the scheduler's:
     * it places at most {@code C93_CAPACITY} running attempts on a node and refuses more, and the node re-checks it
     * in {@code acceptsAttempt}, so a node never runs more jobs at once than it advertises (D-154, D-156). This
     * method only supplies a worker for a job the scheduler has already admitted.
     */
    public Future<WorkerProcessHandle> acquire(String tenantId, String mode) {
        String effectiveMode = (mode != null && !mode.isBlank()) ? mode.toLowerCase(Locale.ROOT) : defaultMode;
        // If mode matches default, use the tenant pool
        if (effectiveMode.equalsIgnoreCase(defaultMode)) {
            return poolFor(tenantId).acquire(tenantId);
        }

        // Dedicated pool for this mode and tenant
        String poolKey = tenantId + ":" + effectiveMode;
        TenantWorkerPool pool = tenantPools.computeIfAbsent(poolKey, k -> {
            String binary = resolveModeBinary(effectiveMode);
            TenantWorkerPool created = new TenantWorkerPool(vertx, binary, effectiveMode, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget, maxJobsPerWorker, memoryBudget)
                    .arbiter(this);
            return registerTenantGauges(tenantId, created);
        });
        return pool.acquire(tenantId);
    }

    /**
     * Resolves the binary for a mode: the mode's explicit binary, then the "default" binary, then auto-discovery.
     * Returns null when no executable binary can be found (the pool then falls back to a child JVM).
     */
    String resolveModeBinary(String mode) {
        String normalized = (mode == null || mode.isBlank()) ? defaultMode : mode.toLowerCase(Locale.ROOT);
        String explicit = modeBinaries.get(normalized);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        explicit = modeBinaries.get("default");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return TenantWorkerPool.resolveBinary(null, normalized);
    }

    /**
     * Releases an acquired worker after one of its jobs finishes. This is a run-level event: the pool decides from
     * the worker's own health whether the process is retired.
     */
    public void release(String tenantId, String mode, WorkerProcessHandle worker) {
        TenantWorkerPool pool = poolFor(tenantId, mode);
        if (pool != null) {
            pool.release(worker);
        } else {
            worker.terminate();
        }
    }

    /** Retires a worker that is itself unhealthy, in the pool that owns it. */
    public void poison(String tenantId, String mode, WorkerProcessHandle worker, String reason) {
        TenantWorkerPool pool = poolFor(tenantId, mode);
        if (pool != null) {
            pool.poison(worker, reason);
        } else {
            worker.terminate();
        }
    }

    /** The pool that owns a tenant's workers for a mode, or null when it was never created. */
    private TenantWorkerPool poolFor(String tenantId, String mode) {
        String effectiveMode = (mode != null && !mode.isBlank()) ? mode.toLowerCase(Locale.ROOT) : defaultMode;
        String poolKey = effectiveMode.equalsIgnoreCase(defaultMode) ? tenantId : tenantId + ":" + effectiveMode;
        return tenantPools.get(poolKey);
    }

    @Override
    public synchronized void tenantWaiting(String tenantId) {
        rotation.enqueue(tenantId);
    }

    /** Which tenant the manager would hand the next freed worker to; for status, and for testing the rotation. */
    public synchronized String nextWaitingTenant() {
        return rotation.next();
    }

    /**
     * A worker finished: hand its capacity to the fairest tenant waiting, skipping the one just served. One request
     * per freed worker, so a tenant cannot take several at once by having a long queue.
     */
    @Override
    public synchronized void capacityFreed() {
        String tenantId = rotation.next();
        if (tenantId == null) {
            return;
        }
        TenantWorkerPool pool = tenantPools.get(tenantId);
        if (pool == null) {
            // Defensive only: a tenant that queued has a pool, so this cannot happen in practice. Nothing can be
            // served, so the claim is dropped rather than kept forever (the rotation is not a waiting list).
            rotation.served(tenantId, false);
            return;
        }
        pool.pump();
        // Still waiting for more, so it goes to the back of the rotation and its next turn comes after everyone else.
        rotation.served(tenantId, pool.hasPending());
    }

    /** How many workers the node is running across every tenant, and what it may run. */
    public WorkerBudget nodeBudget() {
        return nodeBudget;
    }

    /** Registers a tenant's worker gauges once, backed by the pool, which the manager holds for its lifetime. */
    private TenantWorkerPool registerTenantGauges(String tenantId, TenantWorkerPool pool) {
        Metrics.of(vertx).tenantWorkers(tenantId, pool,
                TenantWorkerPool::activeWorkerCount, TenantWorkerPool::jobsInFlightCount, TenantWorkerPool::pendingCount);
        return pool;
    }

    /** A point-in-time reading of every tenant's worker use on this node (D-156), for the CLI and tests. */
    public WorkerPoolSnapshot snapshot() {
        List<WorkerPoolSnapshot.TenantWorkers> tenants = new ArrayList<>();
        Set<String> active = new HashSet<>();
        int warm = 0;
        for (TenantWorkerPool pool : tenantPools.values()) {
            warm += pool.warmCount();
            WorkerPoolSnapshot.TenantWorkers usage = pool.tenantUsage();
            if (usage != null && (usage.workers() > 0 || usage.pending() > 0)) {
                tenants.add(usage);
                if (usage.workers() > 0) {
                    active.add(usage.tenant());
                }
            }
        }
        tenants.sort(Comparator.comparing(WorkerPoolSnapshot.TenantWorkers::tenant));
        return new WorkerPoolSnapshot(nodeBudget.used(), nodeBudget.limit(), warm, active.size(),
                memoryBudget.usedBytes(), memoryBudget.limitBytes(), List.copyOf(tenants));
    }

    /** Worker processes this node is running now; a cheap reading for the node-wide gauge. */
    public int workersRunning() {
        return nodeBudget.used();
    }

    /** Worker processes this node may run; 0 means unbounded. */
    public int workersLimit() {
        return nodeBudget.limit();
    }

    /** Memory the node's workers reserve now; a cheap reading for the node-wide gauge (D-163). */
    public long workersMemoryUsed() {
        return memoryBudget.usedBytes();
    }

    /** Memory the node's workers may reserve; 0 means unbounded. */
    public long workersMemoryLimit() {
        return memoryBudget.limitBytes();
    }

    /** Idle workers kept warm, node-wide. */
    public int workersWarm() {
        int warm = 0;
        for (TenantWorkerPool pool : tenantPools.values()) {
            warm += pool.warmCount();
        }
        return warm;
    }

    /** Distinct tenants running at least one worker right now. */
    public int tenantsActive() {
        Set<String> active = new HashSet<>();
        for (TenantWorkerPool pool : tenantPools.values()) {
            WorkerPoolSnapshot.TenantWorkers usage = pool.tenantUsage();
            if (usage != null && usage.workers() > 0) {
                active.add(usage.tenant());
            }
        }
        return active.size();
    }

    /**
     * Makes room for one more worker by reaping an idle (warm) worker from any tenant's pool, returning whether one
     * was found. A pool whose spawn the node-wide budget refused calls this and retries: work must preempt warmth,
     * or an idle worker sitting in one tenant's pool would block another tenant's job for good. A worker with jobs
     * still on it is never reaped here.
     */
    @Override
    public boolean freeIdleWorker() {
        for (TenantWorkerPool pool : tenantPools.values()) {
            if (pool.reapIdleWorker()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes all tenant worker pools and destroys processes.
     */
    public void close() {
        for (TenantWorkerPool pool : tenantPools.values()) {
            pool.close();
        }
        tenantPools.clear();
    }
}
