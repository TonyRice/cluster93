package cluster93.core.files;

import cluster93.core.runtime.HostException;
import io.vertx.core.Future;

/**
 * Admission control for writes that consume storage (D-038). Checked before every file chunk and every kv write, so a
 * node refuses work with "insufficient storage" (HTTP 507) long before its disk or memory fills up, instead of
 * reaching a failure that halts the JVM.
 */
@FunctionalInterface
public interface StorageCapacity {

    /** Completes if there's room for more data; fails with {@link #insufficient} otherwise. */
    Future<Void> ensureRoom();

    StorageCapacity UNLIMITED = Future::succeededFuture;

    static HostException insufficient(String why) {
        return new HostException("Error", "insufficient_storage", "Not enough storage on this cluster: " + why);
    }

    /**
     * For the in-memory development store, which keeps data on the JVM heap: refuses once heap use passes
     * {@code maxFill} of the maximum.
     */
    static StorageCapacity heap(double maxFill) {
        return () -> {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            double fill = (double) used / runtime.maxMemory();
            return fill <= maxFill
                    ? Future.succeededFuture()
                    : Future.failedFuture(insufficient(String.format("JVM heap is %.0f%% full", fill * 100)));
        };
    }
}
