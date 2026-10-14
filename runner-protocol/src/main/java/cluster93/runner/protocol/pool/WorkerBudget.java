package cluster93.runner.protocol.pool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many workers the whole node may run at once, shared by every tenant's pool (D-156).
 *
 * <p>Each tenant has its own {@link TenantWorkerPool}, and each used to bound only itself: with
 * {@code maxTotal} set to the node's capacity, <em>n</em> active tenants could spawn {@code n × capacity}
 * worker processes, so a node advertising 8 could hold 40 and five gigabytes of worker heaps. This is
 * the one counter they all draw from, so the total is the node's capacity however many tenants turn up.
 *
 * <p>Reserved where {@code totalWorkers} is incremented and released where it is decremented, so the two
 * cannot drift: a budget leak would silently stop the node spawning workers.
 */
public final class WorkerBudget {

    private final int limit;
    private final AtomicInteger used = new AtomicInteger();

    /** @param limit how many workers the node may run at once; a value below 1 means unbounded */
    public WorkerBudget(int limit) {
        this.limit = limit;
    }

    /** An unbounded budget, for a pool that is the only one (tests, a single-tenant node). */
    public static WorkerBudget unbounded() {
        return new WorkerBudget(0);
    }

    public int limit() {
        return limit;
    }

    public int used() {
        return used.get();
    }

    public boolean bounded() {
        return limit > 0;
    }

    /** Whether there is room for one more worker, without taking it. */
    public boolean hasRoom() {
        return !bounded() || used.get() < limit;
    }

    /**
     * Takes one worker's worth of the budget.
     *
     * @return false when the node is already at its limit, in which case nothing was taken
     */
    public boolean tryAcquire() {
        if (!bounded()) {
            return true;
        }
        while (true) {
            int current = used.get();
            if (current >= limit) {
                return false;
            }
            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Gives one worker's worth back. Only call it for a worker {@link #tryAcquire()} admitted. */
    public void release() {
        if (bounded()) {
            used.decrementAndGet();
        }
    }
}
