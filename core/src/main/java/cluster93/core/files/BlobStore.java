package cluster93.core.files;

import cluster93.core.runtime.BodyStream;
import io.vertx.core.Future;

/**
 * Where file bytes live (D-056): the cluster itself, a disk folder, or an S3-compatible bucket. A blob is one version
 * of one file, written once and never changed; paths, versions, quotas and the atomic swap between versions stay in
 * the cluster's database ({@link ClusterFileStore}), whatever holds the bytes.
 */
public interface BlobStore {

    /** "cluster", "disk" or "s3": recorded with each file, so it's always read from where it was written. */
    String name();

    /** Starts writing a new blob. Fails with {@code insufficient_storage} if the backend has no room. */
    Future<BlobWriter> create(String blobId, String contentType);

    /** Bytes {@code start} to {@code endInclusive} of a finished blob of {@code size} bytes, as a paused stream. */
    BodyStream open(String blobId, long size, long start, long endInclusive);

    /** Deletes a blob, finished or abandoned mid-write; succeeds if there's nothing to delete. */
    Future<Void> delete(String blobId);

    /** Run at startup: fails with a message saying what's wrong if the backend can't be used. */
    default Future<Void> check() {
        return Future.succeededFuture();
    }
}
