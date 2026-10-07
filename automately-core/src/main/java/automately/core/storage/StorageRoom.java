package automately.core.storage;

import automately.core.files.BlobStore;
import automately.core.files.FileStore;
import automately.core.files.Room;
import automately.core.runtime.HostException;
import io.vertx.core.Future;

import java.util.List;

/**
 * The room a new file gets, by where it goes (D-043, D-057): on the cluster's own backend, what's left of the tenant's
 * quota (files and kv together); in the tenant's own S3 bucket, no limit (its storage, its bill); in a disk provider,
 * what's left of the disk allotment the admin gave it, across all its disk providers.
 */
public final class StorageRoom implements Room {

    private final TenantQuota quota;
    private final StorageConfigStore config;
    private final FileStore files;

    public StorageRoom(TenantQuota quota, StorageConfigStore config, FileStore files) {
        this.quota = quota;
        this.config = config;
        this.files = files;
    }

    @Override
    public Future<Long> bytes(String tenant, BlobStore target) {
        if (!target.name().startsWith(StorageProvider.BACKEND_PREFIX)) {
            return quota.remaining(tenant);
        }
        String id = target.name().substring(StorageProvider.BACKEND_PREFIX.length());
        return config.providerById(id).compose(provider -> {
            if (provider == null || !StorageProvider.DISK.equals(provider.type())) {
                return Future.succeededFuture(Long.MAX_VALUE);
            }
            return Future.all(config.policy(tenant), config.providers(tenant)).compose(both -> {
                StoragePolicy policy = both.resultAt(0);
                List<StorageProvider> owned = both.resultAt(1);
                List<String> disks = owned.stream().filter(p -> StorageProvider.DISK.equals(p.type())).map(StorageProvider::backend).toList();
                return files.usageIn(tenant, disks).compose(used -> {
                    long left = policy.diskAllotmentBytes() - used;
                    return left > 0 ? Future.succeededFuture(left) : Future.failedFuture(HostException.limit(
                            "Disk allotment of " + (policy.diskAllotmentBytes() >> 20) + " MB reached (set by the admin's storage policy)"));
                });
            });
        });
    }

    @Override
    public HostException tooBig(String tenant, BlobStore target) {
        return target.name().startsWith(StorageProvider.BACKEND_PREFIX)
                ? HostException.limit("Disk allotment for this tenant's disk providers reached (set by the admin's storage policy)")
                : TenantQuota.exceeded(quota.bytes());
    }

    /** Whether a finished file counts against the tenant's quota (only the cluster's own backend does). */
    public static boolean countsAgainstQuota(String backend) {
        return !backend.startsWith(StorageProvider.BACKEND_PREFIX);
    }
}
