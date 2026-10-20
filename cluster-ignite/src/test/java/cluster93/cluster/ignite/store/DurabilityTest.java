package cluster93.cluster.ignite.store;

import cluster93.cluster.ignite.IgniteCluster;
import cluster93.cluster.ignite.IgniteSettings;
import cluster93.core.host.Domain;
import cluster93.core.job.Job;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.service.RestartPolicy;
import cluster93.core.service.ServiceDefinition;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The point of D-030: everything survives stopping the whole cluster, and a node can be lost. */
class DurabilityTest {

    @TempDir
    Path data;

    @AfterEach
    void stopEverything() {
        TestIgnite.stopAllExceptShared();
    }

    /** Each node binds exactly its base and the port 100 above it (D-185), so nodes sharing a host need one base each. */
    private static final int BASE = 23500;
    /** More than the communication offset (100), so one node's communication port is never another's discovery port. */
    private static final int STEP = 200;

    private IgniteSettings settings(String node, int clusterSize, int port, List<String> members) {
        return new IgniteSettings(node, data.resolve(node), port, members, 1, 128, Duration.ofSeconds(1), clusterSize);
    }

    /** Ignite nodes join each other, so start them in parallel the way a real cluster boots. */
    private List<IgniteCluster> startAll(String... nodes) {
        // Every node is told every base: the node that binds 23700 is found at 23700, never at 23500 plus whatever
        // Ignite would have picked if the port were taken.
        List<String> members = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) {
            members.add("127.0.0.1:" + (BASE + i * STEP));
        }
        List<CompletableFuture<IgniteCluster>> starting = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) {
            String node = nodes[i];
            int port = BASE + i * STEP;
            starting.add(CompletableFuture.supplyAsync(() -> IgniteCluster.start(settings(node, nodes.length, port, members))));
        }
        List<IgniteCluster> started = starting.stream().map(CompletableFuture::join).toList();
        IgniteStores.createTables(started.getFirst());
        return started;
    }

    @Test
    void everythingSurvivesAFullClusterRestart() throws Exception {
        List<IgniteCluster> first = startAll("node-a", "node-b");
        Vertx vertx = Vertx.vertx();
        IgniteStores stores = IgniteStores.open(vertx, first.getFirst());
        Job job = Job.queued("job-1", "acme", "export default () => 1", new JsonObject(), 1000, OrphanPolicy.RESUME, 3, 10);
        await(stores.jobs.save(job));
        await(stores.jobs.update("job-1", j -> j.started(1, "node-a", "inst-a", 20)));
        await(stores.state.completeStep("job-1", 1, "charge", "ch_1", 30));
        await(stores.services.create(ServiceDefinition.create("acme", "web", "script", new JsonObject(), RestartPolicy.ALWAYS, true, 40)));
        await(stores.domains.claim(new Domain("shop.example.com", "acme", "web", "tok", false, 50, null), 50));
        await(stores.kv.set("acme", "default", "greeting", "hello", null));
        for (int i = 0; i < 200; i++) {
            await(stores.kv.increment("acme", "counters", "visits", 1));
        }
        await(vertx.close());
        first.forEach(IgniteCluster::stop);

        List<IgniteCluster> second = startAll("node-a", "node-b");
        Vertx again = Vertx.vertx();
        IgniteStores restored = IgniteStores.open(again, second.get(1));

        assertThat(await(restored.jobs.get("job-1")).nodeInstance()).isEqualTo("inst-a");
        assertThat(await(restored.jobs.active())).extracting(Job::id).containsExactly("job-1");
        assertThat(await(restored.state.load("job-1", 1)).step("charge")).isPresent();
        assertThat(await(restored.services.get("acme", "web"))).isNotNull();
        assertThat(await(restored.domains.forService("acme", "web"))).extracting(Domain::domain).containsExactly("shop.example.com");
        assertThat(await(restored.kv.get("acme", "default", "greeting"))).isEqualTo("hello");
        assertThat(((Number) await(restored.kv.get("acme", "counters", "visits"))).longValue()).isEqualTo(200L);
        await(again.close());
        second.forEach(IgniteCluster::stop);
    }

    @Test
    void losingOneNodeLosesNoDataWithOneBackup() throws Exception {
        List<IgniteCluster> cluster = startAll("node-a", "node-b", "node-c");
        Vertx vertx = Vertx.vertx();
        IgniteStores stores = IgniteStores.open(vertx, cluster.getFirst());
        for (int i = 0; i < 500; i++) {
            await(stores.kv.set("acme", "default", "k" + i, i, null));
        }

        cluster.get(2).stop();
        IgniteStores survivor = IgniteStores.open(vertx, cluster.get(1));

        for (int i = 0; i < 500; i++) {
            assertThat(await(survivor.kv.get("acme", "default", "k" + i))).as("k" + i).isEqualTo(i);
        }
        await(vertx.close());
        cluster.get(1).stop();
        cluster.getFirst().stop();
    }

    @Test
    void aMessageInFlightOnALostNodeIsDeliveredElsewhere() throws Exception {
        List<IgniteCluster> cluster = startAll("node-a", "node-b", "node-c");
        Vertx onC = Vertx.vertx();
        Vertx onB = Vertx.vertx();
        cluster93.core.queue.QueueService queuesOnC = new cluster93.core.queue.QueueService(onC, IgniteStores.open(onC, cluster.get(2)).queues,
                cluster93.core.storage.TenantQuota.unlimited(), cluster93.core.files.StorageCapacity.UNLIMITED, 500);
        cluster93.core.queue.QueueService queuesOnB = new cluster93.core.queue.QueueService(onB, IgniteStores.open(onB, cluster.get(1)).queues,
                cluster93.core.storage.TenantQuota.unlimited(), cluster93.core.files.StorageCapacity.UNLIMITED, 500);
        for (int i = 0; i < 20; i++) {
            await(queuesOnB.push("acme", "emails", i, null, null, null));
        }

        // A consumer on node C takes a message, and node C dies before it's done.
        cluster93.core.queue.QueueMessage lost = await(queuesOnC.claim("acme", "emails", "consumer-on-c")).orElseThrow();
        await(onC.close());
        cluster.get(2).stop();

        // Node B's consumer gets every message, the lost one included once its lease runs out.
        java.util.Map<Object, Integer> delivered = new java.util.HashMap<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (delivered.size() < 20 && System.currentTimeMillis() < deadline) {
            java.util.Optional<cluster93.core.queue.QueueMessage> claimed = await(queuesOnB.claim("acme", "emails", "consumer-on-b"));
            if (claimed.isEmpty()) {
                Thread.sleep(100);
                continue;
            }
            delivered.put(claimed.get().body(), claimed.get().attempt());
            assertThat(await(queuesOnB.ack(claimed.get()))).isTrue();
        }

        assertThat(delivered).hasSize(20);
        assertThat(delivered.get(lost.body())).as("delivered again after the lease ran out").isEqualTo(2);
        assertThat(await(queuesOnB.stats("acme", "emails"))).isEqualTo(new JsonObject().put("ready", 0L).put("inFlight", 0L).put("dead", 0L));
        await(onB.close());
        cluster.get(1).stop();
        cluster.getFirst().stop();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
