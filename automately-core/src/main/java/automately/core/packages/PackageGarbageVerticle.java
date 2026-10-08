package automately.core.packages;

import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;

/**
 * On the leader, deletes package files no version names (D-092): uploads whose publish never came, and files a purge
 * kept until their last version went. Only files older than the grace period go, so a publish in progress is safe.
 */
public final class PackageGarbageVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(PackageGarbageVerticle.class);

    private final String instanceId;
    private final PackageStore packages;
    private final NodeRegistry nodes;
    private final Duration interval;
    private final Duration grace;
    private long timer;
    private boolean sweeping;

    public PackageGarbageVerticle(String instanceId, PackageStore packages, NodeRegistry nodes, Duration interval, Duration grace) {
        this.instanceId = instanceId;
        this.packages = packages;
        this.nodes = nodes;
        this.interval = interval;
        this.grace = grace;
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
                        ? packages.collectGarbage(System.currentTimeMillis(), grace.toMillis())
                        : Future.succeededFuture(0))
                .onSuccess(count -> {
                    if (count > 0) {
                        LOG.info("Deleted {} package files no version uses", count);
                    }
                })
                .onFailure(err -> LOG.warn("Package garbage sweep failed", err))
                .onComplete(ar -> sweeping = false);
    }
}
