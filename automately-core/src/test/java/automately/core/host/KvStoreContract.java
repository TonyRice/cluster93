package automately.core.host;

import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour every {@link KvStore} must have; each implementation's test extends this. */
public abstract class KvStoreContract {

    /** Set by the implementation's test before each test. */
    protected KvStore kv;



    @Test
    public void storesJsonValues() throws Exception {
        await(kv.set("t", "default", "order:1", new JsonObject().put("total", 9.5), null));

        assertThat(await(kv.get("t", "default", "order:1"))).isEqualTo(new JsonObject().put("total", 9.5));
        assertThat(await(kv.get("t", "default", "missing"))).isNull();
        assertThat(await(kv.delete("t", "default", "order:1"))).isTrue();
        assertThat(await(kv.delete("t", "default", "order:1"))).isFalse();
    }

    @Test
    public void putIfAbsentKeepsTheFirstValueAndRacersAgree() throws Exception {
        java.util.List<io.vertx.core.Future<Object>> racing = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            racing.add(kv.putIfAbsent("t", "secrets", "s", "value-" + i));
        }
        await(io.vertx.core.Future.all(racing));
        Object winner = await(kv.get("t", "secrets", "s"));
        racing.forEach(f -> assertThat(f.result()).isEqualTo(winner));

        await(kv.set("t", "secrets", "expiring", "old", 50L));
        Thread.sleep(100);
        assertThat(await(kv.putIfAbsent("t", "secrets", "expiring", "new"))).as("an expired value doesn't count").isEqualTo("new");
    }

    @Test
    public void tenantsAndNamespacesAreSeparate() throws Exception {
        await(kv.set("acme", "default", "k", "acme", null));
        await(kv.set("acme", "other", "k", "acme-other", null));
        await(kv.set("globex", "default", "k", "globex", null));

        assertThat(await(kv.get("acme", "default", "k"))).isEqualTo("acme");
        assertThat(await(kv.get("acme", "other", "k"))).isEqualTo("acme-other");
        assertThat(await(kv.get("globex", "default", "k"))).isEqualTo("globex");
        assertThat(await(kv.list("acme", "default", "", 10, null)).getJsonArray("keys")).isEqualTo(new JsonArray().add("k"));
    }

    @Test
    public void expiryIsExact() throws Exception {
        await(kv.set("t", "default", "session", "abc", 150L));

        assertThat(await(kv.get("t", "default", "session"))).isEqualTo("abc");
        Thread.sleep(200);
        assertThat(await(kv.get("t", "default", "session"))).isNull();
        assertThat(await(kv.list("t", "default", "", 10, null)).getJsonArray("keys")).isEmpty();
    }

    @Test
    public void listsInOrderWithPrefixAndCursor() throws Exception {
        for (String key : List.of("user:3", "user:1", "order:1", "user:2")) {
            await(kv.set("t", "default", key, 1, null));
        }

        JsonObject first = await(kv.list("t", "default", "user:", 2, null));
        JsonObject second = await(kv.list("t", "default", "user:", 2, first.getString("cursor")));

        assertThat(first.getJsonArray("keys")).isEqualTo(new JsonArray().add("user:1").add("user:2"));
        assertThat(first.getString("cursor")).isEqualTo("user:2");
        assertThat(second.getJsonArray("keys")).isEqualTo(new JsonArray().add("user:3"));
        assertThat(second.getString("cursor")).isNull();
    }

    @Test
    public void concurrentIncrementsLoseNothing() throws Exception {
        List<Future<Long>> increments = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            increments.add(kv.increment("t", "default", "counter", 1));
        }
        await(Future.all(increments));

        // Stores return JSON numbers; the boxed Java type (Integer or Long) isn't part of the contract.
        assertThat(((Number) await(kv.get("t", "default", "counter"))).longValue()).isEqualTo(100L);
    }

    @Test
    public void incrementRefusesNonNumbers() throws Exception {
        await(kv.set("t", "default", "name", "tony", null));

        assertThatThrownBy(() -> await(kv.increment("t", "default", "name", 1)))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(HostException.class).hasMessageContaining("numeric");
    }

    @Test
    public void usageCountsLiveEntriesPerTenant() throws Exception {
        JsonObject order = new JsonObject().put("total", 9.5);
        await(kv.set("usage-a", "default", "order:1", order, null));
        await(kv.set("usage-a", "other", "k", "x", null));
        await(kv.increment("usage-a", "default", "visits", 41));
        await(kv.increment("usage-a", "default", "visits", 1));
        await(kv.set("usage-a", "default", "gone", "soon", 1L));
        await(kv.set("usage-b", "default", "order:1", order, null));
        Thread.sleep(20);

        long expected = KvStore.entrySize("default", "order:1", order) + KvStore.entrySize("other", "k", "x")
                + KvStore.entrySize("default", "visits", 42L);
        assertThat(await(kv.usage("usage-a"))).isEqualTo(expected);
        assertThat(await(kv.usage("usage-b"))).isEqualTo(KvStore.entrySize("default", "order:1", order));
        assertThat(await(kv.usage("nobody"))).isZero();

        await(kv.delete("usage-a", "other", "k"));
        assertThat(await(kv.usage("usage-a"))).isEqualTo(expected - KvStore.entrySize("other", "k", "x"));
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
