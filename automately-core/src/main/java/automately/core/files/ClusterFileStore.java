package automately.core.files;

import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
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
 * Files with their metadata in the cluster's database and their bytes in a {@link BlobStore} (D-040, D-056).
 *
 * <p>Each write is a new blob. Once it's complete, one metadata write publishes it, so readers see the old version or
 * the new one, never a mix. The previous version goes to the garbage table, which deletes its blob after a grace period
 * long enough for downloads already in progress to finish. Subclasses only provide the metadata and garbage storage.
 */
public abstract class ClusterFileStore implements FileStore {

    /** How long a replaced or deleted version's blob is kept for readers still streaming it. */
    public static final Duration READER_GRACE = Duration.ofMinutes(10);
    /** An upload that never finished (its writer died) is cleaned up after this. */
    public static final Duration ABANDONED_UPLOAD = Duration.ofHours(6);

    protected final Vertx vertx;
    private final BlobStore blobs;

    protected ClusterFileStore(Vertx vertx, BlobStore blobs) {
        this.vertx = vertx;
        this.blobs = blobs;
    }

    public BlobStore blobs() {
        return blobs;
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
        String type = contentType == null ? "application/octet-stream" : contentType;
        // Recorded first, so a writer that dies mid-upload leaves nothing behind for long.
        return putGarbage(fileId, System.currentTimeMillis() + ABANDONED_UPLOAD.toMillis())
                .compose(v -> blobs.create(fileId, type))
                .map(blob -> new Writer(tenant, path, type, fileId, maxBytes, blob));
    }

    private static HostException quota() {
        return HostException.limit("Storage quota reached: the tenant has no room left for this upload (files and kv together)");
    }

    @Override
    public BodyStream open(FileInfo file, long start, long endInclusive) {
        if (!blobs.name().equals(file.backend())) {
            return failed(new HostException("Error", "storage_unavailable", "This file is stored in the " + file.backend()
                    + " backend, but the cluster now uses " + blobs.name() + " (AUTOMATELY_FILES_BACKEND); move it back to read it"));
        }
        return blobs.open(file.fileId(), file.size(), start, endInclusive);
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
                chain = chain.compose(v -> blobs.delete(id)).compose(v -> removeGarbage(id));
            }
            return chain.map(ids.size());
        });
    }

    private static BodyStream failed(Throwable cause) {
        return new BodyStream() {
            private Consumer<Throwable> error;

            @Override
            public void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
                this.error = error;
            }

            @Override
            public void pause() {
            }

            @Override
            public void resume() {
                if (error != null) {
                    Consumer<Throwable> once = error;
                    error = null;
                    once.accept(cause);
                }
            }

            @Override
            public void cancel() {
            }
        };
    }

    // --- writing ---

    /** Hashes and counts what passes through, enforces the quota, and publishes the version when the blob is complete. */
    private final class Writer implements FileWriter {

        private final String tenant;
        private final String path;
        private final String contentType;
        private final String fileId;
        private final long allowance;
        private final BlobWriter blob;
        private final MessageDigest sha256;
        private long size;
        private boolean done;

        Writer(String tenant, String path, String contentType, String fileId, long allowance, BlobWriter blob) {
            this.tenant = tenant;
            this.path = path;
            this.contentType = contentType;
            this.fileId = fileId;
            this.allowance = allowance;
            this.blob = blob;
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
                return discard().compose(v -> Future.failedFuture(quota()));
            }
            sha256.update(data.getBytes());
            return blob.write(data).recover(err -> {
                synchronized (this) {
                    done = true;
                }
                return discard().compose(v -> Future.failedFuture(err));
            });
        }

        @Override
        public synchronized Future<FileInfo> finish() {
            if (done) {
                return Future.failedFuture("The upload is already finished");
            }
            done = true;
            FileInfo info = new FileInfo(tenant, path, fileId, size, contentType, "\"" + HexFormat.of().formatHex(sha256.digest()) + "\"",
                    blobs.name(), System.currentTimeMillis());
            // Only a blob that never completed is discarded here. Once the metadata names it, it's live; if publishing
            // fails before that, its abandoned-upload row in the garbage table still cleans it up.
            return blob.finish()
                    .recover(err -> discard().compose(v -> Future.failedFuture(err)))
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
            return discard();
        }

        /** The blob goes, and its garbage row is made due now, in case deleting it failed. */
        private Future<Void> discard() {
            return blob.abort().recover(err -> Future.succeededFuture()).compose(v -> putGarbage(fileId, 0));
        }
    }
}
