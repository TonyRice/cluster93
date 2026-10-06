package automately.core.scheduler;

import automately.core.job.Attempt;
import automately.core.job.Job;
import automately.core.job.JobStatus;
import automately.core.job.OrphanPolicy;
import automately.core.state.StepRecord;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static automately.core.scheduler.LocalCluster.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when a node disappears mid-job (docs/design/orphaned-jobs.md). Node "a-1" is always the
 * reaper leader and is never stopped; jobs are steered onto "z-1" by keeping "a-1" full. "Kill" stops a
 * node (it cancels its scripts, D-015); "partition" cuts a node off while it keeps running (a zombie).
 */
class OrphanHandlingTest {

    private LocalCluster cluster;
    private Job blocker;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new LocalCluster();
        cluster.start("leader", "a-1", 1);
        blocker = cluster.submit("hold", OrphanPolicy.FAIL, 1);
        cluster.awaitStatus(blocker.id(), JobStatus.RUNNING);
        cluster.start("victim", "z-1", 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        cluster.close();
    }

    /** Submits a job onto the victim node, then cuts the victim off (it keeps running) and frees the leader. */
    private Job runOnVictimThenPartition(String script, OrphanPolicy policy, int maxAttempts) throws Exception {
        Job job = cluster.submit(script, policy, maxAttempts);
        assertThat(cluster.awaitRunning(job.id(), 1).nodeInstance()).isEqualTo("z-1");
        cluster.partition("z-1");
        cluster.runtime.release(blocker.id(), 1);
        return job;
    }

    /** Submits a job onto the victim node, then stops it and frees the leader so a new attempt has somewhere to go. */
    private Job runOnVictimThenKill(String script, OrphanPolicy policy, int maxAttempts) throws Exception {
        Job job = cluster.submit(script, policy, maxAttempts);
        assertThat(cluster.awaitRunning(job.id(), 1).nodeInstance()).isEqualTo("z-1");
        if (script.equals("resumable")) {
            cluster.awaitJob(job.id(), j -> cluster.runtime.isHeld(j.id(), 1), "held after steps a and b");
        }
        cluster.kill("z-1");
        cluster.runtime.release(blocker.id(), 1);
        return job;
    }

    @Test
    void failPolicyEndsAsOrphanedAndNeverReruns() throws Exception {
        Job job = runOnVictimThenKill("hold", OrphanPolicy.FAIL, 1);

        Job orphaned = cluster.awaitTerminal(job.id());

        assertThat(orphaned.status()).isEqualTo(JobStatus.ORPHANED);
        assertThat(orphaned.attempt()).isEqualTo(1);
        assertThat(orphaned.attempts()).extracting(Attempt::outcome).containsExactly(JobStatus.ORPHANED);
        assertThat(orphaned.error()).contains("victim").contains("fail");
    }

    @Test
    void retryPolicyRunsANewAttemptFromTheStart() throws Exception {
        Job job = runOnVictimThenKill("hold", OrphanPolicy.RETRY, 3);

        Job second = cluster.awaitRunning(job.id(), 2);
        assertThat(second.node()).isEqualTo("leader");
        cluster.runtime.release(job.id(), 2);

        Job done = cluster.awaitTerminal(job.id());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.output()).isEqualTo("released attempt 2");
        assertThat(done.attempts()).extracting(Attempt::outcome).containsExactly(JobStatus.ORPHANED, JobStatus.SUCCEEDED);
    }

    @Test
    void resumePolicySkipsStepsThatAlreadyCompleted() throws Exception {
        Job job = runOnVictimThenKill("resumable", OrphanPolicy.RESUME, 3);

        Job done = cluster.awaitTerminal(job.id());

        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.attempt()).isEqualTo(2);
        assertThat(done.output()).isEqualTo(new JsonArray(List.of("a", "b", "c")));
        assertThat(cluster.runtime.executedSteps).as("a and b ran once, on attempt 1").containsExactly("a@1", "b@1", "c@2");
        assertThat(await(cluster.state.load(job.id(), 2)).steps()).extracting(StepRecord::name, StepRecord::attempt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", 1), org.assertj.core.groups.Tuple.tuple("b", 1),
                        org.assertj.core.groups.Tuple.tuple("c", 2));
    }

    @Test
    void retryPolicyGivesUpAfterMaxAttempts() throws Exception {
        Job job = cluster.submit("hold", OrphanPolicy.RETRY, 2);
        cluster.awaitRunning(job.id(), 1);
        cluster.kill("z-1");
        // The leader stays full, so attempt 2 lands on a second victim, which then dies too.
        cluster.start("victim-2", "z-2", 1);
        assertThat(cluster.awaitRunning(job.id(), 2).nodeInstance()).isEqualTo("z-2");
        cluster.kill("z-2");

        Job orphaned = cluster.awaitTerminal(job.id());

        assertThat(orphaned.status()).isEqualTo(JobStatus.ORPHANED);
        assertThat(orphaned.error()).contains("no attempts left");
        assertThat(orphaned.attempts()).extracting(Attempt::outcome).containsExactly(JobStatus.ORPHANED, JobStatus.ORPHANED);
    }

    @Test
    void aZombieAttemptCannotOverwriteTheAttemptThatReplacedIt() throws Exception {
        Job job = runOnVictimThenPartition("hold", OrphanPolicy.RETRY, 3);
        cluster.awaitRunning(job.id(), 2);

        // The partitioned node is still running attempt 1. On its next heartbeat it sees the job moved on and
        // cancels the attempt; the cancelled result is then rejected by the attempt check.
        cluster.awaitJob(job.id(), j -> !cluster.runtime.cancellations.isEmpty(), "after the zombie was cancelled");
        cluster.runtime.release(job.id(), 1);
        Thread.sleep(300);
        assertThat(cluster.job(job.id()).status()).as("attempt 1's result was discarded").isEqualTo(JobStatus.RUNNING);
        assertThat(cluster.job(job.id()).attempt()).isEqualTo(2);
        assertThat(cluster.runtime.cancellations).as("the partitioned node noticed it lost the job and stopped attempt 1")
                .contains(job.id() + "#1: attempt 1 was superseded");

        cluster.runtime.release(job.id(), 2);
        Job done = cluster.awaitTerminal(job.id());
        assertThat(done.output()).isEqualTo("released attempt 2");
    }

    @Test
    void aSupersededAttemptsStateWritesAreRejected() throws Exception {
        Job job = runOnVictimThenPartition("late-step", OrphanPolicy.RESUME, 3);
        cluster.awaitRunning(job.id(), 2);

        // The partitioned attempt 1 carries on and tries to record a step.
        cluster.runtime.release(job.id(), 1);
        cluster.awaitJob(job.id(), j -> !cluster.runtime.stateErrors.isEmpty(), "after the zombie's write");

        assertThat(cluster.runtime.stateErrors).singleElement().asString().startsWith("SUPERSEDED");
        assertThat(await(cluster.state.load(job.id(), 2)).step("late")).as("nothing leaked into attempt 2").isEmpty();
    }

    @Test
    void waitsForTheGracePeriodBeforeTakingOver() throws Exception {
        Job job = cluster.submit("hold", OrphanPolicy.FAIL, 1);
        cluster.awaitRunning(job.id(), 1);
        cluster.kill("z-1");

        Thread.sleep(LocalCluster.GRACE.toMillis() / 2);
        assertThat(cluster.job(job.id()).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(cluster.awaitTerminal(job.id()).status()).isEqualTo(JobStatus.ORPHANED);
    }

    @Test
    void aRestartedNodeWithTheSameNameDoesNotHideItsOrphans() throws Exception {
        Job job = cluster.submit("hold", OrphanPolicy.FAIL, 1);
        cluster.awaitRunning(job.id(), 1);
        cluster.kill("z-1");
        cluster.start("victim", "z-2", 1);

        Job orphaned = cluster.awaitTerminal(job.id());

        assertThat(orphaned.status()).isEqualTo(JobStatus.ORPHANED);
    }

    @Test
    void aQueuedJobNoNodePickedUpIsDispatchedEvenUnderFail() throws Exception {
        // As if it was dispatched to a node that died before starting it: nothing ever ran.
        Job stranded = Job.queued("stranded", "tenant-a", "hello", new JsonObject(), 10_000, OrphanPolicy.FAIL, 1,
                System.currentTimeMillis() - 60_000);
        await(cluster.jobs.save(stranded));

        Job done = cluster.awaitTerminal(stranded.id());

        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.attempt()).isEqualTo(1);
    }

    @Test
    void announcesNewAttemptsAndOnlyOneEnd() throws Exception {
        List<JsonObject> events = new CopyOnWriteArrayList<>();
        Job job = cluster.submit("hold", OrphanPolicy.RETRY, 3);
        cluster.awaitRunning(job.id(), 1);
        await(cluster.vertx.eventBus().<JsonObject>consumer(Addresses.jobEvents(job.id()), msg -> events.add(msg.body())).completion());
        cluster.kill("z-1");
        cluster.runtime.release(blocker.id(), 1);
        cluster.awaitRunning(job.id(), 2);
        cluster.runtime.release(job.id(), 2);
        cluster.awaitTerminal(job.id());
        Thread.sleep(200);

        assertThat(events).filteredOn(e -> e.getString("type").equals("attempt")).singleElement().satisfies(e -> {
            assertThat(e.getInteger("attempt")).isEqualTo(2);
            assertThat(e.getString("node")).isEqualTo("leader");
            assertThat(e.getString("previousOutcome")).isEqualTo("ORPHANED");
        });
        assertThat(events).filteredOn(e -> e.getString("type").equals("end")).singleElement()
                .satisfies(e -> assertThat(e.getString("status")).isEqualTo("SUCCEEDED"));
    }

    @Test
    void cancellingARunningJobStopsItGracefully() throws Exception {
        Job job = cluster.submit("hold", OrphanPolicy.RETRY, 3);
        cluster.awaitRunning(job.id(), 1);

        await(cluster.scheduler.cancel(job.id(), "user asked"));
        Job done = cluster.awaitTerminal(job.id());

        assertThat(done.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(done.error()).isEqualTo("Stopped: user asked");
        assertThat(cluster.runtime.stops).containsExactly(job.id() + "#1: user asked");
    }

    @Test
    void aCancelWhoseMessageWasLostIsStillHonouredByTheNode() throws Exception {
        Job job = cluster.submit("hold", OrphanPolicy.FAIL, 1);
        cluster.awaitRunning(job.id(), 1);

        // Mark it the way the scheduler does, but never tell the node.
        await(cluster.jobs.update(job.id(), j -> j.cancelRequested("quietly", System.currentTimeMillis())));

        assertThat(cluster.awaitTerminal(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void anOrphanedJobWithAPendingCancelIsNotRetried() throws Exception {
        Job job = runOnVictimThenKill("hold", OrphanPolicy.RETRY, 3);
        await(cluster.jobs.update(job.id(), j -> j.cancelRequested("user asked", System.currentTimeMillis())));

        Job done = cluster.awaitTerminal(job.id());

        assertThat(done.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(done.attempt()).isEqualTo(1);
        assertThat(cluster.runtime.started).doesNotContain(job.id() + "#2");
    }

    @Test
    void cancellingAQueuedJobEndsIt() throws Exception {
        Job stranded = Job.queued("stranded-cancel", "tenant-a", "hello", new JsonObject(), 10_000, OrphanPolicy.FAIL, 1,
                System.currentTimeMillis());
        await(cluster.jobs.save(stranded));

        Job cancelled = await(cluster.scheduler.cancel(stranded.id(), "changed my mind"));

        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        Thread.sleep(LocalCluster.REQUEUE_AFTER.toMillis() + 300);
        assertThat(cluster.runtime.started).as("a cancelled job is never dispatched").doesNotContain("stranded-cancel#1");
    }
}
