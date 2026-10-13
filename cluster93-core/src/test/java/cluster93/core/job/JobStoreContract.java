package cluster93.core.job;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Behaviour every {@link JobStore} must have; each implementation's test extends this. */
public abstract class JobStoreContract {

    /** Set by the implementation's test before each test. */
    protected JobStore store;



    @Test
    public void concurrentUpdatesLoseNoChanges() throws Exception {
        Job job = Job.queued("job-1", "t", "s", new JsonObject(), 1, OrphanPolicy.FAIL, 1, 0);
        await(store.save(job));

        List<Future<Optional<Job>>> updates = new ArrayList<>();
        IntStream.range(0, 50).forEach(i -> updates.add(store.update("job-1", current -> Optional.of(withLog(current, "line " + i)))));
        await(Future.all(updates));

        assertThat(await(store.get("job-1")).logs()).hasSize(50);
    }

    @Test
    public void aTransitionThatDoesNotApplyChangesNothing() throws Exception {
        Job job = Job.queued("job-1", "t", "s", new JsonObject(), 1, OrphanPolicy.FAIL, 1, 0);
        await(store.save(job));

        assertThat(await(store.update("job-1", current -> current.started(7, "n", "i", 1)))).isEmpty();
        assertThat(await(store.update("missing", current -> Optional.of(current)))).isEmpty();
        assertThat(await(store.get("job-1"))).isEqualTo(job);
    }

    @Test
    public void activeListsOnlyUnfinishedJobs() throws Exception {
        await(store.save(Job.queued("q", "t", "s", null, 1, OrphanPolicy.FAIL, 1, 0)));
        Job done = Job.queued("d", "t", "s", null, 1, OrphanPolicy.FAIL, 1, 0).rejected("no", 1).orElseThrow();
        await(store.save(done));

        assertThat(await(store.active())).extracting(Job::id).containsExactly("q");
    }

    @Test
    public void listsATenantsNewestJobsFirstUpToTheLimit() throws Exception {
        for (int i = 1; i <= 5; i++) {
            await(store.save(Job.queued("a-" + i, "tenant-a", "s", null, 1, OrphanPolicy.FAIL, 1, 1000L * i)));
        }
        await(store.save(Job.queued("b-1", "tenant-b", "s", null, 1, OrphanPolicy.FAIL, 1, 9000)));

        assertThat(await(store.list("tenant-a", 3))).extracting(Job::id).containsExactly("a-5", "a-4", "a-3");
        assertThat(await(store.list("tenant-b", 10))).extracting(Job::id).containsExactly("b-1");
    }

    @Test
    public void deletesOnlyJobsThatFinishedBeforeTheCutoff() throws Exception {
        Job old = Job.queued("old", "t", "s", null, 1, OrphanPolicy.FAIL, 1, 0).rejected("x", 100).orElseThrow();
        Job recent = Job.queued("recent", "t", "s", null, 1, OrphanPolicy.FAIL, 1, 0).rejected("x", 5000).orElseThrow();
        Job running = Job.queued("running", "t", "s", null, 1, OrphanPolicy.FAIL, 1, 0);
        for (Job job : List.of(old, recent, running)) {
            await(store.save(job));
        }

        List<Job> deleted = await(store.deleteFinishedBefore(1000, 100));

        assertThat(deleted).extracting(Job::id).containsExactly("old");
        assertThat(await(store.get("old"))).isNull();
        assertThat(await(store.get("recent"))).isNotNull();
        assertThat(await(store.get("running"))).isNotNull();
    }

    private static Job withLog(Job job, String line) {
        List<String> logs = new ArrayList<>(job.logs());
        logs.add(line);
        return job.edit().logs(logs).build();
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
