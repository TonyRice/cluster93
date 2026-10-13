package cluster93.cluster.ignite;

import cluster93.core.files.StorageCapacity;
import io.vertx.core.Future;
import org.apache.ignite.DataRegionMetrics;

/**
 * Storage admission control for this node (D-038). With persistence, the limit is free disk space in the data
 * directory (a full disk is a critical failure that halts Ignite). In memory, it's how full the data region is (a full
 * region is an out-of-memory error). Writes are refused well before either.
 */
public final class IgniteCapacity {

    /** In-memory regions refuse new data at this fill; eviction (D-038) only starts at 90 %. */
    public static final double MAX_REGION_FILL = 0.8;

    private IgniteCapacity() {
    }

    public static StorageCapacity of(IgniteCluster cluster, long minFreeDiskBytes) {
        IgniteSettings settings = cluster.settings();
        if (settings.persistent()) {
            java.io.File dir = settings.dataDirectory().toFile();
            return () -> {
                long free = dir.getUsableSpace();
                return free >= minFreeDiskBytes
                        ? Future.succeededFuture()
                        : Future.failedFuture(StorageCapacity.insufficient(
                        "only " + free / (1024 * 1024) + " MB of disk left for the data directory (keeping " + minFreeDiskBytes / (1024 * 1024) + " MB free)"));
            };
        }
        long max = settings.dataMemoryMb() * 1024L * 1024;
        return () -> {
            DataRegionMetrics metrics = cluster.ignite().dataRegionMetrics(IgniteCluster.DATA_REGION);
            double fill = metrics == null ? 0 : (double) metrics.getTotalUsedSize() / max;
            return fill <= MAX_REGION_FILL
                    ? Future.succeededFuture()
                    : Future.failedFuture(StorageCapacity.insufficient(String.format("the in-memory data region is %.0f%% full", fill * 100)));
        };
    }
}
