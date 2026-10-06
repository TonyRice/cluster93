package automately.core.scheduler;

import automately.core.job.Job;
import automately.core.job.JobStatus;
import automately.core.job.JobStore;
import automately.core.runtime.JobContext;
import automately.core.service.ServiceStore;
import io.vertx.core.Future;

import java.util.List;

/** Exposes {@link LocalCluster} to tests outside this package. */
public final class LocalClusterAccess implements AutoCloseable {

    private final LocalCluster cluster = new LocalCluster();

    public void start(String nodeId, String instanceId, int capacity) throws Exception {
        cluster.start(nodeId, instanceId, capacity);
    }

    public void kill(String instanceId) throws Exception {
        cluster.kill(instanceId);
    }

    public ServiceStore services() {
        return cluster.services;
    }

    public JobStore jobs() {
        return cluster.jobs;
    }

    public JobScheduler scheduler() {
        return cluster.scheduler;
    }

    public Job job(String id) throws Exception {
        return cluster.job(id);
    }

    public Job awaitRunning(String id) throws Exception {
        return cluster.awaitStatus(id, JobStatus.RUNNING);
    }

    public Job awaitTerminal(String id) throws Exception {
        return cluster.awaitTerminal(id);
    }

    public Runtime runtime() {
        return new Runtime();
    }

    public static <T> T await(Future<T> future) throws Exception {
        return LocalCluster.await(future);
    }

    /** What the fake runtime saw. */
    public final class Runtime {

        public JobContext context(String jobId, int attempt) {
            return cluster.runtime.contexts.get(jobId + "#" + attempt);
        }

        public List<String> stops() {
            return cluster.runtime.stops;
        }
    }

    @Override
    public void close() throws Exception {
        cluster.close();
    }
}
