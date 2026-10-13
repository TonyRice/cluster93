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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Moving, copying and deleting folders (D-077), including finishing one whose node died. */
class FileTreeTest {

    private Vertx vertx;
    private FileStore files;
    private Records ops;
    private FileTree tree;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        files = new SharedDataFileStore(vertx);
        ops = new SharedDataRecords(vertx, FileTree.OPS);
        tree = new FileTree(vertx, files, ops, "node-a");
        for (String path : List.of("site/index.html", "site/css/app.css", "site/css/print.css", "site/img/logo.png", "other.txt")) {
            write(path, path);
        }
        await(files.mkdir("acme", "site/empty", false));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private void write(String path, String content) throws Exception {
        FileWriter writer = await(files.create("acme", path, "text/plain", Room.fixed(Long.MAX_VALUE)));
        await(writer.write(Buffer.buffer(content)));
        await(writer.finish());
    }

    private List<String> everything(String dir) throws Exception {
        List<String> found = new java.util.ArrayList<>();
        for (Object item : await(files.listDir("acme", dir, 1000, null)).getJsonArray("entries")) {
            JsonObject entry = (JsonObject) item;
            found.add(entry.getString("path") + ("dir".equals(entry.getString("kind")) ? "/" : ""));
            if ("dir".equals(entry.getString("kind"))) {
                found.addAll(everything(entry.getString("path")));
            }
        }
        return found;
    }

    @Test
    void movingAFolderTakesEverythingInIt() throws Exception {
        JsonObject result = await(tree.move("acme", "site", "archive/site-v1", false));

        assertThat(result.getInteger("files")).isEqualTo(4);
        assertThat(everything("archive")).containsExactly("archive/site-v1/", "archive/site-v1/css/", "archive/site-v1/css/app.css",
                "archive/site-v1/css/print.css", "archive/site-v1/empty/", "archive/site-v1/img/", "archive/site-v1/img/logo.png",
                "archive/site-v1/index.html");
        assertThat(await(files.statEntry("acme", "site"))).as("the old folder is gone").isNull();
        assertThat(await(ops.all())).as("the journal is cleared").isEmpty();
    }

    @Test
    void copyingAFolderSharesTheBytes() throws Exception {
        await(tree.copy("acme", "site", "site-copy", false));

        assertThat(everything("site-copy")).hasSize(7);
        assertThat(everything("site")).as("the original stays").hasSize(7);
        assertThat(await(files.stat("acme", "site-copy/css/app.css")).fileId()).isEqualTo(await(files.stat("acme", "site/css/app.css")).fileId());
    }

    @Test
    void deletingAFolderTakesEverythingInIt() throws Exception {
        await(tree.delete("acme", "site"));

        assertThat(await(files.statEntry("acme", "site"))).isNull();
        assertThat(everything("")).containsExactly("other.txt");
    }

    @Test
    void foldersCantGoInsideThemselvesOrOverOthersWithoutAsking() throws Exception {
        await(files.mkdir("acme", "taken", false));

        assertThat(code(() -> await(tree.move("acme", "site", "site/inner", false)))).isEqualTo("invalid_argument");
        assertThat(code(() -> await(tree.copy("acme", "site", "taken", false)))).isEqualTo("already_exists");
        assertThat(code(() -> await(tree.copy("acme", "site", "other.txt", true)))).isEqualTo("not_a_directory");
        assertThat(code(() -> await(tree.move("acme", "nowhere", "x", false)))).isEqualTo("not_found");

        await(tree.copy("acme", "site", "taken", true));
        assertThat(everything("taken")).as("merged in when asked").hasSize(7);
    }

    @Test
    void oneFolderOperationAtATimePerTenant() throws Exception {
        await(ops.put("acme", new JsonObject().put("tenant", "acme").put("op", "copy").put("from", "x").put("to", "y")
                .put("holder", "node-b").put("until", System.currentTimeMillis() + 60_000)));

        assertThat(code(() -> await(tree.move("acme", "site", "elsewhere", false)))).isEqualTo("busy");
        assertThat(await(tree.move("acme", "other.txt", "moved.txt", false)).getInteger("files")).as("single files don't wait").isEqualTo(1);
    }

    @Test
    void anotherNodeFinishesAMoveWhoseNodeDied() throws Exception {
        // node-b started moving site, got as far as one file, and died: its lease ran out.
        await(files.mkdir("acme", "moved", true));
        await(files.move("acme", "site/index.html", "moved/index.html", false));
        await(ops.put("acme", new JsonObject().put("tenant", "acme").put("op", "move").put("from", "site").put("to", "moved")
                .put("overwrite", false).put("holder", "node-b").put("until", System.currentTimeMillis() - 1)));

        assertThat(await(tree.resumeAbandoned())).isEqualTo(1);

        assertThat(everything("moved")).containsExactly("moved/css/", "moved/css/app.css", "moved/css/print.css", "moved/empty/", "moved/img/",
                "moved/img/logo.png", "moved/index.html");
        assertThat(await(files.statEntry("acme", "site"))).isNull();
        assertThat(await(ops.all())).isEmpty();
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
