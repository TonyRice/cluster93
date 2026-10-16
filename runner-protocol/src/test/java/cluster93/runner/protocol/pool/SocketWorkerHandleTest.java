package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.server.NodeWorkerChannel;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker that <b>connected</b> to the node has no process to read (D-166): it has a connection, its death is "the
 * connection ended" rather than an exit status, and it holds no slot of the node's worker-process allowance. What it
 * shares with a spawned worker — the state machine, the bound tenant, admission — is what makes it a worker at all.
 */
class SocketWorkerHandleTest {

    @Test
    @DisplayName("A connected worker has no process, pid or exit code, and holds no worker-process slot (D-166)")
    void aConnectedWorkerHasNoProcessToRead() {
        SocketWorkerHandle handle = newHandle(384L * 1024 * 1024);

        assertThat(handle.spawned()).isFalse();
        assertThat(handle.process()).isNull();
        assertThat(handle.pid()).isZero();
        assertThat(handle.exitCode()).isNull();
        assertThat(handle.oomKilled()).as("its container's memory is the deployment's to read (D-169)").isFalse();
        assertThat(handle.stderrTail()).isEmpty();
        assertThat(handle.holdsWorkerSlot()).isFalse();

        // Alive while its connection is, and saying only that it ended once it is not — no cause is invented (§2.5).
        assertThat(handle.isAlive()).isTrue();
        assertThat(handle.deathCause()).isNull();
        handle.connectionEnded();
        assertThat(handle.isAlive()).isFalse();
        assertThat(handle.deathCause()).isEqualTo("connection ended");
    }

    @Test
    @DisplayName("A connected worker binds to the tenant the node chose, and admits several jobs like any other (D-155)")
    void aConnectedWorkerBindsAndAdmitsJobs() {
        SocketWorkerHandle handle = newHandle(64L * 1024 * 1024);
        assertThat(handle.state()).isEqualTo(WorkerHandle.State.UNBOUND);
        assertThat(handle.hasCapacity()).as("an unbound worker takes no job").isFalse();

        handle.bind("acme", new JsonObject(), new JsonObject());
        assertThat(handle.state()).isEqualTo(WorkerHandle.State.BOUND);
        assertThat(handle.boundTenantId()).isEqualTo("acme");
        assertThat(handle.maxJobs()).isEqualTo(4);
        assertThat(handle.hasCapacity()).isTrue();

        assertThat(handle.admitJob()).isTrue();
        assertThat(handle.runningJobs()).isEqualTo(1);
        assertThat(handle.hasCapacity()).isTrue();
        for (int i = 0; i < 3; i++) {
            assertThat(handle.admitJob()).isTrue();
        }
        assertThat(handle.hasCapacity()).as("four jobs fill the worker").isFalse();
        assertThat(handle.admitJob()).isFalse();
        assertThat(handle.runningJobs()).isEqualTo(4);

        // Idle it can be handed to another tenant; with runs on it, it refuses (D-172).
        var refused = handle.bind("globex", new JsonObject(), new JsonObject());
        assertThat(refused.failed()).as("a worker with runs in flight must not change tenant").isTrue();
        assertThat(String.valueOf(refused.cause().getMessage())).contains("idle");
        assertThat(handle.boundTenantId()).isEqualTo("acme");
    }

    @Test
    @DisplayName("Terminating a connected worker closes its connection, exactly once")
    void terminatingClosesTheConnectionOnce() {
        AtomicInteger closed = new AtomicInteger();
        NodeWorkerChannel channel = new NodeWorkerChannel("worker-c7", new TenantWorkerPoolTest.FakeStream(),
                new TenantWorkerPoolTest.FakeStream());
        SocketWorkerHandle handle = new SocketWorkerHandle(7L, closed::incrementAndGet, channel, 4, 1024L);

        handle.terminate();
        handle.terminate();

        assertThat(closed.get()).isEqualTo(1);
        assertThat(handle.state()).isEqualTo(WorkerHandle.State.TERMINATED);
        assertThat(handle.isAlive()).isFalse();
        assertThat(handle.reservedBytes()).isEqualTo(1024L);
    }

    private static SocketWorkerHandle newHandle(long reservedBytes) {
        NodeWorkerChannel channel = new NodeWorkerChannel("worker-c1", new TenantWorkerPoolTest.FakeStream(),
                new TenantWorkerPoolTest.FakeStream());
        return new SocketWorkerHandle(1L, () -> { }, channel, 4, reservedBytes);
    }
}
