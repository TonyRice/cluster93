package cluster93.runner.protocol.pool;

import java.util.List;

/**
 * A point-in-time reading of a node's worker pools (D-156), for the metrics endpoint and the CLI: how many worker
 * processes the node is running and may run, how many are idle, how many tenants are actually using one, and a
 * per-tenant breakdown.
 *
 * <p>It is a snapshot, not a live view: it is computed on request from {@link TenantWorkerPoolManager} and the pools
 * it owns. Nothing is counted here that the pools do not already know.
 *
 * @param workersRunning worker processes running now (the node-wide budget's {@code used})
 * @param workersLimit   worker processes the node may run (the budget's {@code limit}); 0 means unbounded
 * @param workersWarm    idle workers kept warm, node-wide
 * @param tenantsActive  distinct tenants running at least one worker right now
 * @param tenants        the per-tenant readings, busiest first
 */
public record WorkerPoolSnapshot(int workersRunning, int workersLimit, int workersWarm, int tenantsActive,
                                 List<TenantWorkers> tenants) {

    /**
     * One tenant's worker use on this node.
     *
     * @param tenant            the tenant
     * @param workers           worker processes running for it
     * @param jobs              jobs those workers are running at once
     * @param pending           acquires of its jobs waiting for a worker
     * @param warm              idle workers kept warm for it
     * @param budgetEnforcement which layer enforces its memory budget (D-157): {@code cgroup} for Javet,
     *                          {@code engine} for GraalJS
     */
    public record TenantWorkers(String tenant, int workers, int jobs, int pending, int warm, String budgetEnforcement) {
    }
}
