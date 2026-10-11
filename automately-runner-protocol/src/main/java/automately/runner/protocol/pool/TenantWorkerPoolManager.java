package automately.runner.protocol.pool;

import automately.runner.protocol.server.NodeRpcAuthorizer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supervises per-tenant {@link TenantWorkerPool} instances, isolating warm worker pools,
 * memory bounds, and recycling lifecycles across tenants (D-127).
 */
public class TenantWorkerPoolManager {

    private final Vertx vertx;
    private final Map<String, String> modeBinaries;
    private final String defaultMode;
    private final int minWarmPerTenant;
    private final int maxTotalPerTenant;
    private final int heapMb;
    private final TenantWorkerPool.ReusePolicy reusePolicy;
    private final CgroupMemoryLimiter cgroupLimiter;
    private final NodeRpcAuthorizer authorizer;

    private final Map<String, TenantWorkerPool> tenantPools = new ConcurrentHashMap<>();

    public TenantWorkerPoolManager(Vertx vertx, Map<String, String> modeBinaries, String defaultMode) {
        this(vertx, modeBinaries, defaultMode, 1, 8, 128, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPoolManager(Vertx vertx,
                                   Map<String, String> modeBinaries,
                                   String defaultMode,
                                   int minWarmPerTenant,
                                   int maxTotalPerTenant,
                                   int heapMb,
                                   TenantWorkerPool.ReusePolicy reusePolicy,
                                   CgroupMemoryLimiter cgroupLimiter,
                                   NodeRpcAuthorizer authorizer) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.modeBinaries = modeBinaries != null ? Map.copyOf(modeBinaries) : Map.of();
        this.defaultMode = defaultMode != null ? defaultMode : "javet";
        this.minWarmPerTenant = Math.max(0, minWarmPerTenant);
        this.maxTotalPerTenant = Math.max(1, maxTotalPerTenant);
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
            String binary = modeBinaries.get(defaultMode);
            return new TenantWorkerPool(vertx, binary, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer);
        });
    }

    /**
     * Acquires a worker for a specific tenant and execution mode.
     */
    public Future<WorkerProcessHandle> acquire(String tenantId, String mode) {
        String effectiveMode = (mode != null && !mode.isBlank()) ? mode : defaultMode;
        // If mode matches default, use the tenant pool
        if (effectiveMode.equalsIgnoreCase(defaultMode)) {
            return poolFor(tenantId).acquire(tenantId);
        }

        // Dedicated pool for this mode and tenant
        String poolKey = tenantId + ":" + effectiveMode.toLowerCase();
        TenantWorkerPool pool = tenantPools.computeIfAbsent(poolKey, k -> {
            String binary = modeBinaries.get(effectiveMode.toLowerCase());
            return new TenantWorkerPool(vertx, binary, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer);
        });
        return pool.acquire(tenantId);
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
