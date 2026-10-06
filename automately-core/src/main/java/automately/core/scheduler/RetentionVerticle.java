package automately.core.scheduler;

import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import automately.core.state.JobStateStore;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Deletes finished jobs (and their checkpoints and steps) once they're older than the retention period, so
 * a durable store doesn't grow forever. Runs on the leader only, in batches.
 */
public final class RetentionVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionVerticle.class);
    static final int BATCH = 500;

    private final String instanceId;
    private final JobStore jobs;
    private final JobStateStore state;
    private final NodeRegistry nodes;
    private final Duration retention;
    private final Duration interval;
    private long timer;
    private boolean sweeping;

    public RetentionVerticle(String instanceId, JobStore jobs, JobStateStore state, NodeRegistry nodes, Duration retention, Duration interval) {
        this.instanceId = instanceId;
        this.jobs = jobs;
        this.state = state;
        this.nodes = nodes;
        this.retention = retention;
        this.interval = interval;
    }

    @Override
    public Future<?> start() {
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
                .compose(live -> isLeader(live) ? deleteBatch() : Future.succeededFuture(0))
                .onSuccess(count -> {
                    if (count > 0) {
                        LOG.info("Retention removed {} finished jobs older than {}", count, retention);
                    }
                })
                .onFailure(err -> LOG.warn("Retention sweep failed: {}", err.getMessage()))
                .onComplete(ar -> sweeping = false);
    }

    private Future<Integer> deleteBatch() {
        return jobs.deleteFinishedBefore(System.currentTimeMillis() - retention.toMillis(), BATCH).compose(deleted -> {
            List<Future<Void>> cleaned = deleted.stream().map((Job job) -> state.delete(job.id(), job.attempt())).toList();
            return Future.join(cleaned).map(done -> deleted.size());
        });
    }

    private boolean isLeader(List<NodeInfo> live) {
        return live.stream().map(NodeInfo::instanceId).min(Comparator.naturalOrder()).map(instanceId::equals).orElse(false);
    }
}
