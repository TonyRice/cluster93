package automately.server.cluster;

import automately.server.ServerConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job transitions rely on {@code AsyncMap.replaceIfPresent} acting as compare-and-set on the clustered
 * (Hazelcast) map with {@link JsonObject} values (D-011). This pins that behaviour down.
 */
class ClusteredCompareAndSetTest {

    private static Vertx a;
    private static Vertx b;

    @BeforeAll
    static void start() throws Exception {
        String cluster = "cas-test-" + UUID.randomUUID();
        a = await(Clustering.vertx(config(cluster)));
        b = await(Clustering.vertx(config(cluster)));
    }

    @AfterAll
    static void stop() throws Exception {
        await(b.close());
        await(a.close());
    }

    private static ServerConfig config(String cluster) {
        return ServerConfig.fromEnv(Map.of(
                "AUTOMATELY_CLUSTERED", "true",
                "AUTOMATELY_CLUSTER_NAME", cluster,
                "AUTOMATELY_CLUSTER_PORT", "16701",
                "AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1"));
    }

    @Test
    void replaceSucceedsAgainstTheValueReadFromAnotherNode() throws Exception {
        AsyncMap<String, JsonObject> onA = await(a.sharedData().<String, JsonObject>getAsyncMap("cas"));
        AsyncMap<String, JsonObject> onB = await(b.sharedData().<String, JsonObject>getAsyncMap("cas"));
        await(onA.put("job", new JsonObject().put("status", "RUNNING").put("attempt", 1)));

        JsonObject seenByB = await(onB.get("job"));
        boolean replaced = await(onB.replaceIfPresent("job", seenByB, seenByB.copy().put("status", "SUCCEEDED")));

        assertThat(replaced).isTrue();
        assertThat(await(onA.get("job")).getString("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void replaceFailsWhenSomeoneElseChangedTheValueFirst() throws Exception {
        AsyncMap<String, JsonObject> onA = await(a.sharedData().<String, JsonObject>getAsyncMap("cas"));
        AsyncMap<String, JsonObject> onB = await(b.sharedData().<String, JsonObject>getAsyncMap("cas"));
        await(onA.put("race", new JsonObject().put("attempt", 1)));
        JsonObject stale = await(onB.get("race"));

        assertThat(await(onA.replaceIfPresent("race", await(onA.get("race")), new JsonObject().put("attempt", 2)))).isTrue();
        assertThat(await(onB.replaceIfPresent("race", stale, new JsonObject().put("attempt", 99)))).isFalse();
        assertThat(await(onB.get("race")).getInteger("attempt")).isEqualTo(2);
    }

    @Test
    void replaceFailsForAMissingKey() throws Exception {
        AsyncMap<String, JsonObject> onA = await(a.sharedData().<String, JsonObject>getAsyncMap("cas"));

        assertThat(await(onA.replaceIfPresent("absent", new JsonObject(), new JsonObject().put("x", 1)))).isFalse();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
