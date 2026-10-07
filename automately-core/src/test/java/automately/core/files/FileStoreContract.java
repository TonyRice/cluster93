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

    /** A store with plenty of quota, set by the implementation's test before each test. */
    protected FileStore store;

    /** A store with the given quota, sharing the same storage. */
    protected abstract FileStore storeWithQuota(long quotaBytes);

    private static final int CHUNK = ChunkedFileStore.CHUNK_SIZE;

    protected static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    /** Writes in pieces of {@code piece} bytes, as a stream would arrive. */
    protected static FileInfo write(FileStore store, String tenant, String path, byte[] data, int piece) throws Exception {
        FileWriter writer = await(store.create(tenant, path, "application/test"));
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

        assertThat(info.chunks()).isEqualTo(4);
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
            if (got.length() == CHUNK) {
                // Stop after the first chunk; the test thread replaces the file, then resumes (never block a handler).
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

        long later = System.currentTimeMillis() + ChunkedFileStore.READER_GRACE.toMillis() + 1;
        assertThat(await(store.collectGarbage(later))).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> read(store.open(info, 0, 2))).hasMessageContaining("missing");
    }

    @Test
    public void abortedUploadsLeaveNothing() throws Exception {
        FileWriter writer = await(store.create("acme", "half.bin", null));
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
    public void theQuotaIsEnforcedWhileWriting() throws Exception {
        FileStore small = storeWithQuota(CHUNK + 100);
        write(small, "quota", "first.bin", bytes(CHUNK, 6), CHUNK);

        assertThatThrownBy(() -> write(small, "quota", "second.bin", bytes(200, 7), 50))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(HostException.class).hasMessageContaining("quota");
        assertThat(await(small.stat("quota", "second.bin"))).isNull();
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
