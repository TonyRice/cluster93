package automately.core.files.s3;

import automately.core.files.BlobStore;
import automately.core.files.BlobWriter;
import automately.core.files.BodyStreams;
import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code s3} backend (D-056): one object per blob in an S3-compatible bucket, at {@code <prefix><blobId>}.
 * Uploads stream as multipart parts of {@link #PART_SIZE}, each part's upload being the upload's backpressure;
 * a blob smaller than a part is a single PUT. Reads are ranged GETs streamed to the client.
 */
public final class S3BlobStore implements BlobStore {

    public static final String NAME = "s3";
    /** S3 parts must be at least 5 MiB, except the last; 8 MiB keeps requests few and memory per upload bounded. */
    public static final int PART_SIZE = 8 * 1024 * 1024;

    private final String name;
    private final S3Client s3;

    public S3BlobStore(Vertx vertx, S3Settings settings) {
        this(NAME, new S3Client(vertx, settings));
    }

    /** {@code name} identifies this bucket in file metadata (tenants' own providers have their own, D-057). */
    public S3BlobStore(String name, S3Client s3) {
        this.name = name;
        this.s3 = s3;
    }

    @Override
    public String name() {
        return name;
    }

    private String key(String blobId) {
        return s3.settings().prefix() + blobId;
    }

    @Override
    public Future<Void> check() {
        return s3.headBucket().recover(err -> Future.failedFuture(new IllegalStateException(
                "Can't use " + s3.settings().describe() + ": " + err.getMessage(), err)));
    }

    @Override
    public Future<BlobWriter> create(String blobId, String contentType) {
        return Future.succeededFuture(new Writer(key(blobId), contentType));
    }

    @Override
    public BodyStream open(String blobId, long size, long start, long endInclusive) {
        long end = Math.min(endInclusive, size - 1);
        if (end < start) {
            return BodyStreams.lazy(null, () -> { });
        }
        // Carries on with a new range if the connection closes partway, or a request fails to open (D-081, D-094); the
        // reader sees the store's wording only if it gives up.
        return new ResumingRangeBody((offset, retry) -> s3.getRange(key(blobId), start + offset, end, retry), end - start + 1,
                (delayMs, task) -> s3.vertx().setTimer(Math.max(1, delayMs), id -> task.run()),
                err -> err instanceof S3Client.S3Exception e && e.status() == 404
                        ? new IllegalStateException("File data is missing (it was replaced or deleted)")
                        : unavailable(err));
    }

    /** The object, and any multipart upload of it that never finished (a node died mid-upload). */
    @Override
    public Future<Void> delete(String blobId) {
        String key = key(blobId);
        return s3.deleteObject(key)
                .compose(v -> s3.unfinishedUploads(key))
                .compose(ids -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (String id : ids) {
                        chain = chain.compose(x -> s3.abortMultipartUpload(key, id));
                    }
                    return chain;
                })
                .recover(err -> Future.failedFuture(unavailable(err)));
    }

    static HostException unavailable(Throwable err) {
        return new HostException("Error", "storage_unavailable", "The file storage backend failed: " + err.getMessage());
    }

    private final class Writer implements BlobWriter {

        private final String key;
        private final String contentType;
        private final List<String> etags = new ArrayList<>();
        private Buffer pending = Buffer.buffer();
        private String uploadId;

        Writer(String key, String contentType) {
            this.key = key;
            this.contentType = contentType;
        }

        @Override
        public synchronized Future<Void> write(Buffer data) {
            pending.appendBuffer(data);
            Future<Void> sent = Future.succeededFuture();
            while (pending.length() >= PART_SIZE) {
                Buffer part = pending.getBuffer(0, PART_SIZE);
                pending = pending.getBuffer(PART_SIZE, pending.length());
                sent = sent.compose(v -> sendPart(part));
            }
            return sent.recover(err -> Future.failedFuture(unavailable(err)));
        }

        private Future<Void> sendPart(Buffer part) {
            Future<String> upload = uploadId != null ? Future.succeededFuture(uploadId)
                    : s3.createMultipartUpload(key, contentType).onSuccess(id -> uploadId = id);
            return upload.compose(id -> s3.uploadPart(key, id, etags.size() + 1, part)).map(etag -> {
                etags.add(etag);
                return null;
            });
        }

        @Override
        public synchronized Future<Void> finish() {
            Future<Void> done = uploadId == null
                    ? s3.putObject(key, pending, contentType)
                    : (pending.length() > 0 ? sendPart(pending) : Future.<Void>succeededFuture())
                            .compose(v -> s3.completeMultipartUpload(key, uploadId, etags))
                            // A start whose answer was lost and asked again (D-098) may have left another upload of
                            // this key; the key is this blob's alone, so any still open is an orphan.
                            .compose(v -> abortUnfinished().recover(err -> Future.succeededFuture()));
            return done.recover(err -> Future.failedFuture(unavailable(err)));
        }

        /** Every upload of this blob still open, not only its own: see {@link #finish()}. */
        @Override
        public synchronized Future<Void> abort() {
            return uploadId == null ? Future.succeededFuture() : abortUnfinished();
        }

        private Future<Void> abortUnfinished() {
            return s3.unfinishedUploads(key).compose(ids -> Future.all(ids.stream().map(id -> s3.abortMultipartUpload(key, id)).toList()))
                    .mapEmpty();
        }
    }
}
