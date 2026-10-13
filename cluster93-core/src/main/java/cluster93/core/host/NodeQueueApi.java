package cluster93.core.host;

import cluster93.core.queue.QueueConsumer;
import cluster93.core.queue.QueueService;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.Inbound;
import cluster93.core.runtime.api.QueueApi;
import cluster93.core.runtime.api.Registration;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.function.Consumer;

/** {@code bus.queue(name)} on the node's {@link QueueService}, for the execution's tenant. */
final class NodeQueueApi implements QueueApi {

    private final HostScope scope;
    private final QueueService queues;

    NodeQueueApi(HostScope scope, QueueService queues) {
        this.scope = scope;
        this.queues = queues;
    }

    @Override
    public Future<String> push(String queue, Object message, Long delayMs, String key, Integer maxAttempts) {
        return available().compose(v -> queues.push(scope.tenant(), queue, message, delayMs, key, maxAttempts));
    }

    @Override
    public Future<Registration> consume(String queue, int concurrency, Consumer<Inbound> listener) {
        return Names.guarded(() -> {
            QueueService service = service();
            QueueService.name(queue);
            if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
                throw HostException.invalid("\"concurrency\" must be between 1 and " + MAX_CONCURRENCY);
            }
            return QueueConsumer.start(service, scope.tenant(), queue, concurrency, delivery -> listener.accept(inbound(delivery)))
                    .map(consumer -> scope.track(consumer::stop));
        });
    }

    @Override
    public Future<JsonObject> stats(String queue) {
        return available().compose(v -> queues.stats(scope.tenant(), queue));
    }

    @Override
    public Future<JsonObject> dead(String queue, int limit, String cursor) {
        return available().compose(v -> queues.dead(scope.tenant(), queue, limit, cursor));
    }

    @Override
    public Future<Boolean> retry(String queue, String id) {
        return available().compose(v -> Names.guarded(() -> queues.retry(scope.tenant(), QueueService.name(queue), String.valueOf(id))));
    }

    @Override
    public Future<Boolean> discard(String queue, String id) {
        return available().compose(v -> Names.guarded(() -> queues.discard(scope.tenant(), QueueService.name(queue), String.valueOf(id))));
    }

    private Future<Void> available() {
        return queues == null ? Future.failedFuture(new HostException("Error", "unavailable", "Queues are not available here")) : Future.succeededFuture();
    }

    private QueueService service() {
        if (queues == null) {
            throw new HostException("Error", "unavailable", "Queues are not available here");
        }
        return queues;
    }

    private static Inbound inbound(QueueConsumer.Delivery delivery) {
        return new Inbound() {
            @Override
            public Object payload() {
                return new JsonObject().put("queue", delivery.message().queue()).put("id", delivery.message().id())
                        .put("attempt", delivery.message().attempt()).put("enqueuedAt", delivery.message().enqueuedAt())
                        .put("message", delivery.message().body());
            }

            @Override
            public boolean expectsReply() {
                return true;
            }

            @Override
            public void reply(Object value) {
                delivery.ack();
            }

            @Override
            public void fail(int code, String message) {
                // 503: the script ended or stopped listening before handling it; that isn't the message's fault.
                if (code == 503) {
                    delivery.release();
                } else {
                    delivery.fail(message);
                }
            }
        };
    }
}
