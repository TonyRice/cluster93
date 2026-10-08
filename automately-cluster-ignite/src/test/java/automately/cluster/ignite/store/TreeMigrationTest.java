package automately.cluster.ignite.store;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Upgrading a data directory (D-076): tables gain new columns, and files from before directories join the tree. */
class TreeMigrationTest {

    @Test
    void anExistingTableGainsNewColumnsAndTheirIndex() throws Exception {
        var ignite = TestIgnite.cluster().ignite();
        Table.Definition before = new Table.Definition("MIGRATE_T", Table.columns("a", String.class), List.of(Table.index("a")));
        Table.create(ignite, before, 0);
        Table old = new Table(TestIgnite.vertx(), ignite, before, json -> Map.of("a", json.getString("a")));
        await(old.put("one", new JsonObject().put("a", "x")));

        Table.Definition after = new Table.Definition("MIGRATE_T", Table.columns("a", String.class, "b", String.class),
                List.of(Table.index("a"), Table.index("b")));
        Table.create(ignite, after, 0);
        Table now = new Table(TestIgnite.vertx(), ignite, after, json -> {
            java.util.HashMap<String, Object> columns = new java.util.HashMap<>();
            columns.put("a", json.getString("a"));
            columns.put("b", json.getString("b"));
            return columns;
        });
        await(now.put("two", new JsonObject().put("a", "y").put("b", "z")));

        assertThat(await(now.queryJson("SELECT json FROM MIGRATE_T WHERE b IS NULL"))).extracting(j -> j.getString("a")).containsExactly("x");
        assertThat(await(now.queryJson("SELECT json FROM MIGRATE_T WHERE b = ?", "z"))).extracting(j -> j.getString("a")).containsExactly("y");
        // Running it again is a no-op.
        Table.create(ignite, after, 0);
    }

    @Test
    void filesFromBeforeDirectoriesAreBackfilledIntoTheTree() throws Exception {
        IgniteStores stores = TestIgnite.stores();
        IgniteFileStore files = stores.files(stores.chunks(automately.core.files.StorageCapacity.UNLIMITED));
        // A row as the old version wrote it: no parent or name.
        Table.Definition old = new Table.Definition("FILES", Table.columns("tenant", String.class, "path", String.class, "size", Long.class,
                "backend", String.class), List.of());
        Table legacy = new Table(TestIgnite.vertx(), TestIgnite.cluster().ignite(), old, json -> Map.of("tenant", json.getString("tenant"),
                "path", json.getString("path"), "size", json.getLong("size"), "backend", "cluster"));
        await(legacy.put("acme\u001fold/site/page.html", new JsonObject().put("tenant", "acme").put("path", "old/site/page.html")
                .put("fileId", "f1").put("size", 3L).put("contentType", "text/html").put("etag", "\"e\"").put("backend", "cluster")
                .put("createdAt", 1L)));
        assertThat(await(files.listDir("acme", "", 10, null)).getJsonArray("entries")).as("not in the tree yet").isEmpty();

        assertThat(await(files.backfillTree())).isEqualTo(1);

        assertThat(await(files.listDir("acme", "", 10, null)).getJsonArray("entries").getJsonObject(0).getString("name")).isEqualTo("old");
        assertThat(await(files.listDir("acme", "old/site", 10, null)).getJsonArray("entries").getJsonObject(0).getString("name"))
                .isEqualTo("page.html");
        assertThat(await(files.backfillTree())).as("once is enough").isZero();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
