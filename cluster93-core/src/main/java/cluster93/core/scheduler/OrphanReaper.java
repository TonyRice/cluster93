package cluster93.core.scheduler;

import cluster93.core.job.Job;
import cluster93.core.job.JobStatus;
import cluster93.core.job.JobStore;
import cluster93.core.node.NodeInfo;
import cluster93.core.node.NodeRegistry;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds jobs whose node disappeared and applies their {@code onOrphan} policy. It also re-dispatches
 * attempts that no node picked up. See docs/design/orphaned-jobs.md.
 *
 * <p>Every node runs one and tracks suspects, but only the live instance with the lowest id acts (D-012). No lock is needed:
 * every change is a compare-and-set transition, so if two reapers overlap while membership changes,
 * only one change wins.
 *
 * <p>A job is only reaped after its node has been missing for {@code grace}, on top of the registry's
 * TTL, so a long GC pause doesn't trigger a takeover. A job known to have run on an earlier incarnation of
 * this node (a restart, so its instance id moved on) skips that wait, so a restart recovers at once.
 */
public final class OrphanReaper extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanReaper.class);

    private final String nodeId;
    private final String instanceId;
    private final JobStore jobs;
    private final NodeRegistry nodes;
    private final JobScheduler scheduler;
    private final Duration interval;
    private final Duration grace;
    private final Duration requeueAfter;

    /** When each suspect attempt ({@code jobId#attempt}) was first seen without a live node. */
    private final Map<String, Long> suspects = new HashMap<>();
    private boolean sweeping;
    private long timer;

    /**
     * @param nodeId   this node's stable name, or null to disable the fast path that recognises jobs left
     *                 behind by an earlier incarnation of this node (the overload without it)
     */
    public OrphanReaper(String nodeId, String instanceId, JobStore jobs, NodeRegistry nodes, JobScheduler scheduler,
                        Duration interval, Duration grace, Duration requeueAfter) {
        this.nodeId = nodeId;
        this.instanceId = instanceId;
        this.jobs = jobs;
        this.nodes = nodes;
        this.scheduler = scheduler;
        this.interval = interval;
        this.grace = grace;
        this.requeueAfter = requeueAfter;
    }

    /** Backward-compatible constructor without a node name; the same-node fast path is then disabled. */
    public OrphanReaper(String instanceId, JobStore jobs, NodeRegistry nodes, JobScheduler scheduler,
                        Duration interval, Duration grace, Duration requeueAfter) {
        this(null, instanceId, jobs, nodes, scheduler, interval, grace, requeueAfter);
    }

    @Override
    public Future<?> start() {
        // A node that restarted must recover what it left behind now, not after the first interval.
        sweep();
        timer = vertx.setPeriodic(interval.toMillis(), id -> sweep());
        return Future.succeededFuture();
    }

    @Override
    public Future<?> stop() {
        vertx.cancelTimer(timer);
        return Future.succeededFuture();
    }

    private void sweep() {
        if (sweeping) {
            return;
        }
        sweeping = true;
        nodes.liveNodes()
                .compose(live -> jobs.active().compose(active -> reap(active, live, isLeader(live))))
                // A cluster call can hang while a member is leaving (Hazelcast's default operation timeout is
                // minutes). Without a deadline, `sweeping` would stay set and reaping would stop until then.
                .timeout(sweepDeadline())
                .onFailure(err -> LOG.warn("Orphan sweep did not complete", err))
                .onComplete(ar -> sweeping = false);
    }

    private Duration sweepDeadline() {
        return interval.multipliedBy(5).compareTo(Duration.ofSeconds(2)) > 0 ? interval.multipliedBy(5) : Duration.ofSeconds(2);
    }

    private boolean isLeader(List<NodeInfo> live) {
        return live.stream().map(NodeInfo::instanceId).min(Comparator.naturalOrder())
                .map(instanceId::equals)
                .orElse(false);
    }

    /**
     * Suspects are tracked on every node, leader or not, so that a change of leader doesn't restart the
     * grace period. Only the leader acts.
     *
     * <p>A suspect whose node is known to have run on an earlier incarnation of this node is reaped at once,
     * without the grace that protects a node that only looked dead (a partition or a long GC pause). That is
     * safe because this node itself restarted and holds a newer instance id, so its previous incarnation
     * cannot be running anything.
     */
    private Future<Void> reap(List<Job> active, List<NodeInfo> live, boolean leader) {
        long now = System.currentTimeMillis();
        Set<String> liveInstances = live.stream().map(NodeInfo::instanceId).collect(Collectors.toSet());
        Set<String> stillSuspect = new HashSet<>();

        for (Job job : active) {
            if (job.status() == JobStatus.RUNNING && !liveInstances.contains(job.nodeInstance())) {
                String key = job.id() + "#" + job.attempt();
                stillSuspect.add(key);
                long firstSeen = suspects.computeIfAbsent(key, k -> now);
                if (leader && (knownDead(job, live, liveInstances) || now - firstSeen >= grace.toMillis())) {
                    orphan(job, now);
                }
            } else if (leader && job.status() == JobStatus.QUEUED && now - job.queuedAt() >= requeueAfter.toMillis()) {
                jobs.update(job.id(), current -> current.requeued(job.attempt(), now))
                        .onSuccess(requeued -> requeued.ifPresent(this::dispatch))
                        .onFailure(err -> LOG.warn("Could not re-queue attempt {} of job {}", job.attempt(), job.id(), err));
            }
        }
        suspects.keySet().retainAll(stillSuspect);
        return Future.succeededFuture();
    }

    /**
     * Whether the job's node is certainly gone: it ran on an earlier incarnation of this node (a restart, so
     * its instance id moved on). Peer nodes in a multi-node cluster always observe the full grace period: a
     * partition or a long GC pause on a lone survivor must not trigger a premature takeover.
     *
     * <p>A reaper built without this node's name keeps the conservative grace path: without that name it can't
     * tell whether a job belonged here, and the overload exists precisely to preserve the older behaviour.
     */
    private boolean knownDead(Job job, List<NodeInfo> live, Set<String> liveInstances) {
        if (nodeId == null) {
            return false;
        }
        return job.node() != null && job.node().equals(nodeId) && !instanceId.equals(job.nodeInstance());
    }

    private void orphan(Job job, long now) {
        jobs.update(job.id(), current -> current.orphaned(job.attempt(), job.nodeInstance(), now))
                .onSuccess(updated -> updated.ifPresent(next -> {
                    suspects.remove(job.id() + "#" + job.attempt());
                    if (next.status().isTerminal()) {
                        LOG.info("Job {} lost node {} (attempt {}, policy {}); it ends {}", job.id(), job.node(), job.attempt(),
                                job.onOrphan().apiName(), next.status());
                        vertx.eventBus().publish(Addresses.jobEvents(job.id()),
                                new JsonObject().put("type", "end").put("status", next.status().name()));
                    } else {
                        LOG.info("Job {} lost node {}; starting attempt {} ({})", job.id(), job.node(), next.attempt(), job.onOrphan().apiName());
                        dispatch(next);
                    }
                }))
                .onFailure(err -> LOG.warn("Could not mark attempt {} of job {} as orphaned", job.attempt(), job.id(), err));
    }

    private void dispatch(Job job) {
        // If no node can take it now, it stays QUEUED and is picked up again after `requeueAfter`.
        scheduler.dispatch(job).onFailure(err -> LOG.info("Attempt {} of job {} is waiting for capacity: {}",
                job.attempt(), job.id(), err.getMessage()));
    }
}
