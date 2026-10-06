package automately.server;

import automately.core.job.JobStore;
import automately.core.job.SharedDataJobStore;
import automately.core.node.NodeRegistry;
import automately.core.node.SharedDataNodeRegistry;
import automately.core.runtime.ScriptRuntime;
import automately.core.scheduler.JobScheduler;
import automately.core.scheduler.NodeVerticle;
import automately.core.scheduler.OrphanReaper;
import automately.core.service.ServiceStore;
import automately.core.service.ServiceSupervisor;
import automately.core.service.SharedDataServiceStore;
import automately.core.state.JobStateStore;
import automately.core.state.SharedDataJobStateStore;
import automately.runtime.javet.JavetScriptRuntime;
import automately.core.host.DomainStore;
import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.HostRouter;
import automately.core.host.HostServicesFactory;
import automately.core.host.NodeHostServices;
import automately.core.host.ServeRegistry;
import automately.core.host.ServeVerticle;
import automately.core.host.SharedDataKvStore;
import automately.core.host.TxtResolver;
import automately.server.api.ApiVerticle;
import automately.server.ingress.DnsTxtResolver;
import automately.server.ingress.IngressVerticle;
import automately.server.cluster.Clustering;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.UUID;

/** Wires one node together. This is the only place that chooses implementations. */
public final class Automately {

    private Automately() {
    }

    public static Future<RunningNode> start(ServerConfig config) {
        return start(config, DnsTxtResolver::new);
    }

    /** {@code txt} builds the DNS TXT resolver used for domain verification; tests substitute a fake. */
    public static Future<RunningNode> start(ServerConfig config, java.util.function.Function<Vertx, TxtResolver> txt) {
        return Clustering.vertx(config).compose(vertx -> {
            // A fresh id per process start: a restarted node must not be mistaken for its previous life (D-009).
            String instanceId = UUID.randomUUID().toString();
            JobStore jobs = new SharedDataJobStore(vertx);
            JobStateStore state = new SharedDataJobStateStore(vertx);
            NodeRegistry nodes = new SharedDataNodeRegistry(vertx, config.heartbeat().multipliedBy(3));
            ServiceStore services = new SharedDataServiceStore(vertx);
            ScriptRuntime runtime = new JavetScriptRuntime(vertx, config.maxHeapMb(), config.capacity());
            JobScheduler scheduler = new JobScheduler(vertx, jobs, nodes);
            ServeRegistry serving = new ServeRegistry();
            HostServicesFactory hostServices = NodeHostServices.factory(vertx, new SharedDataKvStore(vertx),
                    new FetchClient(vertx, new EgressPolicy(config.egressAllowPrivate())), serving);
            DomainStore domains = new DomainStore(vertx);
            HostRouter hostRouter = new HostRouter(config.ingressDomain(), domains);
            ApiVerticle api = new ApiVerticle(config, jobs, state, nodes, scheduler, services, domains, txt.apply(vertx), hostRouter);
            IngressVerticle ingress = new IngressVerticle(config.ingressPort(), hostRouter, services, jobs);

            return vertx.deployVerticle(new NodeVerticle(config.nodeId(), instanceId, config.capacity(), jobs, state, nodes, runtime,
                            config.heartbeat(), config.stopGrace(), hostServices))
                    .compose(id -> vertx.deployVerticle(new ServeVerticle(instanceId, serving)))
                    .compose(id -> vertx.deployVerticle(new OrphanReaper(instanceId, jobs, nodes, scheduler, config.heartbeat(),
                            config.orphanGrace(), config.requeueAfter())))
                    .compose(id -> vertx.deployVerticle(new ServiceSupervisor(instanceId, services, jobs, nodes, scheduler,
                            config.heartbeat(), ServiceSupervisor.Timing.DEFAULT)))
                    .compose(id -> vertx.deployVerticle(api))
                    .compose(id -> vertx.deployVerticle(ingress))
                    .map(id -> new RunningNode(config.nodeId(), instanceId, vertx, api.port(), ingress.port()))
                    .recover(err -> vertx.close().transform(v -> Future.failedFuture(err)));
        });
    }

    public record RunningNode(String nodeId, String instanceId, Vertx vertx, int httpPort, int ingressPort) {

        public Future<Void> stop() {
            return vertx.close();
        }
    }
}
