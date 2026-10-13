package cluster93.core.identity;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour every {@link Records} must have; each implementation's test extends this. */
public abstract class RecordsContract {

    /** {@code MEMBERS}, indexed by tenant and user; set by the implementation's test before each test. */
    protected Records records;

    @Test
    public void recordsAreStoredOnceAndFoundByKey() throws Exception {
        assertThat(await(records.putIfAbsent("acme/alice", member("acme", "alice")))).isTrue();
        assertThat(await(records.putIfAbsent("acme/alice", member("acme", "other")))).isFalse();
        assertThat(await(records.get("acme/alice")).getString("user")).isEqualTo("alice");
        assertThat(await(records.get("nobody"))).isNull();

        await(records.put("acme/alice", member("acme", "alice").put("roles", "changed")));
        assertThat(await(records.get("acme/alice")).getString("roles")).isEqualTo("changed");
        assertThat(await(records.remove("acme/alice")).getString("user")).isEqualTo("alice");
        assertThat(await(records.remove("acme/alice"))).isNull();
    }

    @Test
    public void indexedFieldsFindRecordsIncludingOnesNamedLikeSqlWords() throws Exception {
        await(records.put("acme/alice", member("acme", "alice")));
        await(records.put("acme/bob", member("acme", "bob")));
        await(records.put("globex/alice", member("globex", "alice")));

        assertThat(await(records.where("tenant", "acme"))).extracting(json -> json.getString("user")).containsExactlyInAnyOrder("alice", "bob");
        assertThat(await(records.where("user", "alice"))).extracting(json -> json.getString("tenant")).containsExactlyInAnyOrder("acme", "globex");
        assertThat(await(records.where("user", "nobody"))).isEmpty();
        assertThat(await(records.all())).hasSize(3);
        assertThatThrownBy(() -> await(records.where("roles", "x"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void updatesAreCompareAndSet() throws Exception {
        await(records.put("acme/alice", member("acme", "alice").put("count", 0)));

        var all = new java.util.ArrayList<Future<Optional<JsonObject>>>();
        for (int i = 0; i < 10; i++) {
            all.add(records.update("acme/alice", json -> Optional.of(json.put("count", json.getInteger("count") + 1))));
        }
        await(Future.all(all));

        assertThat(await(records.get("acme/alice")).getInteger("count")).as("no lost updates").isEqualTo(10);
        assertThat(await(records.update("nobody", json -> Optional.of(json)))).isEmpty();
        assertThat(await(records.update("acme/alice", json -> Optional.empty()))).isEmpty();
        assertThat(await(records.get("acme/alice")).getInteger("count")).isEqualTo(10);
    }

    private static JsonObject member(String tenant, String user) {
        return new JsonObject().put("tenant", tenant).put("user", user);
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
