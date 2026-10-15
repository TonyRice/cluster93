package cluster93.runner.protocol.pool;

/**
 * Decides which tenant gets a freed worker when the node's capacity is contended (D-161).
 *
 * <p>Each tenant has its own {@link TenantWorkerPool}, so a pool only ever sees one tenant and cannot tell whether
 * another is waiting. The arbitration therefore belongs to the {@link TenantWorkerPoolManager}, which sees them all:
 * a pool reports that its tenant is waiting, and reports when it frees capacity, and the manager hands the capacity
 * to the fairest tenant rather than to whoever happens to ask first.
 */
interface NodeArbiter {

    /** This tenant has work waiting for node capacity. */
    void tenantWaiting(String tenantId);

    /** A worker finished and its capacity is free: give it to the fairest tenant waiting, if any. */
    void capacityFreed();

    /** Reap one idle worker node-wide so a waiting job can spawn; false when every worker is busy. */
    boolean freeIdleWorker();
}
