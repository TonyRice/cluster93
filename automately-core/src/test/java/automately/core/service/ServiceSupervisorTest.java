package automately.core.service;

import automately.core.job.Job;
import automately.core.job.JobStatus;
import automately.core.runtime.JobContext;
import automately.core.scheduler.LocalClusterAccess;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** The supervisor against two in-memory nodes and the fake runtime (docs/design/services.md). */
class ServiceSupervisorTest {

    private static final String TENANT = "tenant-a";

    private LocalClusterAccess cluster;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new LocalClusterAccess();
        cluster.start("node-a", "a-1", 2);
        cluster.start("node-b", "b-1", 2);
    }

    @AfterEach
    void tearDown() throws Exception {
        cluster.close();
    }

    private ServiceDefinition define(String name, String script, RestartPolicy restart) throws Exception {
        ServiceDefinition service = ServiceDefinition.create(TENANT, name, script, new JsonObject().put("everyMs", 5), restart, true,
                System.currentTimeMillis());
        assertThat(LocalClusterAccess.await(cluster.services().create(service))).isTrue();
        return service;
    }

    private ServiceDefinition service(String name) throws Exception {
        return LocalClusterAccess.await(cluster.services().get(TENANT, name));
    }

    private ServiceDefinition awaitService(String name, Predicate<ServiceDefinition> condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        ServiceDefinition last = service(name);
        while (System.currentTimeMillis() < deadline) {
            last = service(name);
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Service " + name + " never became " + what + "; last seen " + last.toJson().encodePrettily());
    }

    private Job awaitRunningRun(String name, int run) throws Exception {
        ServiceDefinition service = awaitService(name, s -> s.runs() >= run && s.currentJobId() != null, "run " + run);
        return cluster.awaitRunning(service.currentJobId());
    }

    private List<Job> runsOf(String name) throws Exception {
        return LocalClusterAccess.await(cluster.jobs().list(TENANT, 1000)).stream()
                .filter(job -> job.service() != null && job.service().name().equals(name))
                .sorted(Comparator.comparingInt(job -> job.service().run()))
                .toList();
    }

    @Test
    void startsARunWithTheServiceContext() throws Exception {
        define("poller", "hold", RestartPolicy.ALWAYS);

        Job run = awaitRunningRun("poller", 1);

        assertThat(run.service().name()).isEqualTo("poller");
        assertThat(run.service().run()).isEqualTo(1);
        assertThat(run.timeoutMs()).as("services have no timeout").isZero();
        JobContext context = cluster.runtime().context(run.id(), 1);
        assertThat(context.service().getString("name")).isEqualTo("poller");
        assertThat(context.service().getJsonObject("config")).isEqualTo(new JsonObject().put("everyMs", 5));
        assertThat(context.service().getValue("previousRun")).isNull();
        assertThat(service("poller").status(run, System.currentTimeMillis())).isEqualTo(ServiceStatus.RUNNING);
    }

    @Test
    void restartsAfterFailuresWithGrowingBackoff() throws Exception {
        define("flaky", "fail", RestartPolicy.ALWAYS);

        ServiceDefinition after = awaitService("flaky", s -> s.runs() >= 4 && s.currentJobId() == null, "4 runs ended");

        assertThat(after.consecutiveFailures()).isGreaterThanOrEqualTo(4);
        assertThat(after.lastExit().getString("status")).isEqualTo("FAILED");
        List<Job> runs = runsOf("flaky");
        // Backoff 100, 200, 400 ms: gaps between run starts grow (allowing for the 100 ms sweep interval).
        long gap1 = runs.get(1).createdAt() - runs.get(0).createdAt();
        long gap3 = runs.get(3).createdAt() - runs.get(2).createdAt();
        assertThat(gap3).isGreaterThan(gap1);
        assertThat(gap3).isGreaterThanOrEqualTo(350);
    }

    @Test
    void onFailureCompletesWhenARunSucceeds() throws Exception {
        define("one-shot", "hello", RestartPolicy.ON_FAILURE);

        ServiceDefinition done = awaitService("one-shot", ServiceDefinition::completed, "completed");
        Thread.sleep(400);

        assertThat(service("one-shot").runs()).isEqualTo(1);
        assertThat(done.status(null, System.currentTimeMillis())).isEqualTo(ServiceStatus.COMPLETED);
    }

    @Test
    void alwaysRestartsEvenAfterSuccess() throws Exception {
        define("again", "hello", RestartPolicy.ALWAYS);

        ServiceDefinition after = awaitService("again", s -> s.runs() >= 3, "3 runs");

        assertThat(after.consecutiveFailures()).isZero();
    }

    @Test
    void stoppingCancelsTheRunAndStartingRunsAgain() throws Exception {
        define("worker", "hold", RestartPolicy.ALWAYS);
        Job first = awaitRunningRun("worker", 1);

        LocalClusterAccess.await(cluster.services().update(TENANT, "worker",
                s -> java.util.Optional.of(s.withEnabled(false, System.currentTimeMillis()))));
        ServiceDefinition stopped = awaitService("worker", s -> s.currentJobId() == null, "stopped");

        assertThat(cluster.awaitTerminal(first.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cluster.runtime().stops()).contains(first.id() + "#1: service worker stopped");
        assertThat(stopped.status(null, System.currentTimeMillis())).isEqualTo(ServiceStatus.STOPPED);
        assertThat(stopped.consecutiveFailures()).as("a stop is not a failure").isZero();

        LocalClusterAccess.await(cluster.services().update(TENANT, "worker",
                s -> java.util.Optional.of(s.withEnabled(true, System.currentTimeMillis()))));
        Job second = awaitRunningRun("worker", 2);
        assertThat(second.service().previousJobId()).isEqualTo(first.id());
    }

    @Test
    void anUpdateRollsTheRunningInstanceToTheNewRevision() throws Exception {
        define("roller", "hold", RestartPolicy.ALWAYS);
        Job first = awaitRunningRun("roller", 1);

        LocalClusterAccess.await(cluster.services().update(TENANT, "roller", s -> java.util.Optional.of(
                s.redefined("hold", new JsonObject().put("everyMs", 50), RestartPolicy.ALWAYS, System.currentTimeMillis()))));
        Job second = awaitRunningRun("roller", 2);

        assertThat(second.service().revision()).isEqualTo(2);
        assertThat(second.input()).isEqualTo(new JsonObject().put("everyMs", 50));
        assertThat(cluster.job(first.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cluster.runtime().stops()).contains(first.id() + "#1: service roller updated to revision 2");
        assertThat(service("roller").consecutiveFailures()).isZero();
    }

    @Test
    void neverRunsTwoInstancesAtOnce() throws Exception {
        define("single", "hold", RestartPolicy.ALWAYS);
        awaitRunningRun("single", 1);

        long deadline = System.currentTimeMillis() + 2_000;
        int restarts = 0;
        while (System.currentTimeMillis() < deadline) {
            long active = runsOf("single").stream().filter(job -> !job.status().isTerminal()).count();
            assertThat(active).as("active runs").isLessThanOrEqualTo(1);
            ServiceDefinition current = service("single");
            if (current.currentJobId() != null && restarts < 5) {
                LocalClusterAccess.await(cluster.scheduler().cancel(current.currentJobId(), "restart " + restarts++));
            }
            Thread.sleep(25);
        }
        assertThat(service("single").runs()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aRunLostWithItsNodeIsRestartedElsewhere() throws Exception {
        cluster.start("node-c", "c-1", 2);
        define("survivor", "hold", RestartPolicy.ALWAYS);
        Job first = awaitRunningRun("survivor", 1);
        // Whichever node runs it dies, even if it's the supervisor leader: leadership moves on (D-012, D-014).
        String victim = first.nodeInstance();

        cluster.kill(victim);
        Job second = awaitRunningRun("survivor", 2);

        assertThat(second.nodeInstance()).isNotEqualTo(victim);
        assertThat(cluster.job(first.id()).status()).isEqualTo(JobStatus.ORPHANED);
        ServiceDefinition service = service("survivor");
        assertThat(service.lastExit().getString("status")).isEqualTo("ORPHANED");
        assertThat(service.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void eachRunStartsFromThePreviousRunsCheckpoint() throws Exception {
        define("counter", "checkpoint", RestartPolicy.ALWAYS);

        ServiceDefinition after = awaitService("counter", s -> s.runs() >= 4 && s.currentJobId() == null, "4 runs ended");

        List<Job> runs = runsOf("counter");
        for (int i = 1; i < 4; i++) {
            JobContext context = cluster.runtime().context(runs.get(i).id(), 1);
            assertThat(((JsonObject) context.carried().checkpoint()).getInteger("count")).as("run " + (i + 1)).isEqualTo(i);
            assertThat(context.service().getJsonObject("previousRun").getString("jobId")).isEqualTo(runs.get(i - 1).id());
            assertThat(context.service().getJsonObject("previousRun").getString("status")).isEqualTo("FAILED");
        }
        assertThat(after.runs()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void aClaimedRunWhoseJobNeverAppearedIsGivenUp() throws Exception {
        long now = System.currentTimeMillis();
        ServiceDefinition ghost = new ServiceDefinition(TENANT, "ghost", "hold", new JsonObject(), RestartPolicy.ALWAYS, true, false, 1,
                "job-that-was-never-saved", 1, now - 60_000, 1, 0, 0, null, now, now, false, null, null, null);
        LocalClusterAccess.await(cluster.services().create(ghost));

        Job run = awaitRunningRun("ghost", 2);

        assertThat(service("ghost").lastExit().getString("jobId")).isEqualTo("job-that-was-never-saved");
        assertThat(run.service().run()).isEqualTo(2);
    }
}
