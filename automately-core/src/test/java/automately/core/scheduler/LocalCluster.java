package automately.core.scheduler;

import automately.core.job.Job;
import automately.core.job.JobStatus;
import automately.core.job.JobStore;
import automately.core.job.OrphanPolicy;
import automately.core.job.SharedDataJobStore;
import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import automately.core.node.SharedDataNodeRegistry;
import automately.core.service.ServiceStore;
import automately.core.service.ServiceSupervisor;
import automately.core.service.SharedDataServiceStore;
import automately.core.state.JobStateStore;
import automately.core.state.SharedDataJobStateStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Several nodes, each with its own reaper, sharing one local Vert.x instance and its in-memory maps. */
final class LocalCluster implements AutoCloseable {

    static final Duration HEARTBEAT = Duration.ofMillis(100);
    static final Duration GRACE = Duration.ofMillis(300);
    static final Duration REQUEUE_AFTER = Duration.ofMillis(500);
    static final Duration STOP_GRACE = Duration.ofMillis(300);
    static final ServiceSupervisor.Timing SERVICE_TIMING =
            new ServiceSupervisor.Timing(Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMillis(800));

    final Vertx vertx = Vertx.vertx();
    final JobStore jobs = new SharedDataJobStore(vertx);
    final JobStateStore state = new SharedDataJobStateStore(vertx);
    final NodeRegistry nodes = new SharedDataNodeRegistry(vertx, HEARTBEAT.multipliedBy(3));
    final FakeRuntime runtime = new FakeRuntime(vertx);
    final JobScheduler scheduler = new JobScheduler(vertx, jobs, nodes);
    final ServiceStore services = new SharedDataServiceStore(vertx);

    /** Instances whose heartbeats are being dropped, as if cut off from the cluster. */
    private final Set<String> partitioned = ConcurrentHashMap.newKeySet();

    /** Deployment ids per instance id: the node verticle and its reaper. */
    private final Map<String, String[]> deployments = new HashMap<>();

    /** Starts a node. Instance ids sort in start order, so the first node started is the reaper leader. */
    String start(String nodeId, String instanceId, int capacity) throws Exception {
        String node = await(vertx.deployVerticle(new NodeVerticle(nodeId, instanceId, capacity, jobs, state, partitionable(), runtime,
                HEARTBEAT, STOP_GRACE, automately.core.host.HostServicesFactory.NONE)));
        String reaper = await(vertx.deployVerticle(new OrphanReaper(instanceId, jobs, nodes, scheduler, HEARTBEAT, GRACE, REQUEUE_AFTER)));
        String supervisor = await(vertx.deployVerticle(new ServiceSupervisor(instanceId, services, jobs, nodes, scheduler, HEARTBEAT,
                SERVICE_TIMING)));
        deployments.put(instanceId, new String[]{node, reaper, supervisor});
        return instanceId;
    }

    /**
     * Simulates a network partition: the node keeps running its scripts, but the cluster stops hearing
     * from it. This is how a zombie attempt arises.
     */
    void partition(String instanceId) throws Exception {
        partitioned.add(instanceId);
        await(nodes.remove(instanceId));
    }

    /** The node's view of the registry, which drops its announcements while it is partitioned. */
    private NodeRegistry partitionable() {
        return new NodeRegistry() {
            @Override
            public Future<Void> announce(NodeInfo node) {
                return partitioned.contains(node.instanceId()) ? Future.succeededFuture() : nodes.announce(node);
            }

            @Override
            public Future<Void> remove(String instanceId) {
                return nodes.remove(instanceId);
            }

            @Override
            public Future<List<NodeInfo>> liveNodes() {
                return nodes.liveNodes();
            }
        };
    }

    /** Stops a node and its reaper. The node cancels its scripts and leaves their jobs to the reaper (D-015). */
    void kill(String instanceId) throws Exception {
        for (String deployment : deployments.remove(instanceId)) {
            await(vertx.undeploy(deployment));
        }
    }

    Job submit(String script, OrphanPolicy policy, int maxAttempts) throws Exception {
        return await(scheduler.submit("tenant-a", script, new JsonObject(), Duration.ofSeconds(10), policy, maxAttempts));
    }

    Job job(String id) throws Exception {
        return await(jobs.get(id));
    }

    Job awaitJob(String id, Predicate<Job> condition, String description) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Job job = job(id);
        while (System.currentTimeMillis() < deadline) {
            job = job(id);
            if (condition.test(job)) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Job " + id + " never became " + description + "; last seen " + job.toSummaryJson().encodePrettily());
    }

    Job awaitStatus(String id, JobStatus status) throws Exception {
        return awaitJob(id, job -> job.status() == status, status.name());
    }

    Job awaitRunning(String id, int attempt) throws Exception {
        return awaitJob(id, job -> job.status() == JobStatus.RUNNING && job.attempt() == attempt, "RUNNING attempt " + attempt);
    }

    Job awaitTerminal(String id) throws Exception {
        return awaitJob(id, job -> job.status().isTerminal(), "terminal");
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        runtime.releaseAll();
        await(vertx.close());
    }
}
