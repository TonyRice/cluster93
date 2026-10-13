package cluster93.core.scheduler;

import cluster93.core.job.Job;
import cluster93.core.job.JobStore;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.job.ServiceRef;
import cluster93.core.node.NodeInfo;
import cluster93.core.node.NodeRegistry;
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
        return submit(tenant, script, input, timeout, onOrphan, maxAttempts, java.util.function.UnaryOperator.identity());
    }

    /** As {@link #submit}, with {@code prepare} applied to the job before it's saved, such as attaching a callback. */
    public Future<Job> submit(String tenant, String script, JsonObject input, Duration timeout, OrphanPolicy onOrphan, int maxAttempts,
                              java.util.function.UnaryOperator<Job> prepare) {
        cluster93.core.metrics.Metrics.of(vertx).jobSubmitted(false);
        Job job = prepare.apply(Job.queued(UUID.randomUUID().toString(), tenant, script, input, timeout.toMillis(), onOrphan, maxAttempts,
                System.currentTimeMillis()));
        return jobs.save(job)
                .compose(v -> dispatch(job))
                .recover(err -> jobs.update(job.id(), current -> current.rejected(err.getMessage(), System.currentTimeMillis()))
                        .compose(rejected -> rejected.isPresent() ? Future.succeededFuture(rejected.get()) : jobs.get(job.id())));
    }

    /**
     * Cancels a job. A queued job ends {@code CANCELLED} at once. A running job is marked (durably) and
     * its node is told to stop it gracefully; if that message is lost, the node's heartbeat check or the
     * reaper still honours the mark. Completes with the job as it is after the request.
     */
    public Future<Job> cancel(String jobId, String reason) {
        return jobs.update(jobId, job -> job.cancelRequested(reason, System.currentTimeMillis())).compose(updated -> {
            if (updated.isEmpty()) {
                return jobs.get(jobId);
            }
            Job job = updated.get();
            if (job.status().isTerminal()) {
                vertx.eventBus().publish(Addresses.jobEvents(jobId), new JsonObject().put("type", "end").put("status", job.status().name()));
                return Future.succeededFuture(job);
            }
            JsonObject request = new JsonObject().put("jobId", jobId).put("attempt", job.attempt()).put("reason", reason);
            return vertx.eventBus().request(Addresses.nodeControl(job.nodeInstance()), request, DISPATCH)
                    .transform(ignored -> Future.succeededFuture(job));
        });
    }

    /** Submits a run of a service: like {@link #submit} but with no timeout, and returning the job even if no node takes it yet. */
    public Future<Job> submitServiceRun(String jobId, String tenant, String script, JsonObject config, ServiceRef service, boolean secrets) {
        return submitServiceRun(jobId, tenant, script, config, service, secrets, null);
    }

    /** As above, with the revision's package versions (D-089). */
    public Future<Job> submitServiceRun(String jobId, String tenant, String script, JsonObject config, ServiceRef service, boolean secrets,
                                        JsonObject packages) {
        return submitServiceRun(jobId, tenant, script, config, service, secrets, packages, null);
    }

    /** As above, with the revision's {@code process.env} declaration (D-095). */
    public Future<Job> submitServiceRun(String jobId, String tenant, String script, JsonObject config, ServiceRef service, boolean secrets,
                                        JsonObject packages, JsonObject env) {
        cluster93.core.metrics.Metrics.of(vertx).jobSubmitted(true);
        Job job = Job.queued(jobId, tenant, script, config, 0, OrphanPolicy.FAIL, 1, service, System.currentTimeMillis()).withSecrets(secrets)
                .withPackages(packages).withEnv(env);
        // If no node can take it now, it stays QUEUED and the reaper re-dispatches it later.
        return jobs.save(job).compose(v -> dispatch(job).otherwise(job));
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

    /**
     * Nodes a job attempt may be placed on, least loaded first. A node must have capacity left and be a compute node:
     * a node playing no {@code worker} role advertises capacity 0 (docs/design/node-roles.md), so it is never chosen.
     */
    static List<NodeInfo> candidates(List<NodeInfo> live) {
        return live.stream()
                .filter(node -> node.capacity() > 0)
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
