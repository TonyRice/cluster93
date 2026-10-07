package automately.core.queue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Queues in a Vert.x {@link AsyncMap}, for a single in-memory node and tests. Queries are full scans; the Ignite store
 * uses an index. Compare-and-set is {@link AsyncMap#replaceIfPresent} (D-011).
 */
public final class SharedDataQueueStore implements QueueStore {

    static final String MAP_NAME = "automately.queues";
    static final int MAX_UPDATE_ATTEMPTS = 20;
    static final char SEPARATOR = '\u001f';

    private final Future<AsyncMap<String, JsonObject>> map;

    public SharedDataQueueStore(Vertx vertx) {
        this.map = vertx.sharedData().getAsyncMap(MAP_NAME);
    }

    static String key(String tenant, String queue, String id) {
        return tenant + SEPARATOR + queue + SEPARATOR + id;
    }

    @Override
    public Future<Boolean> add(QueueMessage message) {
        return map.compose(m -> m.putIfAbsent(key(message.tenant(), message.queue(), message.id()), message.toJson())).map(previous -> previous == null);
    }

    @Override
    public Future<QueueMessage> get(String tenant, String queue, String id) {
        return map.compose(m -> m.get(key(tenant, queue, id))).map(json -> json == null ? null : QueueMessage.fromJson(json));
    }

    @Override
    public Future<Optional<QueueMessage>> update(String tenant, String queue, String id, Function<QueueMessage, Optional<QueueMessage>> change) {
        return map.compose(m -> update(m, key(tenant, queue, id), change, 1));
    }

    private Future<Optional<QueueMessage>> update(AsyncMap<String, JsonObject> m, String key, Function<QueueMessage, Optional<QueueMessage>> change,
                                                  int tries) {
        return m.get(key).compose(current -> {
            if (current == null) {
                return Future.succeededFuture(Optional.empty());
            }
            Optional<QueueMessage> next = change.apply(QueueMessage.fromJson(current));
            if (next.isEmpty()) {
                return Future.succeededFuture(Optional.empty());
            }
            return m.replaceIfPresent(key, current, next.get().toJson()).compose(replaced -> {
                if (replaced) {
                    return Future.succeededFuture(next);
                }
                if (tries >= MAX_UPDATE_ATTEMPTS) {
                    return Future.failedFuture("Gave up updating queue message " + key + " after " + tries + " conflicting writes");
                }
                return update(m, key, change, tries + 1);
            });
        });
    }

    @Override
    public Future<Boolean> removeIfUnchanged(QueueMessage expected) {
        return map.compose(m -> m.removeIfPresent(key(expected.tenant(), expected.queue(), expected.id()), expected.toJson()));
    }

    @Override
    public Future<List<QueueMessage>> claimable(String tenant, String queue, long now, int limit) {
        return in(tenant, queue).map(messages -> messages.stream()
                .filter(m -> m.claimable(now))
                .sorted(Comparator.comparingLong(QueueMessage::availableAt).thenComparing(QueueMessage::id))
                .limit(limit)
                .toList());
    }

    @Override
    public Future<JsonObject> counts(String tenant, String queue) {
        return in(tenant, queue).map(messages -> new JsonObject()
                .put("ready", messages.stream().filter(m -> m.status() == QueueMessage.Status.READY).count())
                .put("inFlight", messages.stream().filter(m -> m.status() == QueueMessage.Status.IN_FLIGHT).count())
                .put("dead", messages.stream().filter(m -> m.status() == QueueMessage.Status.DEAD).count()));
    }

    @Override
    public Future<List<QueueMessage>> dead(String tenant, String queue, int limit, String cursor) {
        return in(tenant, queue).map(messages -> messages.stream()
                .filter(m -> m.status() == QueueMessage.Status.DEAD && (cursor == null || m.id().compareTo(cursor) > 0))
                .sorted(Comparator.comparing(QueueMessage::id))
                .limit(limit)
                .toList());
    }

    @Override
    public Future<Long> usage(String tenant) {
        return all().map(messages -> messages.stream().filter(m -> m.tenant().equals(tenant)).mapToLong(QueueMessage::size).sum());
    }

    private Future<List<QueueMessage>> in(String tenant, String queue) {
        return all().map(messages -> messages.stream().filter(m -> m.tenant().equals(tenant) && m.queue().equals(queue)).toList());
    }

    private Future<List<QueueMessage>> all() {
        return map.compose(AsyncMap::values).map(values -> values.stream().map(QueueMessage::fromJson).toList());
    }
}
