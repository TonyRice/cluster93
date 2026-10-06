package automately.server;

import automately.core.job.JobStore;
import automately.core.job.SharedDataJobStore;
import automately.core.node.NodeRegistry;
import automately.core.node.SharedDataNodeRegistry;
import automately.core.runtime.ScriptRuntime;
import automately.core.scheduler.JobScheduler;
import automately.core.scheduler.NodeVerticle;
import automately.core.scheduler.OrphanReaper;
import automately.core.state.JobStateStore;
import automately.core.state.SharedDataJobStateStore;
import automately.runtime.javet.JavetScriptRuntime;
import automately.server.api.ApiVerticle;
import automately.server.cluster.Clustering;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.UUID;

/** Wires one node together. This is the only place that chooses implementations. */
public final class Automately {

    private Automately() {
    }

    public static Future<RunningNode> start(ServerConfig config) {
        return Clustering.vertx(config).compose(vertx -> {
            // A fresh id per process start: a restarted node must not be mistaken for its previous life (D-009).
            String instanceId = UUID.randomUUID().toString();
            JobStore jobs = new SharedDataJobStore(vertx);
            JobStateStore state = new SharedDataJobStateStore(vertx);
            NodeRegistry nodes = new SharedDataNodeRegistry(vertx, config.heartbeat().multipliedBy(3));
            ScriptRuntime runtime = new JavetScriptRuntime(vertx, config.maxHeapMb());
            JobScheduler scheduler = new JobScheduler(vertx, jobs, nodes);
            ApiVerticle api = new ApiVerticle(config, jobs, state, nodes, scheduler);

            return vertx.deployVerticle(new NodeVerticle(config.nodeId(), instanceId, config.capacity(), jobs, state, nodes, runtime,
                            config.heartbeat()))
                    .compose(id -> vertx.deployVerticle(new OrphanReaper(instanceId, jobs, nodes, scheduler, config.heartbeat(),
                            config.orphanGrace(), config.requeueAfter())))
                    .compose(id -> vertx.deployVerticle(api))
                    .map(id -> new RunningNode(config.nodeId(), instanceId, vertx, api.port()))
                    .recover(err -> vertx.close().transform(v -> Future.failedFuture(err)));
        });
    }

    public record RunningNode(String nodeId, String instanceId, Vertx vertx, int httpPort) {

        public Future<Void> stop() {
            return vertx.close();
        }
    }
}
