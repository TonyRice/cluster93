package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    /** The node-wide allowance shared by every tenant's pool, so n tenants cannot spawn n × capacity (D-156). */
    private final WorkerBudget nodeBudget;
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
        this(vertx, modeBinaries, defaultMode, 1, 8, 8, 128, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPoolManager(Vertx vertx,
                                   Map<String, String> modeBinaries,
                                   String defaultMode,
                                   int minWarmPerTenant,
                                   int maxTotalPerTenant,
                                   int nodeWideWorkers,
                                   int heapMb,
                                   TenantWorkerPool.ReusePolicy reusePolicy,
                                   CgroupMemoryLimiter cgroupLimiter,
                                   NodeRpcAuthorizer authorizer) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.modeBinaries = modeBinaries != null ? Map.copyOf(modeBinaries) : Map.of();
        this.defaultMode = defaultMode != null ? defaultMode : "javet";
        this.minWarmPerTenant = Math.max(0, minWarmPerTenant);
        this.maxTotalPerTenant = Math.max(1, maxTotalPerTenant);
        this.nodeBudget = new WorkerBudget(nodeWideWorkers);
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
            return new TenantWorkerPool(vertx, binary, defaultMode, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget)
                    .arbiter(this);
        });
    }

    /**
     * Acquires a worker for a specific tenant and execution mode.
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
            return new TenantWorkerPool(vertx, binary, effectiveMode, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget)
                    .arbiter(this);
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
     * Releases an acquired worker.
     */
    public void release(String tenantId, String mode, WorkerProcessHandle worker, boolean errorOrOom) {
        String effectiveMode = (mode != null && !mode.isBlank()) ? mode : defaultMode;
        String poolKey = effectiveMode.equalsIgnoreCase(defaultMode) ? tenantId : tenantId + ":" + effectiveMode.toLowerCase();
        TenantWorkerPool pool = tenantPools.get(poolKey);
        if (pool != null) {
            pool.release(worker, errorOrOom);
        } else {
            worker.terminate();
        }
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
