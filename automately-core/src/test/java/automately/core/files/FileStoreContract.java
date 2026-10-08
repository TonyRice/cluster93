package automately.core.files;

import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour every {@link FileStore} must have; each implementation's test extends this. */
public abstract class FileStoreContract {

    /** Set by the implementation's test before each test. */
    protected FileStore store;

    private static final int CHUNK = ChunkedBlobStore.CHUNK_SIZE;

    protected static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    /** Writes in pieces of {@code piece} bytes, as a stream would arrive. */
    protected static FileInfo write(FileStore store, String tenant, String path, byte[] data, int piece) throws Exception {
        return write(store, tenant, path, data, piece, Long.MAX_VALUE);
    }

    protected static FileInfo write(FileStore store, String tenant, String path, byte[] data, int piece, long maxBytes) throws Exception {
        FileWriter writer = await(store.create(tenant, path, "application/test", Room.fixed(maxBytes)));
        for (int i = 0; i < data.length; i += piece) {
            await(writer.write(Buffer.buffer(data).getBuffer(i, Math.min(data.length, i + piece))));
        }
        return await(writer.finish());
    }

    protected static byte[] read(BodyStream stream) throws Exception {
        CompletableFuture<byte[]> done = new CompletableFuture<>();
        Buffer all = Buffer.buffer();
        stream.handler(all::appendBuffer, () -> done.complete(all.getBytes()), done::completeExceptionally);
        stream.resume();
        return done.get(30, TimeUnit.SECONDS);
    }

    private byte[] readAll(String tenant, String path) throws Exception {
        FileInfo file = await(store.stat(tenant, path));
        return read(store.open(file, 0, file.size() - 1));
    }

    private static String etag(byte[] data) throws Exception {
        return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)) + "\"";
    }

    // --- directories (D-076) ---

    private java.util.List<String> names(String tenant, String dir) throws Exception {
        return await(store.listDir(tenant, dir, 100, null)).getJsonArray("entries").stream()
                .map(e -> ((JsonObject) e).getString("kind") + ":" + ((JsonObject) e).getString("name")).toList();
    }

    private static String code(Throwable thrown) {
        Throwable cause = thrown instanceof ExecutionException ? thrown.getCause() : thrown;
        return cause instanceof HostException host ? host.code() : String.valueOf(cause);
    }

    @Test
    public void writingAFileMakesItsFoldersAndAFolderListsOneLevel() throws Exception {
        write(store, "acme", "site/index.html", "<h1>".getBytes(), 4);
        write(store, "acme", "site/css/app.css", "a{}".getBytes(), 4);
        write(store, "acme", "site/css/deep/x.css", "b{}".getBytes(), 4);
        write(store, "acme", "readme.md", "#".getBytes(), 4);
        write(store, "globex", "site/other.html", "x".getBytes(), 4);

        assertThat(names("acme", "")).containsExactly("file:readme.md", "dir:site");
        assertThat(names("acme", "site")).containsExactly("dir:css", "file:index.html");
        assertThat(names("acme", "site/css")).containsExactly("file:app.css", "dir:deep");
        assertThat(await(store.statEntry("acme", "site/css")).getString("kind")).isEqualTo("dir");
        assertThat(await(store.statEntry("acme", "site/index.html")).getLong("size")).isEqualTo(4);
        assertThat(await(store.statEntry("acme", "nope"))).isNull();

        // Folders stay after their last file goes, as on a disk.
        await(store.delete("acme", "site/css/deep/x.css"));
        assertThat(names("acme", "site/css")).containsExactly("file:app.css", "dir:deep");
        // The old prefix listing still lists files only.
        assertThat(await(store.list("acme", "site/", 100, null)).getJsonArray("files")).hasSize(2);
    }

    @Test
    public void foldersAreMadeEmptyAndRemovedOnlyWhenEmpty() throws Exception {
        assertThat(await(store.mkdir("acme", "a/b/c", true))).isTrue();
        assertThat(await(store.mkdir("acme", "a/b/c", true))).as("-p doesn't mind").isFalse();
        assertThat(names("acme", "a/b")).containsExactly("dir:c");
        assertThatThrownBy(() -> await(store.mkdir("acme", "a/b/c", false))).satisfies(e -> assertThat(code(e)).isEqualTo("already_exists"));
        assertThatThrownBy(() -> await(store.mkdir("acme", "x/y", false))).satisfies(e -> assertThat(code(e)).isEqualTo("not_found"));

        assertThatThrownBy(() -> await(store.rmdir("acme", "a/b"))).satisfies(e -> assertThat(code(e)).isEqualTo("not_empty"));
        assertThat(await(store.rmdir("acme", "a/b/c"))).isTrue();
        assertThat(await(store.rmdir("acme", "a/b/c"))).isFalse();
        assertThat(names("acme", "a/b")).isEmpty();
    }

    @Test
    public void aNameIsAFileOrAFolderNeverBoth() throws Exception {
        write(store, "acme", "notes", "x".getBytes(), 4);
        await(store.mkdir("acme", "docs", false));

        assertThatThrownBy(() -> write(store, "acme", "docs", "x".getBytes(), 4)).satisfies(e -> assertThat(code(e)).isEqualTo("is_a_directory"));
        assertThatThrownBy(() -> write(store, "acme", "notes/inside.txt", "x".getBytes(), 4)).satisfies(e -> assertThat(code(e)).isEqualTo("not_a_directory"));
        assertThatThrownBy(() -> await(store.mkdir("acme", "notes", true))).satisfies(e -> assertThat(code(e)).isEqualTo("already_exists"));
        assertThatThrownBy(() -> await(store.listDir("acme", "notes", 10, null))).satisfies(e -> assertThat(code(e)).isEqualTo("not_a_directory"));
        assertThatThrownBy(() -> await(store.listDir("acme", "missing", 10, null))).satisfies(e -> assertThat(code(e)).isEqualTo("not_found"));
    }

    @Test
    public void bigFoldersListInPages() throws Exception {
        for (int i = 0; i < 7; i++) {
            write(store, "acme", "many/f" + i, "x".getBytes(), 4);
            await(store.mkdir("acme", "many/d" + i, false));
        }
        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            JsonObject page = await(store.listDir("acme", "many", 5, cursor));
            page.getJsonArray("entries").forEach(e -> seen.add(((JsonObject) e).getString("name")));
            cursor = page.getString("cursor");
        } while (cursor != null);
        assertThat(seen).hasSize(14).isSorted().doesNotHaveDuplicates();
    }

    @Test
    public void writesAndReadsBackWithItsHash() throws Exception {
        byte[] data = "hello files".getBytes();
        FileInfo info = write(store, "acme", "docs/hello.txt", data, 4);

        assertThat(info.size()).isEqualTo(data.length);
        assertThat(info.etag()).isEqualTo(etag(data));
        assertThat(await(store.stat("acme", "docs/hello.txt"))).isEqualTo(info);
        assertThat(readAll("acme", "docs/hello.txt")).isEqualTo(data);
    }

    @Test
    public void largeFilesSpanChunksIntact() throws Exception {
        byte[] data = bytes(CHUNK * 3 + CHUNK / 2, 1);

        FileInfo info = write(store, "acme", "big.bin", data, 100_000);

        assertThat(info.backend()).as("recorded with the file (D-056)").isNotBlank();
        assertThat(info.size()).isEqualTo(data.length);
        assertThat(info.etag()).isEqualTo(etag(data));
        assertThat(readAll("acme", "big.bin")).isEqualTo(data);
    }

    @Test
    public void rangesCrossChunkBoundaries() throws Exception {
        byte[] data = bytes(CHUNK * 2 + 10, 2);
        FileInfo info = write(store, "acme", "range.bin", data, CHUNK);

        assertThat(read(store.open(info, 0, 9))).isEqualTo(java.util.Arrays.copyOfRange(data, 0, 10));
        assertThat(read(store.open(info, CHUNK - 5, CHUNK + 4))).isEqualTo(java.util.Arrays.copyOfRange(data, CHUNK - 5, CHUNK + 5));
        assertThat(read(store.open(info, data.length - 3, data.length + 100))).isEqualTo(java.util.Arrays.copyOfRange(data, data.length - 3, data.length));
    }

    @Test
    public void emptyFilesWork() throws Exception {
        FileInfo info = write(store, "acme", "empty", new byte[0], 10);

        assertThat(info.size()).isZero();
        assertThat(read(store.open(info, 0, -1))).isEmpty();
    }

    @Test
    public void aReaderOfTheOldVersionFinishesWhileItIsReplaced() throws Exception {
        byte[] v1 = bytes(CHUNK * 3, 3);
        byte[] v2 = bytes(1000, 4);
        FileInfo old = write(store, "acme", "video.mp4", v1, CHUNK);
        BodyStream reader = store.open(old, 0, old.size() - 1);
        CompletableFuture<Void> firstChunk = new CompletableFuture<>();
        CompletableFuture<byte[]> done = new CompletableFuture<>();
        Buffer got = Buffer.buffer();
        reader.handler(chunk -> {
            got.appendBuffer(chunk);
            if (got.length() >= CHUNK && !firstChunk.isDone()) {
                // Stop after the first megabyte (however the backend splits it); the test thread replaces the file,
                // then resumes (never block a handler).
                reader.pause();
                firstChunk.complete(null);
            }
        }, () -> done.complete(got.getBytes()), done::completeExceptionally);
        reader.resume();

        firstChunk.get(30, TimeUnit.SECONDS);
        write(store, "acme", "video.mp4", v2, 500);
        reader.resume();

        assertThat(done.get(30, TimeUnit.SECONDS)).as("the old version streamed to the end").isEqualTo(v1);
        assertThat(readAll("acme", "video.mp4")).isEqualTo(v2);
    }

    @Test
    public void deletedVersionsAreCollectedAfterTheGracePeriod() throws Exception {
        FileInfo info = write(store, "acme", "gone.txt", "bye".getBytes(), 10);

        assertThat(await(store.delete("acme", "gone.txt"))).isTrue();
        assertThat(await(store.stat("acme", "gone.txt"))).isNull();
        assertThat(await(store.delete("acme", "gone.txt"))).isFalse();
        assertThat(await(store.collectGarbage(System.currentTimeMillis()))).as("still within the grace period").isZero();
        assertThat(read(store.open(info, 0, 2))).isEqualTo("bye".getBytes());

        long later = System.currentTimeMillis() + ClusterFileStore.READER_GRACE.toMillis() + 1;
        assertThat(await(store.collectGarbage(later))).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> read(store.open(info, 0, 2))).hasMessageContaining("missing");
    }

    @Test
    public void abortedUploadsLeaveNothing() throws Exception {
        FileWriter writer = await(store.create("acme", "half.bin", null, Room.fixed(Long.MAX_VALUE)));
        await(writer.write(Buffer.buffer(bytes(CHUNK + 10, 5))));

        await(writer.abort());

        assertThat(await(store.stat("acme", "half.bin"))).isNull();
        assertThat(await(store.collectGarbage(System.currentTimeMillis()))).isGreaterThanOrEqualTo(1);
    }

    @Test
    public void listsByPrefixWithPaging() throws Exception {
        for (String path : new String[]{"img/c.png", "img/a.png", "docs/x.md", "img/b.png"}) {
            write(store, "acme", path, path.getBytes(), 100);
        }
        write(store, "globex", "img/z.png", new byte[]{1}, 10);

        JsonObject first = await(store.list("acme", "img/", 2, null));
        JsonObject second = await(store.list("acme", "img/", 2, first.getString("cursor")));

        assertThat(first.getJsonArray("files").stream().map(f -> ((JsonObject) f).getString("path"))).containsExactly("img/a.png", "img/b.png");
        assertThat(second.getJsonArray("files").stream().map(f -> ((JsonObject) f).getString("path"))).containsExactly("img/c.png");
        assertThat(second.getString("cursor")).isNull();
    }

    @Test
    public void tenantsAreSeparate() throws Exception {
        write(store, "acme", "same.txt", "acme".getBytes(), 10);
        write(store, "globex", "same.txt", "globex".getBytes(), 10);

        assertThat(readAll("acme", "same.txt")).isEqualTo("acme".getBytes());
        assertThat(readAll("globex", "same.txt")).isEqualTo("globex".getBytes());
        assertThat(await(store.usage("acme"))).isEqualTo(4);
    }

    @Test
    public void anUploadIsCutOffAtItsLimit() throws Exception {
        write(store, "quota", "fits.bin", bytes(CHUNK + 100, 6), CHUNK, CHUNK + 100);

        assertThatThrownBy(() -> write(store, "quota", "over.bin", bytes(200, 7), 50, 100))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(HostException.class).hasMessageContaining("quota");
        assertThatThrownBy(() -> await(store.create("quota", "none.bin", null, Room.fixed(0))))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(HostException.class).hasMessageContaining("quota");
        assertThat(await(store.stat("quota", "over.bin"))).isNull();
        assertThat(await(store.usage("quota"))).isEqualTo(CHUNK + 100);
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
