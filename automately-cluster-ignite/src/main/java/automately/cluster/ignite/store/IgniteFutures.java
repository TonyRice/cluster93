package automately.cluster.ignite.store;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.apache.ignite.lang.IgniteFuture;

import java.util.function.Supplier;

/** Ignite futures as Vert.x futures, with retries for failures caused only by a topology change (D-033). */
final class IgniteFutures {

    static final int MAX_TOPOLOGY_RETRIES = 10;

    private IgniteFutures() {
    }

    /** Starts an Ignite operation; completes on the caller's context. Retried if the topology changed under it. */
    static <T> Future<T> async(Vertx vertx, Supplier<IgniteFuture<T>> operation) {
        return retrying(vertx, () -> {
            Context context = vertx.getOrCreateContext();
            Promise<T> promise = Promise.promise();
            try {
                operation.get().listen(done -> context.runOnContext(v -> {
                    try {
                        promise.complete(done.get());
                    } catch (RuntimeException e) {
                        promise.fail(e);
                    }
                }));
            } catch (RuntimeException e) {
                promise.fail(e);
            }
            return promise.future();
        }, 1);
    }

    /**
     * While a node joins or leaves, Ignite can fail an operation with a topology error that succeeds when retried a
     * moment later. Only those are retried; callers use this for reads, idempotent writes and compare-and-set.
     */
    static <T> Future<T> retrying(Vertx vertx, Supplier<Future<T>> operation, int attempt) {
        return operation.get().recover(err -> {
            if (attempt >= MAX_TOPOLOGY_RETRIES || !isTopologyChange(err)) {
                return Future.failedFuture(err);
            }
            Promise<T> retried = Promise.promise();
            vertx.setTimer(100L * attempt, id -> retrying(vertx, operation, attempt + 1).onComplete(retried));
            return retried.future();
        });
    }

    static boolean isTopologyChange(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage());
            if (t instanceof org.apache.ignite.cluster.ClusterTopologyException
                    || t instanceof org.apache.ignite.internal.cluster.ClusterTopologyCheckedException
                    || message.contains("topology")
                    // SQL while partitions are still moving (a fresh cluster, a node joining or leaving).
                    || message.contains("Failed to reserve partitions for query")
                    || message.contains("please retry the query")
                    // A query planned or running while partitions move is cancelled by Ignite; retried like the above.
                    || t instanceof org.apache.ignite.cache.query.QueryCancelledException) {
                return true;
            }
        }
        return false;
    }
}
