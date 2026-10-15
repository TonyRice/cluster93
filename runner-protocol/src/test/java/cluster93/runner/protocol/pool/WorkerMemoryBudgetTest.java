package cluster93.runner.protocol.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node-wide memory ceiling on workers (D-163): what {@link WorkerBudget} does for the worker <em>count</em>, this
 * does for the memory they hold.
 */
class WorkerMemoryBudgetTest {

    @Test
    @DisplayName("Workers are admitted until the ceiling is reached, then refused (D-163)")
    void workersAreAdmittedUpToTheLimit() {
        WorkerMemoryBudget budget = new WorkerMemoryBudget(300);

        assertThat(budget.tryAcquire(100)).isTrue();
        assertThat(budget.tryAcquire(100)).isTrue();
        assertThat(budget.tryAcquire(100)).isTrue();
        // The fourth does not fit, and takes nothing with it.
        assertThat(budget.tryAcquire(100)).isFalse();
        assertThat(budget.usedBytes()).isEqualTo(300);

        // A worker finishing gives its memory back, and only then is there room again.
        budget.release(100);
        assertThat(budget.usedBytes()).isEqualTo(200);
        assertThat(budget.tryAcquire(100)).isTrue();
    }

    @Test
    @DisplayName("The first worker is always admitted, so a ceiling below one worker cannot deadlock (D-163)")
    void theFirstWorkerIsAlwaysAdmitted() {
        WorkerMemoryBudget budget = new WorkerMemoryBudget(400);

        // Larger than the whole ceiling: still admitted, because a node that runs no worker does no work.
        assertThat(budget.tryAcquire(1_000)).isTrue();
        assertThat(budget.usedBytes()).isEqualTo(1_000);

        // Every worker after it must fit, and this one cannot.
        assertThat(budget.tryAcquire(1)).isFalse();
        assertThat(budget.usedBytes()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("An unbounded budget never refuses and never counts")
    void anUnboundedBudgetNeverRefuses() {
        WorkerMemoryBudget budget = WorkerMemoryBudget.unbounded();

        assertThat(budget.bounded()).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(budget.tryAcquire(1L << 30)).isTrue();
        }
        // usedBytes stays 0 when unbounded, so a release cannot drive it negative.
        assertThat(budget.usedBytes()).isZero();
        budget.release(1L << 30);
        assertThat(budget.usedBytes()).isZero();
    }
}
