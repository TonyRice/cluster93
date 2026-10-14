package cluster93.runner.protocol.pool;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Who gets the next freed worker: a round-robin over the tenants waiting, never handing one straight back to the
 * tenant just served. Skipping the last-served tenant is the rule that matters — without it a freed worker goes
 * back to whoever just had one, and a tenant with a backlog keeps the pool while another waits. With n tenants
 * competing each gets roughly capacity/n; a tenant that is the only one waiting still gets everything.
 *
 * <p>Split out from the pool so the fairness rule is testable without spawning a worker (D-153).
 */
final class TenantRotation {
    private final Deque<String> order = new ArrayDeque<>();
    private String lastServed;

    /** A tenant joined the queue. Idempotent: a tenant is in the rotation once, however many it queues. */
    void enqueue(String tenantId) {
        if (!order.contains(tenantId)) {
            order.addLast(tenantId);
        }
    }

    /** The next tenant to serve, or null when nobody is waiting. */
    String next() {
        for (String tenantId : order) {
            if (!tenantId.equals(lastServed)) {
                return tenantId;
            }
        }
        // Only the last-served tenant is waiting: better it has the worker than nobody does.
        return order.peekFirst();
    }

    /** Records that a tenant was served; it goes to the back of the rotation if it still has work. */
    void served(String tenantId, boolean stillWaiting) {
        order.remove(tenantId);
        if (stillWaiting) {
            order.addLast(tenantId);
        }
        lastServed = tenantId;
    }

    void clear() {
        order.clear();
        lastServed = null;
    }

    int waiting() {
        return order.size();
    }
}
