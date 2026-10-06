package automately.core.job;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SharedDataJobStoreTest {

    private Vertx vertx;
    private JobStore store;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataJobStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void concurrentUpdatesLoseNoChanges() throws Exception {
        Job job = Job.queued("job-1", "t", "s", new JsonObject(), 1, OrphanPolicy.FAIL, 1, 0);
        await(store.save(job));

        List<Future<Optional<Job>>> updates = new ArrayList<>();
        IntStream.range(0, 50).forEach(i -> updates.add(store.update("job-1", current -> Optional.of(withLog(current, "line " + i)))));
        await(Future.all(updates));

        assertThat(await(store.get("job-1")).logs()).hasSize(50);
    }

    @Test
    void aTransitionThatDoesNotApplyChangesNothing() throws Exception {
        Job job = Job.queued("job-1", "t", "s", new JsonObject(), 1, OrphanPolicy.FAIL, 1, 0);
        await(store.save(job));

        assertThat(await(store.update("job-1", current -> current.started(7, "n", "i", 1)))).isEmpty();
        assertThat(await(store.update("missing", current -> Optional.of(current)))).isEmpty();
        assertThat(await(store.get("job-1"))).isEqualTo(job);
    }

    @Test
    void activeListsOnlyUnfinishedJobs() throws Exception {
        await(store.save(Job.queued("q", "t", "s", null, 1, OrphanPolicy.FAIL, 1, 0)));
        Job done = Job.queued("d", "t", "s", null, 1, OrphanPolicy.FAIL, 1, 0).rejected("no", 1).orElseThrow();
        await(store.save(done));

        assertThat(await(store.active())).extracting(Job::id).containsExactly("q");
    }

    private static Job withLog(Job job, String line) {
        List<String> logs = new ArrayList<>(job.logs());
        logs.add(line);
        return new Job(job.id(), job.tenant(), job.script(), job.input(), job.timeoutMs(), job.onOrphan(), job.maxAttempts(),
                job.status(), job.attempt(), job.node(), job.nodeInstance(), job.output(), job.error(), logs, job.attempts(),
                job.createdAt(), job.queuedAt(), job.startedAt(), job.finishedAt());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
