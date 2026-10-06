package automately.core.scheduler;

import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.job.OrphanPolicy;
import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Accepts jobs and places each attempt on the least-loaded live node, the same policy the old
 * {@code JobServer} called {@code execute_on_least_jobs}. A node that turns an attempt down because it
 * filled up since its last heartbeat is skipped and the next candidate is tried.
 */
public final class JobScheduler {

    private static final DeliveryOptions DISPATCH = new DeliveryOptions().setSendTimeout(5_000);

    private final Vertx vertx;
    private final JobStore jobs;
    private final NodeRegistry nodes;

    public JobScheduler(Vertx vertx, JobStore jobs, NodeRegistry nodes) {
        this.vertx = vertx;
        this.jobs = jobs;
        this.nodes = nodes;
    }

    public Future<Job> submit(String tenant, String script, JsonObject input, Duration timeout, OrphanPolicy onOrphan, int maxAttempts) {
        Job job = Job.queued(UUID.randomUUID().toString(), tenant, script, input, timeout.toMillis(), onOrphan, maxAttempts,
                System.currentTimeMillis());
        return jobs.save(job)
                .compose(v -> dispatch(job))
                .recover(err -> jobs.update(job.id(), current -> current.rejected(err.getMessage(), System.currentTimeMillis()))
                        .compose(rejected -> rejected.isPresent() ? Future.succeededFuture(rejected.get()) : jobs.get(job.id())));
    }

    /** Offers the job's current attempt to live nodes, least loaded first. Used for new and re-queued attempts. */
    public Future<Job> dispatch(Job job) {
        return nodes.liveNodes().compose(live -> {
            List<NodeInfo> candidates = candidates(live);
            if (candidates.isEmpty()) {
                return Future.failedFuture("No node has free capacity (" + (live.isEmpty() ? "no live nodes" : live.stream()
                        .map(n -> n.id() + " " + n.running() + "/" + n.capacity())
                        .collect(java.util.stream.Collectors.joining(", "))) + ")");
            }
            return dispatch(job, candidates, 0, new ArrayList<>());
        });
    }

    static List<NodeInfo> candidates(List<NodeInfo> live) {
        return live.stream()
                .filter(NodeInfo::hasCapacity)
                .sorted(Comparator.comparingDouble(NodeInfo::load).thenComparing(NodeInfo::id))
                .toList();
    }

    private Future<Job> dispatch(Job job, List<NodeInfo> candidates, int index, List<String> refusals) {
        if (index >= candidates.size()) {
            return Future.failedFuture("No node accepted the job (" + String.join("; ", refusals) + ")");
        }
        NodeInfo node = candidates.get(index);
        JsonObject request = new JsonObject().put("jobId", job.id()).put("attempt", job.attempt());
        return vertx.eventBus()
                .request(Addresses.nodeRun(node.instanceId()), request, DISPATCH)
                .map(reply -> job)
                .recover(err -> {
                    refusals.add(node.id() + ": " + err.getMessage());
                    return dispatch(job, candidates, index + 1, refusals);
                });
    }
}
