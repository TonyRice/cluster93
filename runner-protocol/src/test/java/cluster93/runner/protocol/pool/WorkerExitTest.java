package cluster93.runner.protocol.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker that dies can only say so through its exit status (D-159): the channel reports the pipe ending, and this
 * turns that into the cause a tenant needs.
 */
class WorkerExitTest {

    @Test
    @DisplayName("An exit code names the cause: OOM or force-kill, segfault, or a plain code (D-159)")
    void anExitCodeNamesTheCause() {
        // 137 is SIGKILL: the cgroup OOM killer, or a plain kill -9 — the code cannot tell them apart, so it says both.
        assertThat(WorkerExit.describe(137)).contains("out of memory").contains("SIGKILL");
        assertThat(WorkerExit.describe(139)).contains("SIGSEGV");
        assertThat(WorkerExit.describe(3)).isEqualTo("exited with code 3");
        // A clean exit is not a crash, so there is nothing to attribute.
        assertThat(WorkerExit.describe(0)).isNull();
    }

    @Test
    @DisplayName("A running worker has no exit cause to report (D-159)")
    void aRunningWorkerHasNoCause() {
        TenantWorkerPoolTest.FakeProcess alive = new TenantWorkerPoolTest.FakeProcess(1L, 137);
        assertThat(WorkerExit.describe(alive)).isNull();
        assertThat(WorkerExit.describe(null)).isNull();
    }

    @Test
    @DisplayName("A dead worker's cause is named, whatever the pipe said (D-159)")
    void aDeadWorkersCauseIsNamed() {
        TenantWorkerPoolTest.FakeProcess dead = new TenantWorkerPoolTest.FakeProcess(2L, 137);
        dead.destroy();

        assertThat(WorkerExit.describe(dead)).contains("out of memory");
        // The run that produced no result reports the cause, not the bare EOF the channel saw.
        assertThat(WorkerExit.runFailure(dead, new IOException("Worker process connection closed unexpectedly (EOF)")))
                .isEqualTo("Worker killed: out of memory or force-killed (SIGKILL)");
    }

    @Test
    @DisplayName("A live worker's failure keeps the run's own cause (D-159)")
    void aLiveWorkersFailureKeepsItsCause() {
        TenantWorkerPoolTest.FakeProcess alive = new TenantWorkerPoolTest.FakeProcess(3L);

        assertThat(WorkerExit.runFailure(alive, new IllegalStateException("bind failed")))
                .isEqualTo("Worker error: bind failed");
        assertThat(WorkerExit.runFailure(alive, null)).isEqualTo("Worker error: worker error");
    }
}
