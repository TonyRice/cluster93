package automately.cluster.ignite.store;

import automately.cluster.ignite.IgniteCluster;
import automately.cluster.ignite.IgniteSettings;
import automately.core.host.Domain;
import automately.core.job.Job;
import automately.core.job.OrphanPolicy;
import automately.core.service.RestartPolicy;
import automately.core.service.ServiceDefinition;
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
        Ignition.stopAll(true);
    }

    private IgniteSettings settings(String node, int clusterSize) {
        return new IgniteSettings(node, data.resolve(node), 23500, List.of("127.0.0.1"), 1, 64, Duration.ofSeconds(1), clusterSize);
    }

    /** Ignite nodes join each other, so start them in parallel the way a real cluster boots. */
    private List<IgniteCluster> startAll(String... nodes) {
        List<CompletableFuture<IgniteCluster>> starting = new ArrayList<>();
        for (String node : nodes) {
            starting.add(CompletableFuture.supplyAsync(() -> IgniteCluster.start(settings(node, nodes.length))));
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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
