package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.message.PolicyMessage;

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

    /**
     * An open worker this node accepted, to bind to {@code tenantId} (D-166), or null when none is waiting. A node
     * that accepts worker containers implements it; one that only spawns leaves it null, which is why it defaults.
     */
    default WorkerHandle takeOpenWorker(String tenantId) {
        return null;
    }

    /**
     * The egress policy {@code tenantId}'s runs decide under, to hand a worker the moment it is bound (D-173), or null
     * when this node sends none — a node that decides egress itself. A worker that is never given one refuses to
     * connect anywhere on its own, so "no policy" is the safe answer, not a hole.
     */
    default PolicyMessage policyFor(String tenantId) {
        return null;
    }

    /**
     * An idle worker to re-bind to {@code tenantId}, taken from another tenant's pool (D-172), or null when no pool has
     * one to spare. Called when a claim cannot be served any other way: it preempts another tenant's warm worker, which
     * is the same rule that lets work reap an idle worker node-wide (D-156) — except that the process is reused rather
     * than replaced.
     */
    default WorkerHandle takeIdleWorkerFor(String tenantId) {
        return null;
    }
}
