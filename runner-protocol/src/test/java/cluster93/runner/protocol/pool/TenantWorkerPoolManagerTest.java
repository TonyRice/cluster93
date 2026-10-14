package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant arbitration: which tenant gets a freed worker when the node's capacity is contended (D-161).
 *
 * <p>This is the level fairness has to live at. Each tenant has its own {@link TenantWorkerPool}, so a pool only
 * ever sees one tenant — the round-robin installed inside one could never fire. The manager sees them all.
 */
class TenantWorkerPoolManagerTest {

    private static TenantWorkerPoolManager manager(Vertx vertx) {
        // No pool is created for a tenant until it is asked for, which is what lets these tests exercise the
        // arbitration on its own: the rotation is the whole decision, and no worker is spawned.
        return new TenantWorkerPoolManager(vertx, Map.of(), "javet", 0, 2, 2, 128,
                TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), NodeRpcAuthorizer.ALLOW_ALL);
    }

    @Test
    @DisplayName("A freed worker is not handed straight back to the tenant that just had one (D-161)")
    void theManagerRotatesBetweenWaitingTenants() {
        Vertx vertx = Vertx.vertx();
        try {
            TenantWorkerPoolManager manager = manager(vertx);
            // One tenant queues a burst, another queues a single request behind it.
            for (int i = 0; i < 10; i++) {
                manager.tenantWaiting("acme");
            }
            manager.tenantWaiting("globex");
            // Idempotent: a tenant is in the rotation once, however many requests it queued.
            assertThat(manager.nextWaitingTenant()).isEqualTo("acme");

            // acme is served first (it waited first); the next freed worker must go to globex, not back to acme.
            manager.capacityFreed();
            assertThat(manager.nextWaitingTenant()).isEqualTo("globex");

            manager.capacityFreed();
            // Nobody left waiting.
            assertThat(manager.nextWaitingTenant()).isNull();
        } finally {
            vertx.close();
        }
    }

    @Test
    @DisplayName("Freeing capacity with nobody waiting is a no-op, not a repeat of the last tenant")
    void nothingWaitingMeansNothingServed() {
        Vertx vertx = Vertx.vertx();
        try {
            TenantWorkerPoolManager manager = manager(vertx);

            manager.capacityFreed();
            assertThat(manager.nextWaitingTenant()).isNull();
        } finally {
            vertx.close();
        }
    }
}
