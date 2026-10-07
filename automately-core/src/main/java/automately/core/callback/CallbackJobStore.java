package automately.core.callback;

import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.queue.QueueService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A job store that queues a callback delivery whenever a job with a callback ends (D-062). Every way a job ends is a
 * transition through {@link #update} (its node finishing it, the reaper orphaning it, a cancel, a rejection), and
 * compare-and-set lets exactly one of them end it, so each job is queued once. The queue's key is the job id, which
 * makes a repeat a no-op anyway.
 */
public final class CallbackJobStore implements JobStore {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackJobStore.class);

    private final JobStore jobs;
    private final QueueService queues;

    public CallbackJobStore(JobStore jobs, QueueService queues) {
        this.jobs = jobs;
        this.queues = queues;
    }

    @Override
    public Future<Optional<Job>> update(String id, Function<Job, Optional<Job>> transition) {
        boolean[] ended = {false};
        return jobs.update(id, current -> {
            Optional<Job> next = transition.apply(current);
            // Compare-and-set may run this more than once; only the last run counts.
            ended[0] = next.isPresent() && !current.status().isTerminal() && next.get().status().isTerminal() && next.get().callback() != null;
            return next;
        }).compose(updated -> {
            if (!ended[0] || updated.isEmpty()) {
                return Future.succeededFuture(updated);
            }
            Job job = updated.get();
            return queues.push(QueueService.SYSTEM_TENANT, Callbacks.QUEUE, new JsonObject().put("tenant", job.tenant()).put("jobId", job.id()),
                            null, "job-" + job.id(), Callbacks.MAX_DELIVERIES)
                    .map(updated)
                    .recover(err -> {
                        LOG.error("Couldn't queue the callback of job {}: {}", job.id(), err.getMessage());
                        return Future.succeededFuture(updated);
                    });
        });
    }

    @Override
    public Future<Void> save(Job job) {
        return jobs.save(job);
    }

    @Override
    public Future<Job> get(String id) {
        return jobs.get(id);
    }

    @Override
    public Future<List<Job>> list(String tenant, int limit) {
        return jobs.list(tenant, limit);
    }

    @Override
    public Future<List<Job>> active() {
        return jobs.active();
    }

    @Override
    public Future<List<Job>> deleteFinishedBefore(long cutoff, int limit) {
        return jobs.deleteFinishedBefore(cutoff, limit);
    }
}
