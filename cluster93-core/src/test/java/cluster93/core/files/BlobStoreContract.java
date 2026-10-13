package cluster93.core.files;

import cluster93.core.runtime.BodyStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What every file backend must do (D-056); each backend's test extends this. */
public abstract class BlobStoreContract {

    /** Set by the backend's test before each test. */
    protected BlobStore blobs;

    protected static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    /** Writes in pieces of growing, uneven sizes, as a network stream would arrive. */
    protected String write(byte[] data) throws Exception {
        String id = UUID.randomUUID().toString();
        BlobWriter writer = await(blobs.create(id, "application/test"));
        int[] pieces = {1, 7, 4096, 65_536, 1_000_003, 3 * 1024 * 1024};
        int position = 0;
        for (int i = 0; position < data.length; i++) {
            int size = Math.min(pieces[i % pieces.length], data.length - position);
            await(writer.write(Buffer.buffer(Arrays.copyOfRange(data, position, position + size))));
            position += size;
        }
        await(writer.finish());
        return id;
    }

    protected byte[] read(String id, long size, long start, long endInclusive) throws Exception {
        BodyStream stream = blobs.open(id, size, start, endInclusive);
        CompletableFuture<byte[]> done = new CompletableFuture<>();
        Buffer all = Buffer.buffer();
        stream.handler(all::appendBuffer, () -> done.complete(all.getBytes()), done::completeExceptionally);
        stream.resume();
        return done.get(60, TimeUnit.SECONDS);
    }

    @Test
    public void smallAndLargeBlobsComeBackIntact() throws Exception {
        byte[] small = bytes(1234, 1);
        byte[] large = bytes(20 * 1024 * 1024 + 17, 2);

        String a = write(small);
        String b = write(large);

        assertThat(read(a, small.length, 0, small.length - 1)).isEqualTo(small);
        assertThat(read(b, large.length, 0, large.length - 1)).isEqualTo(large);
    }

    @Test
    public void rangesAcrossChunkAndPartBoundaries() throws Exception {
        byte[] data = bytes(17 * 1024 * 1024 + 5, 3);
        String id = write(data);
        int mb = 1024 * 1024;

        for (long[] range : new long[][] {{0, 0}, {mb - 3, mb + 2}, {8L * mb - 1, 8L * mb}, {16L * mb - 10, 16L * mb + 9},
                {data.length - 1, data.length - 1}, {5, 3 * mb}}) {
            assertThat(read(id, data.length, range[0], range[1]))
                    .as("bytes " + range[0] + "-" + range[1])
                    .isEqualTo(Arrays.copyOfRange(data, (int) range[0], (int) range[1] + 1));
        }
        assertThat(read(id, data.length, 10, Long.MAX_VALUE)).as("an end past the size stops at the end")
                .isEqualTo(Arrays.copyOfRange(data, 10, data.length));
    }

    @Test
    public void anEmptyBlobIsEmpty() throws Exception {
        String id = write(new byte[0]);
        assertThat(read(id, 0, 0, -1)).isEmpty();
    }

    @Test
    public void anAbortedBlobNeverAppears() throws Exception {
        String id = UUID.randomUUID().toString();
        BlobWriter writer = await(blobs.create(id, "application/test"));
        await(writer.write(Buffer.buffer(bytes(9 * 1024 * 1024, 4))));
        await(writer.abort());

        assertThatThrownBy(() -> read(id, 9 * 1024 * 1024, 0, 99)).isInstanceOf(ExecutionException.class);
        await(blobs.delete(id));
    }

    @Test
    public void deletingIsFinalAndRepeatable() throws Exception {
        byte[] data = bytes(3 * 1024 * 1024, 5);
        String id = write(data);

        await(blobs.delete(id));
        await(blobs.delete(id));
        await(blobs.delete(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> read(id, data.length, 0, data.length - 1))
                .isInstanceOf(ExecutionException.class).cause().hasMessageContaining("missing");
    }

    @Test
    public void theBackendChecksOut() throws Exception {
        await(blobs.check());
        assertThat(blobs.name()).isNotBlank();
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
