package automately.core.state;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedDataJobStateStoreTest {

    private Vertx vertx;
    private JobStateStore store;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataJobStateStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void storesStepsInCompletionOrderAndTheLatestCheckpoint() throws Exception {
        await(store.completeStep("job", 1, "b", "B", 10));
        await(store.completeStep("job", 1, "a", 42, 11));
        await(store.saveCheckpoint("job", 1, "first"));
        await(store.saveCheckpoint("job", 1, "second"));

        JobState state = await(store.load("job", 1));
        assertThat(state.steps()).extracting(StepRecord::name).containsExactly("b", "a");
        assertThat(state.step("a").orElseThrow().result()).isEqualTo(42);
        assertThat(state.checkpoint()).isEqualTo("second");
    }

    @Test
    void resumeCopiesTheLatestEarlierAttemptAndSkipsAttemptsThatRecordedNothing() throws Exception {
        await(store.completeStep("job", 1, "a", 1, 10));
        await(store.resumeFrom("job", 2));
        await(store.completeStep("job", 2, "b", 2, 20));
        // Attempt 3 crashed before it copied anything; attempt 4 must still get a and b.

        JobState resumed = await(store.resumeFrom("job", 4));

        assertThat(resumed.steps()).extracting(StepRecord::name).containsExactly("a", "b");
        assertThat(await(store.load("job", 4))).isEqualTo(resumed);
    }

    @Test
    void writesToAnOldAttemptDoNotReachTheNewOne() throws Exception {
        await(store.completeStep("job", 1, "a", 1, 10));
        await(store.resumeFrom("job", 2));

        await(store.completeStep("job", 1, "zombie", 1, 30));

        assertThat(await(store.load("job", 2)).step("zombie")).isEmpty();
    }

    @Test
    void rejectsValuesOverTheLimit() {
        String big = "x".repeat(JobStateStore.MAX_VALUE_BYTES + 1);

        assertThatThrownBy(() -> await(store.saveCheckpoint("job", 1, big)))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(StateException.class)
                .satisfies(e -> assertThat(((StateException) e).kind()).isEqualTo(StateException.Kind.LIMIT));
    }

    @Test
    void rejectsTotalStateOverTheLimit() throws Exception {
        String chunk = "x".repeat(60 * 1024);
        // 17 chunks of 60 KB fit under 1 MB; the 18th does not.
        for (int i = 0; i < 17; i++) {
            await(store.completeStep("job", 1, "s" + i, chunk, i));
        }

        assertThatThrownBy(() -> await(store.completeStep("job", 1, "one-too-many", chunk, 99)))
                .cause().isInstanceOf(StateException.class).hasMessageContaining("limit");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
