package cluster93.runner.protocol.pool;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How much memory the whole node's workers may reserve at once (D-163), shared by every tenant's pool, next to the
 * worker-<em>count</em> allowance of {@link WorkerBudget}.
 *
 * <p>{@link WorkerBudget} bounds how many worker processes a node runs, not how much memory they hold: with the
 * defaults a tenant takes {@code ceil(maxJobsPerTenant / maxJobsPerWorker)} workers, each cgroup-sized for
 * {@code maxJobsPerWorker × C93_MAX_HEAP_MB} plus native overhead, and nothing reasoned about the total. This is
 * that total. A worker reserves its cgroup size when it is spawned and gives it back when it is reaped, so the sum
 * cannot drift from the workers actually running.
 */
public final class WorkerMemoryBudget {

    private final long limitBytes;
    private final AtomicLong usedBytes = new AtomicLong();

    /** @param limitBytes how much worker memory the node may reserve; a value below 1 means unbounded */
    public WorkerMemoryBudget(long limitBytes) {
        this.limitBytes = limitBytes;
    }

    /** An unbounded budget, for a pool that is the only one (tests, a single-tenant node). */
    public static WorkerMemoryBudget unbounded() {
        return new WorkerMemoryBudget(0);
    }

    public long limitBytes() {
        return limitBytes;
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public boolean bounded() {
        return limitBytes > 0;
    }

    /** Whether {@code bytes} would fit, without taking them. */
    public boolean hasRoom(long bytes) {
        return !bounded() || usedBytes.get() == 0 || usedBytes.get() + bytes <= limitBytes;
    }

    /**
     * Takes {@code bytes} of the node's worker memory.
     *
     * <p>The first worker is always admitted, however small the limit: a limit below one worker's size would
     * otherwise let no worker start at all, and a node that runs no worker does no work. Every worker after it must
     * fit.
     *
     * @return false when the node is already at its memory limit, in which case nothing was taken
     */
    public boolean tryAcquire(long bytes) {
        if (!bounded()) {
            return true;
        }
        while (true) {
            long current = usedBytes.get();
            if (current > 0 && current + bytes > limitBytes) {
                return false;
            }
            if (usedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /** Gives {@code bytes} back. Only call it for a worker {@link #tryAcquire(long)} admitted. */
    public void release(long bytes) {
        if (bounded()) {
            usedBytes.addAndGet(-bytes);
        }
    }
}
