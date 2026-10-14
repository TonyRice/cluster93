package cluster93.core.files;

import io.vertx.core.Future;

/**
 * How many bytes a new file may store in a given backend (D-043, D-057): what's left of the tenant's quota on the
 * cluster's own backend, what's left of its disk allotment on a disk provider, no limit in its own bucket.
 * Fails (with the reason) when there's no room at all.
 */
@FunctionalInterface
public interface Room {

    Future<Long> bytes(String tenant, BlobStore target);

    /** Why a file of a known size doesn't fit there (the quota, or a disk allotment). */
    default cluster93.core.runtime.HostException tooBig(String tenant, BlobStore target) {
        return ClusterFileStore.quota();
    }

    static Room fixed(long bytes) {
        return (tenant, target) -> Future.succeededFuture(bytes);
    }
}
