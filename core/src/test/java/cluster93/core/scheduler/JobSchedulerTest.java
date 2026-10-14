package cluster93.core.scheduler;

import cluster93.core.job.Job;
import cluster93.core.job.JobStatus;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.node.NodeInfo;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static cluster93.core.scheduler.LocalCluster.await;
import static org.assertj.core.api.Assertions.assertThat;

/** Normal scheduling: two nodes with capacity 1 each. */
class JobSchedulerTest {

    private LocalCluster cluster;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new LocalCluster();
        cluster.start("node-a", "a-1", 1);
        cluster.start("node-b", "b-1", 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        cluster.close();
    }

    @Test
    void runsAJobToCompletion() throws Exception {
        Job done = cluster.awaitTerminal(cluster.submit("hello", OrphanPolicy.FAIL, 1).id());

        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.output()).isEqualTo("HELLO");
        assertThat(done.node()).isIn("node-a", "node-b");
        assertThat(done.logs()).containsExactly("running hello attempt 1");
        assertThat(done.attempts()).singleElement().satisfies(a -> assertThat(a.outcome()).isEqualTo(JobStatus.SUCCEEDED));
    }

    @Test
    void placesTheNextJobOnTheLeastLoadedNode() throws Exception {
        Job first = cluster.submit("hold", OrphanPolicy.FAIL, 1);
        cluster.awaitStatus(first.id(), JobStatus.RUNNING);
        Job second = cluster.submit("hold", OrphanPolicy.FAIL, 1);
        cluster.awaitStatus(second.id(), JobStatus.RUNNING);

        assertThat(List.of(cluster.job(first.id()).node(), cluster.job(second.id()).node()))
                .containsExactlyInAnyOrder("node-a", "node-b");
    }

    @Test
    void failsTheJobWhenEveryNodeIsFull() throws Exception {
        cluster.awaitStatus(cluster.submit("hold", OrphanPolicy.FAIL, 1).id(), JobStatus.RUNNING);
        cluster.awaitStatus(cluster.submit("hold", OrphanPolicy.FAIL, 1).id(), JobStatus.RUNNING);

        Job rejected = cluster.submit("hello", OrphanPolicy.FAIL, 1);

        assertThat(rejected.status()).isEqualTo(JobStatus.FAILED);
        assertThat(rejected.error()).startsWith("No node has free capacity");
    }

    @Test
    void aBrokenRuntimeFailsTheJobAndFreesTheSlot() throws Exception {
        Job crashed = cluster.awaitTerminal(cluster.submit("crash", OrphanPolicy.FAIL, 1).id());

        assertThat(crashed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(crashed.error()).contains("engine exploded");
        cluster.awaitStatus(cluster.submit("hold", OrphanPolicy.FAIL, 1).id(), JobStatus.RUNNING);
        cluster.awaitStatus(cluster.submit("hold", OrphanPolicy.FAIL, 1).id(), JobStatus.RUNNING);
    }

    @Test
    void publishesLogLinesAndTheFinalStatus() throws Exception {
        List<JsonObject> events = new CopyOnWriteArrayList<>();
        Job job = cluster.submit("hold", OrphanPolicy.FAIL, 1);
        cluster.awaitStatus(job.id(), JobStatus.RUNNING);
        await(cluster.vertx.eventBus().<JsonObject>consumer(Addresses.jobEvents(job.id()), msg -> events.add(msg.body())).completion());

        cluster.runtime.releaseAll();
        cluster.awaitTerminal(job.id());
        long deadline = System.currentTimeMillis() + 2_000;
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(events).last().satisfies(end -> {
            assertThat(end.getString("type")).isEqualTo("end");
            assertThat(end.getString("status")).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void candidatesAreSortedByLoadAndSkipFullNodes() {
        List<NodeInfo> live = List.of(
                new NodeInfo("busy", "1", 4, 3, 0),
                new NodeInfo("full", "2", 2, 2, 0),
                new NodeInfo("idle", "3", 4, 0, 0),
                new NodeInfo("half", "4", 4, 2, 0));

        assertThat(JobScheduler.candidates(live)).extracting(NodeInfo::id).containsExactly("idle", "half", "busy");
    }
}
