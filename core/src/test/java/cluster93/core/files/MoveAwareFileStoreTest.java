package cluster93.core.files;

import cluster93.core.identity.Records;
import cluster93.core.identity.SharedDataRecords;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** While a folder operation runs, readers see its finished result and writers wait (D-082). Frozen halfway here. */
class MoveAwareFileStoreTest {

    private Vertx vertx;
    private FileStore raw;
    private Records ops;
    private FileStore seen;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        raw = new SharedDataFileStore(vertx);
        ops = new SharedDataRecords(vertx, FileTree.OPS);
        seen = new MoveAwareFileStore(raw, ops);
        for (String path : List.of("src/a.txt", "src/b.txt", "src/sub/c.txt", "other.txt")) {
            FileWriter writer = await(raw.create("acme", path, "text/plain", Room.fixed(Long.MAX_VALUE)));
            await(writer.write(Buffer.buffer(path)));
            await(writer.finish());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private void running(String op, String from, String to) throws Exception {
        await(ops.put("acme", new JsonObject().put("tenant", "acme").put("op", op).put("from", from).put("to", to)
                .put("holder", "node-a").put("until", System.currentTimeMillis() + 60_000)));
    }

    private List<String> names(String dir) throws Exception {
        return await(seen.listDir("acme", dir, 100, null)).getJsonArray("entries").stream().map(e -> ((JsonObject) e).getString("name")).toList();
    }

    private String read(String path) throws Exception {
        FileInfo file = await(seen.stat("acme", path));
        CompletableFuture<String> done = new CompletableFuture<>();
        Buffer all = Buffer.buffer();
        var stream = seen.open(file, 0, file.size() - 1);
        stream.handler(all::appendBuffer, () -> done.complete(all.toString()), done::completeExceptionally);
        stream.resume();
        return done.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aFolderHalfwayThroughAMoveLooksMovedAlready() throws Exception {
        running("move", "src", "dst");
        // The tree operation has made the target folders and moved one file so far.
        await(raw.mkdir("acme", "dst/sub", true));
        await(raw.move("acme", "src/a.txt", "dst/a.txt", false));

        assertThat(names("")).containsExactly("dst", "other.txt");
        assertThat(names("dst")).containsExactly("a.txt", "b.txt", "sub");
        assertThat(names("dst/sub")).containsExactly("c.txt");
        assertThat(await(seen.stat("acme", "src/b.txt"))).isNull();
        assertThat(await(seen.statEntry("acme", "src"))).isNull();
        assertThat(await(seen.stat("acme", "dst/b.txt")).path()).isEqualTo("dst/b.txt");
        assertThat(read("dst/b.txt")).as("not moved yet, read where it is").isEqualTo("src/b.txt");
        assertThat(read("dst/a.txt")).isEqualTo("src/a.txt");
        assertThat(code(() -> await(seen.listDir("acme", "src", 10, null)))).isEqualTo("not_found");

        // Writes touching either folder wait; others don't.
        assertThat(code(() -> await(seen.create("acme", "dst/new.txt", null, Room.fixed(10))))).isEqualTo("busy");
        assertThat(code(() -> await(seen.delete("acme", "src/b.txt")))).isEqualTo("busy");
        assertThat(code(() -> await(seen.mkdir("acme", "src/more", true)))).isEqualTo("busy");
        assertThat(code(() -> await(seen.list("acme", "src/", 10, null)))).isEqualTo("busy");
        assertThat(code(() -> await(seen.delete("acme", "other.txt")))).isEqualTo("no error");

        // Done: the journal goes, and the real tree is what readers already saw.
        await(raw.move("acme", "src/b.txt", "dst/b.txt", false));
        await(raw.move("acme", "src/sub/c.txt", "dst/sub/c.txt", false));
        await(raw.rmdir("acme", "src/sub"));
        await(raw.rmdir("acme", "src"));
        await(ops.remove("acme"));
        assertThat(names("")).containsExactly("dst");
        assertThat(names("dst")).containsExactly("a.txt", "b.txt", "sub");
    }

    @Test
    void aCopyShowsWholeAndADeleteShowsGone() throws Exception {
        running("copy", "src", "copy");
        await(raw.mkdir("acme", "copy", true));
        assertThat(names("copy")).containsExactly("a.txt", "b.txt", "sub");
        assertThat(names("src")).as("the source stays").containsExactly("a.txt", "b.txt", "sub");
        await(ops.remove("acme"));

        running("delete", "src", null);
        await(raw.delete("acme", "src/a.txt"));
        assertThat(names("")).doesNotContain("src");
        assertThat(await(seen.stat("acme", "src/b.txt"))).as("not deleted yet, but already gone to readers").isNull();
    }

    private static String code(ThrowingRunnable call) {
        try {
            call.run();
            return "no error";
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            return cause instanceof HostException host ? host.code() : String.valueOf(cause);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
