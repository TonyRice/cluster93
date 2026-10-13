package cluster93.core.files;

import cluster93.core.node.NodeInfo;
import cluster93.core.node.NodeRegistry;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;

/** On the leader, deletes the chunks of replaced, deleted and abandoned file versions once their grace period is over. */
public final class FileGarbageVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(FileGarbageVerticle.class);

    private final String instanceId;
    private final FileStore files;
    private final NodeRegistry nodes;
    private final Duration interval;
    private long timer;
    private boolean sweeping;

    public FileGarbageVerticle(String instanceId, FileStore files, NodeRegistry nodes, Duration interval) {
        this.instanceId = instanceId;
        this.files = files;
        this.nodes = nodes;
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
                .compose(live -> live.stream().map(NodeInfo::instanceId).min(Comparator.naturalOrder()).map(instanceId::equals).orElse(false)
                        ? files.collectGarbage(System.currentTimeMillis())
                        : Future.succeededFuture(0))
                .onSuccess(count -> {
                    if (count > 0) {
                        LOG.info("Deleted the chunks of {} old file versions", count);
                    }
                })
                .onFailure(err -> LOG.warn("File garbage sweep failed", err))
                .onComplete(ar -> sweeping = false);
    }
}
