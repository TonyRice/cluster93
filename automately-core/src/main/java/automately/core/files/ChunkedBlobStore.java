package automately.core.files;

import automately.core.runtime.BodyStream;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import java.util.function.Consumer;

/**
 * The {@code cluster} backend (D-040): a blob is 1 MB chunks in the cluster's replicated store, so files need nothing
 * outside the cluster. Subclasses provide the chunk storage (Ignite, or Vert.x shared maps for a single node).
 */
public abstract class ChunkedBlobStore implements BlobStore {

    public static final String NAME = "cluster";
    public static final int CHUNK_SIZE = 1024 * 1024;

    protected final Vertx vertx;
    private final StorageCapacity capacity;

    protected ChunkedBlobStore(Vertx vertx, StorageCapacity capacity) {
        this.vertx = vertx;
        this.capacity = capacity;
    }

    protected abstract Future<Void> putChunk(String blobId, int index, Buffer data);

    /** Completes with null if the chunk doesn't exist. */
    protected abstract Future<Buffer> getChunk(String blobId, int index);

    protected abstract Future<Void> removeChunk(String blobId, int index);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Future<BlobWriter> create(String blobId, String contentType) {
        return capacity.ensureRoom().map(room -> new Writer(blobId));
    }

    @Override
    public BodyStream open(String blobId, long size, long start, long endInclusive) {
        return new ChunkReader(blobId, Math.min(endInclusive, size - 1), start);
    }

    /** Chunks are written in order, so deleting stops at the first missing one. */
    @Override
    public Future<Void> delete(String blobId) {
        return deleteFrom(blobId, 0);
    }

    private Future<Void> deleteFrom(String blobId, int index) {
        return getChunk(blobId, index).compose(chunk -> chunk == null
                ? Future.succeededFuture()
                : removeChunk(blobId, index).compose(v -> deleteFrom(blobId, index + 1)));
    }

    private final class Writer implements BlobWriter {

        private final String blobId;
        private Buffer pending = Buffer.buffer();
        private int chunks;

        Writer(String blobId) {
            this.blobId = blobId;
        }

        @Override
        public synchronized Future<Void> write(Buffer data) {
            pending.appendBuffer(data);
            Future<Void> stored = Future.succeededFuture();
            while (pending.length() >= CHUNK_SIZE) {
                Buffer chunk = pending.getBuffer(0, CHUNK_SIZE);
                pending = pending.getBuffer(CHUNK_SIZE, pending.length());
                int index = chunks++;
                // Checked before every chunk: an upload stops with "insufficient storage" before disk or memory fills (D-038).
                stored = stored.compose(v -> capacity.ensureRoom()).compose(v -> putChunk(blobId, index, chunk));
            }
            return stored;
        }

        @Override
        public synchronized Future<Void> finish() {
            // An empty file is one empty chunk, so it exists like any other.
            return pending.length() > 0 || chunks == 0 ? putChunk(blobId, chunks++, pending) : Future.succeededFuture();
        }

        @Override
        public Future<Void> abort() {
            return delete(blobId);
        }
    }

    /** Pulls one chunk at a time while resumed; slices the first and last chunk to the requested range. */
    private final class ChunkReader implements BodyStream {

        private final String blobId;
        private final long endInclusive;
        private long position;
        private Context context;
        private Consumer<Buffer> chunk;
        private Runnable end;
        private Consumer<Throwable> error;
        private boolean paused = true;
        private boolean reading;
        private boolean finished;

        ChunkReader(String blobId, long endInclusive, long start) {
            this.blobId = blobId;
            this.endInclusive = endInclusive;
            this.position = start;
        }

        @Override
        public synchronized void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
            this.chunk = chunk;
            this.end = end;
            this.error = error;
            this.context = vertx.getOrCreateContext();
        }

        @Override
        public synchronized void pause() {
            paused = true;
        }

        @Override
        public void resume() {
            synchronized (this) {
                paused = false;
            }
            pull();
        }

        @Override
        public synchronized void cancel() {
            finished = true;
        }

        private void pull() {
            int index;
            synchronized (this) {
                if (paused || reading || finished || chunk == null) {
                    return;
                }
                if (position > endInclusive) {
                    finished = true;
                    context.runOnContext(v -> end.run());
                    return;
                }
                reading = true;
                index = (int) (position / CHUNK_SIZE);
            }
            getChunk(blobId, index).onComplete(ar -> {
                Buffer slice;
                synchronized (this) {
                    reading = false;
                    if (finished) {
                        return;
                    }
                    if (ar.failed() || ar.result() == null) {
                        finished = true;
                        Throwable cause = ar.failed() ? ar.cause() : new IllegalStateException("File data is missing (it was replaced or deleted)");
                        context.runOnContext(v -> error.accept(cause));
                        return;
                    }
                    long chunkStart = (long) index * CHUNK_SIZE;
                    int from = (int) (position - chunkStart);
                    int to = (int) Math.min(ar.result().length(), endInclusive - chunkStart + 1);
                    slice = ar.result().getBuffer(from, to);
                    position = chunkStart + to;
                }
                context.runOnContext(v -> {
                    chunk.accept(slice);
                    pull();
                });
            });
        }
    }
}
