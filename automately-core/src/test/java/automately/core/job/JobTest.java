package automately.core.job;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobTest {

    private static Job queued(OrphanPolicy policy, int maxAttempts) {
        return Job.queued("job-1", "tenant-a", "script", new JsonObject(), 1_000, policy, maxAttempts, 100);
    }

    private static Job running(OrphanPolicy policy, int maxAttempts) {
        return queued(policy, maxAttempts).started(1, "node-a", "inst-a", 200).orElseThrow();
    }

    @Test
    void onlyTheQueuedAttemptCanBeStarted() {
        Job job = queued(OrphanPolicy.FAIL, 1);

        assertThat(job.started(2, "node-a", "inst-a", 1)).isEmpty();
        Job started = job.started(1, "node-a", "inst-a", 1).orElseThrow();
        assertThat(started.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(started.started(1, "node-b", "inst-b", 2)).as("a second node can't claim it").isEmpty();
    }

    @Test
    void finishingIsRejectedForAnotherAttemptOrInstance() {
        Job job = running(OrphanPolicy.FAIL, 1);

        assertThat(job.finished(2, "inst-a", JobStatus.SUCCEEDED, 1, null, List.of(), 300)).isEmpty();
        assertThat(job.finished(1, "inst-old", JobStatus.SUCCEEDED, 1, null, List.of(), 300)).isEmpty();
        Job done = job.finished(1, "inst-a", JobStatus.SUCCEEDED, 1, null, List.of("hi"), 300).orElseThrow();
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.attempts()).singleElement().satisfies(a -> {
            assertThat(a.attempt()).isEqualTo(1);
            assertThat(a.outcome()).isEqualTo(JobStatus.SUCCEEDED);
        });
    }

    @Test
    void failPolicyEndsAsOrphaned() {
        Job orphaned = running(OrphanPolicy.FAIL, 1).orphaned(1, "inst-a", 500).orElseThrow();

        assertThat(orphaned.status()).isEqualTo(JobStatus.ORPHANED);
        assertThat(orphaned.status().isTerminal()).isTrue();
        assertThat(orphaned.error()).contains("onOrphan is \"fail\"");
        assertThat(orphaned.attempts()).extracting(Attempt::outcome).containsExactly(JobStatus.ORPHANED);
    }

    @Test
    void retryQueuesTheNextAttemptUntilAttemptsRunOut() {
        Job second = running(OrphanPolicy.RETRY, 2).orphaned(1, "inst-a", 500).orElseThrow();

        assertThat(second.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.node()).isNull();
        assertThat(second.queuedAt()).isEqualTo(500);
        assertThat(second.previousAttempt().node()).isEqualTo("node-a");

        Job last = second.started(2, "node-b", "inst-b", 600).orElseThrow().orphaned(2, "inst-b", 700).orElseThrow();
        assertThat(last.status()).isEqualTo(JobStatus.ORPHANED);
        assertThat(last.error()).contains("no attempts left");
        assertThat(last.attempts()).extracting(Attempt::attempt).containsExactly(1, 2);
    }

    @Test
    void aStaleOrphanDecisionIsRejected() {
        Job job = running(OrphanPolicy.RETRY, 3);

        assertThat(job.orphaned(1, "some-other-instance", 500)).isEmpty();
        Job second = job.orphaned(1, "inst-a", 500).orElseThrow();
        assertThat(second.orphaned(1, "inst-a", 600)).as("attempt 1 was already handled").isEmpty();
    }

    @Test
    void requeueOnlyAppliesToTheQueuedAttempt() {
        Job job = queued(OrphanPolicy.FAIL, 1);

        assertThat(job.requeued(1, 900).orElseThrow().queuedAt()).isEqualTo(900);
        assertThat(job.requeued(2, 900)).isEmpty();
        assertThat(running(OrphanPolicy.FAIL, 1).requeued(1, 900)).isEmpty();
    }

    @Test
    void roundTripsThroughJson() {
        Job job = running(OrphanPolicy.RESUME, 3).orphaned(1, "inst-a", 500).orElseThrow();

        assertThat(Job.fromJson(job.toJson())).isEqualTo(job);
    }

    @Test
    void summaryHidesScriptAndInstanceIds() {
        JsonObject summary = running(OrphanPolicy.RETRY, 2).orphaned(1, "inst-a", 500).orElseThrow().toSummaryJson();

        assertThat(summary.containsKey("script")).isFalse();
        assertThat(summary.containsKey("nodeInstance")).isFalse();
        assertThat(summary.getJsonArray("attempts").getJsonObject(0).containsKey("nodeInstance")).isFalse();
        assertThat(summary.getString("onOrphan")).isEqualTo("retry");
    }

    @Test
    void cancellingAQueuedJobEndsItAtOnce() {
        Job cancelled = queued(OrphanPolicy.RETRY, 3).cancelRequested("user asked", 300).orElseThrow();

        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.error()).isEqualTo("Cancelled: user asked");
        assertThat(cancelled.finishedAt()).isEqualTo(300);
    }

    @Test
    void cancellingARunningJobMarksItOnce() {
        Job marked = running(OrphanPolicy.RETRY, 3).cancelRequested("user asked", 300).orElseThrow();

        assertThat(marked.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(marked.cancelRequested()).isEqualTo("user asked");
        assertThat(marked.cancelRequested("again", 400)).isEmpty();
        Job done = marked.finished(1, "inst-a", JobStatus.CANCELLED, null, "Stopped: user asked", List.of(), 500).orElseThrow();
        assertThat(done.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(done.cancelRequested("late", 600)).as("finished jobs can't be cancelled").isEmpty();
    }

    @Test
    void anOrphanWithAPendingCancelIsCancelledNotRetried() {
        Job marked = running(OrphanPolicy.RETRY, 3).cancelRequested("user asked", 300).orElseThrow();

        Job ended = marked.orphaned(1, "inst-a", 500).orElseThrow();

        assertThat(ended.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(ended.error()).contains("user asked").contains("disappeared before it stopped");
        assertThat(ended.attempts()).extracting(Attempt::outcome).containsExactly(JobStatus.ORPHANED);
    }

    @Test
    void serviceRunsRoundTripThroughJson() {
        Job run = Job.queued("job-9", "t", "s", new JsonObject().put("everyMs", 5), 0, OrphanPolicy.FAIL, 1,
                new ServiceRef("poller", 2, 7, "job-8"), 100);

        assertThat(Job.fromJson(run.toJson())).isEqualTo(run);
        assertThat(run.toSummaryJson().getJsonObject("service").getInteger("run")).isEqualTo(7);
    }
}
