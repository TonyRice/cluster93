package automately.core.files;

import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The file logic both stores share; subclasses only provide storage primitives. Writes buffer into fixed-size chunks
 * (hashing as they go), publish the new version with one metadata write, and hand the previous version to the garbage
 * table, which deletes its chunks after a grace period, long enough for downloads already in progress to finish.
 */
public abstract class ChunkedFileStore implements FileStore {

    public static final int CHUNK_SIZE = 1024 * 1024;
    /** How long a replaced or deleted version's chunks are kept for readers still streaming it. */
    public static final Duration READER_GRACE = Duration.ofMinutes(10);
    /** An upload that never finished (its writer died) is cleaned up after this. */
    public static final Duration ABANDONED_UPLOAD = Duration.ofHours(6);

    protected final Vertx vertx;
    private final StorageCapacity capacity;

    protected ChunkedFileStore(Vertx vertx, StorageCapacity capacity) {
        this.vertx = vertx;
        this.capacity = capacity;
    }

    // --- storage primitives ---

    protected abstract Future<JsonObject> getMeta(String tenant, String path);

    /** Stores the metadata and completes with the previous metadata, or null. */
    protected abstract Future<JsonObject> putMeta(String tenant, String path, JsonObject meta);

    /** Removes the metadata and completes with what it was, or null. */
    protected abstract Future<JsonObject> removeMeta(String tenant, String path);

    /** Metadata in path order. */
    protected abstract Future<List<JsonObject>> listMeta(String tenant, String prefix, int limit, String cursor);

    protected abstract Future<Long> sumSizes(String tenant);

    protected abstract Future<Void> putChunk(String fileId, int index, Buffer data);

    /** Completes with null if the chunk doesn't exist. */
    protected abstract Future<Buffer> getChunk(String fileId, int index);

    protected abstract Future<Void> removeChunk(String fileId, int index);

    protected abstract Future<Void> putGarbage(String fileId, long deleteAfter);

    protected abstract Future<Void> removeGarbage(String fileId);

    protected abstract Future<List<String>> dueGarbage(long now, int limit);

    // --- FileStore ---

    @Override
    public Future<FileInfo> stat(String tenant, String path) {
        return getMeta(tenant, path).map(FileInfo::fromJson);
    }

    @Override
    public Future<Long> usage(String tenant) {
        return sumSizes(tenant);
    }

    @Override
    public Future<FileWriter> create(String tenant, String path, String contentType, long maxBytes) {
        if (maxBytes <= 0) {
            return Future.failedFuture(quota());
        }
        String fileId = UUID.randomUUID().toString();
        // Recorded first, so a writer that dies mid-upload leaves nothing behind for long.
        return capacity.ensureRoom()
                .compose(room -> putGarbage(fileId, System.currentTimeMillis() + ABANDONED_UPLOAD.toMillis()))
                .map(v -> new Writer(tenant, path, contentType, fileId, maxBytes));
    }

    private static HostException quota() {
        return HostException.limit("Storage quota reached: the tenant has no room left for this upload (files and kv together)");
    }

    @Override
    public Future<Boolean> delete(String tenant, String path) {
        return removeMeta(tenant, path).compose(previous -> previous == null
                ? Future.succeededFuture(false)
                : putGarbage(previous.getString("fileId"), System.currentTimeMillis() + READER_GRACE.toMillis()).map(true));
    }

    @Override
    public Future<JsonObject> list(String tenant, String prefix, int limit, String cursor) {
        return listMeta(tenant, prefix, limit + 1, cursor).map(rows -> {
            boolean more = rows.size() > limit;
            List<JsonObject> page = more ? rows.subList(0, limit) : rows;
            return new JsonObject()
                    .put("files", new io.vertx.core.json.JsonArray(page.stream().map(r -> FileInfo.fromJson(r).toViewJson()).toList()))
                    .put("cursor", more ? page.getLast().getString("path") : null);
        });
    }

    @Override
    public Future<Integer> collectGarbage(long now) {
        return dueGarbage(now, 100).compose(ids -> {
            Future<Void> chain = Future.succeededFuture();
            for (String id : ids) {
                chain = chain.compose(v -> deleteChunks(id, 0)).compose(v -> removeGarbage(id));
            }
            return chain.map(ids.size());
        });
    }

    /** Chunks are written in order, so deleting stops at the first missing one. */
    private Future<Void> deleteChunks(String fileId, int index) {
        return getChunk(fileId, index).compose(chunk -> chunk == null
                ? Future.succeededFuture()
                : removeChunk(fileId, index).compose(v -> deleteChunks(fileId, index + 1)));
    }

    // --- writing ---

    private final class Writer implements FileWriter {

        private final String tenant;
        private final String path;
        private final String contentType;
        private final String fileId;
        private final long allowance;
        private final MessageDigest sha256;
        private Buffer pending = Buffer.buffer();
        private long size;
        private int chunks;
        private boolean done;

        Writer(String tenant, String path, String contentType, String fileId, long allowance) {
            this.tenant = tenant;
            this.path = path;
            this.contentType = contentType;
            this.fileId = fileId;
            this.allowance = allowance;
            try {
                this.sha256 = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized Future<Void> write(Buffer data) {
            if (done) {
                return Future.failedFuture("The upload is already finished");
            }
            size += data.length();
            if (size > allowance) {
                done = true;
                return abortChunks().compose(v -> Future.failedFuture(quota()));
            }
            sha256.update(data.getBytes());
            pending.appendBuffer(data);
            Future<Void> stored = Future.succeededFuture();
            while (pending.length() >= CHUNK_SIZE) {
                Buffer chunk = pending.getBuffer(0, CHUNK_SIZE);
                pending = pending.getBuffer(CHUNK_SIZE, pending.length());
                int index = chunks++;
                // Checked before every chunk: an upload stops with "insufficient storage" before disk or memory fills.
                stored = stored.compose(v -> capacity.ensureRoom()).compose(v -> putChunk(fileId, index, chunk));
            }
            return stored.recover(err -> abortChunks().compose(v -> Future.failedFuture(err)));
        }

        @Override
        public synchronized Future<FileInfo> finish() {
            if (done) {
                return Future.failedFuture("The upload is already finished");
            }
            done = true;
            Future<Void> last = pending.length() > 0 || chunks == 0
                    ? putChunk(fileId, chunks++, pending)
                    : Future.succeededFuture();
            FileInfo info = new FileInfo(tenant, path, fileId, size, contentType == null ? "application/octet-stream" : contentType,
                    "\"" + HexFormat.of().formatHex(sha256.digest()) + "\"", CHUNK_SIZE, chunks, System.currentTimeMillis());
            return last
                    .compose(v -> putMeta(tenant, path, info.toJson()))
                    .compose(previous -> removeGarbage(fileId).compose(v -> previous == null || previous.getString("fileId").equals(fileId)
                            ? Future.succeededFuture()
                            : putGarbage(previous.getString("fileId"), System.currentTimeMillis() + READER_GRACE.toMillis())))
                    .map(info);
        }

        @Override
        public synchronized Future<Void> abort() {
            if (done) {
                return Future.succeededFuture();
            }
            done = true;
            return abortChunks();
        }

        private Future<Void> abortChunks() {
            return putGarbage(fileId, 0);
        }
    }

    // --- reading ---

    @Override
    public BodyStream open(FileInfo file, long start, long endInclusive) {
        return new ChunkReader(file, start, endInclusive);
    }

    /** Pulls one chunk at a time while resumed; slices the first and last chunk to the requested range. */
    private final class ChunkReader implements BodyStream {

        private final FileInfo file;
        private final long endInclusive;
        private long position;
        private Context context;
        private Consumer<Buffer> chunk;
        private Runnable end;
        private Consumer<Throwable> error;
        private boolean paused = true;
        private boolean reading;
        private boolean finished;

        ChunkReader(FileInfo file, long start, long endInclusive) {
            this.file = file;
            this.position = start;
            this.endInclusive = Math.min(endInclusive, file.size() - 1);
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
                index = (int) (position / file.chunkSize());
            }
            getChunk(file.fileId(), index).onComplete(ar -> {
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
                    long chunkStart = (long) index * file.chunkSize();
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
