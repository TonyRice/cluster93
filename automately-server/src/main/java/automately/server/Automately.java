package automately.server;

import automately.core.job.JobStore;
import automately.core.job.SharedDataJobStore;
import automately.core.node.NodeRegistry;
import automately.core.node.SharedDataNodeRegistry;
import automately.core.runtime.ScriptRuntime;
import automately.core.scheduler.JobScheduler;
import automately.core.scheduler.NodeVerticle;
import automately.core.scheduler.OrphanReaper;
import automately.core.scheduler.RetentionVerticle;
import automately.core.host.KvStore;
import automately.cluster.ignite.IgniteCluster;
import automately.cluster.ignite.IgniteSettings;
import automately.cluster.ignite.store.IgniteStores;
import automately.core.service.ServiceStore;
import automately.core.service.ServiceSupervisor;
import automately.core.service.SharedDataServiceStore;
import automately.core.state.JobStateStore;
import automately.core.state.SharedDataJobStateStore;
import automately.runtime.javet.JavetScriptRuntime;
import automately.core.host.DomainStore;
import automately.core.host.SharedDataDomainStore;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.UUID;

/** Wires one node together. This is the only place that chooses implementations. */
public final class Automately {

    static final java.time.Duration RETENTION_INTERVAL = java.time.Duration.ofMinutes(10);

    private Automately() {
    }

    public static Future<RunningNode> start(ServerConfig config) {
        return start(config, DnsTxtResolver::new);
    }

    /** {@code txt} builds the DNS TXT resolver used for domain verification; tests substitute a fake. */
    public static Future<RunningNode> start(ServerConfig config, java.util.function.Function<Vertx, TxtResolver> txt) {
        if (!config.clustered()) {
            Vertx vertx = Vertx.vertx();
            return wire(config, txt, vertx, Stores.inMemory(vertx), null);
        }
        // The cluster is the database (D-030): Ignite comes up first (blocking, before Vert.x serves anything),
        // creates its tables, and then becomes Vert.x's cluster manager.
        IgniteCluster cluster;
        try {
            cluster = IgniteCluster.start(new IgniteSettings(config.nodeId(), config.dataDirectory(), config.clusterPort(),
                    config.clusterMembers(), config.dataBackups(), config.dataMemoryMb(), config.baselineAdjust(), config.clusterSize()));
            IgniteStores.createTables(cluster);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return cluster.vertx()
                .compose(vertx -> {
                    IgniteStores durable = IgniteStores.open(vertx, cluster);
                    Stores stores = new Stores(durable.jobs, durable.state, durable.services, durable.domains, durable.kv);
                    return wire(config, txt, vertx, stores, cluster);
                })
                .recover(err -> {
                    cluster.stop();
                    return Future.failedFuture(err);
                });
    }

    /** Where durable data lives: Ignite when clustered, in-memory maps for a single development node. */
    record Stores(JobStore jobs, JobStateStore state, ServiceStore services, DomainStore domains, KvStore kv) {

        static Stores inMemory(Vertx vertx) {
            return new Stores(new SharedDataJobStore(vertx), new SharedDataJobStateStore(vertx), new SharedDataServiceStore(vertx),
                    new SharedDataDomainStore(vertx), new SharedDataKvStore(vertx));
        }
    }

    private static Future<RunningNode> wire(ServerConfig config, java.util.function.Function<Vertx, TxtResolver> txt, Vertx vertx,
                                            Stores stores, IgniteCluster cluster) {
        // A fresh id per process start: a restarted node must not be mistaken for its previous life (D-009).
        String instanceId = UUID.randomUUID().toString();
        JobStore jobs = stores.jobs();
        JobStateStore state = stores.state();
        ServiceStore services = stores.services();
        DomainStore domains = stores.domains();
        NodeRegistry nodes = new SharedDataNodeRegistry(vertx, config.heartbeat().multipliedBy(3));
        ScriptRuntime runtime = new JavetScriptRuntime(vertx, config.maxHeapMb(), config.capacity());
        JobScheduler scheduler = new JobScheduler(vertx, jobs, nodes);
        ServeRegistry serving = new ServeRegistry();
        HostServicesFactory hostServices = NodeHostServices.factory(vertx, stores.kv(),
                new FetchClient(vertx, new EgressPolicy(config.egressAllowPrivate())), serving);
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
                .compose(id -> vertx.deployVerticle(new RetentionVerticle(instanceId, jobs, state, nodes, config.jobRetention(),
                        RETENTION_INTERVAL)))
                .compose(id -> vertx.deployVerticle(api))
                .compose(id -> vertx.deployVerticle(ingress))
                .map(id -> new RunningNode(config.nodeId(), instanceId, vertx, api.port(), ingress.port(), cluster))
                .recover(err -> vertx.close().transform(v -> Future.failedFuture(err)));
    }

    public record RunningNode(String nodeId, String instanceId, Vertx vertx, int httpPort, int ingressPort, IgniteCluster cluster) {

        /** Stops Vert.x (which stops the node's scripts, D-015), then this node's Ignite. */
        public Future<Void> stop() {
            return vertx.close().andThen(ar -> {
                if (cluster != null) {
                    cluster.stop();
                }
            });
        }
    }
}
