package cluster93.cluster.ignite.store;

import cluster93.core.queue.QueueMessage;
import cluster93.core.queue.QueueStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Queues in a durable, replicated table (D-061), indexed by (tenant, queue, status, due), where "due" is when a claim
 * may take the message: {@code availableAt} while ready, the lease's deadline while in flight. Claiming is an index
 * range scan plus compare-and-set on the row.
 */
public final class IgniteQueueStore implements QueueStore {

    static final Table.Definition TABLE = new Table.Definition("QUEUE",
            Table.columns("tenant", String.class, "queue", String.class, "id", String.class, "status", String.class, "due", Long.class,
                    "size", Long.class),
            List.of(Table.index("tenant", "queue", "status", "due"), Table.index("tenant", "queue", "status", "id")));
    static final char SEPARATOR = '\u001f';

    private final Table table;

    public IgniteQueueStore(Vertx vertx, Ignite ignite) {
        this.table = new Table(vertx, ignite, TABLE, json -> {
            QueueMessage message = QueueMessage.fromJson(json);
            Map<String, Object> columns = new HashMap<>();
            columns.put("tenant", message.tenant());
            columns.put("queue", message.queue());
            columns.put("id", message.id());
            columns.put("status", message.status().name());
            columns.put("due", message.status() == QueueMessage.Status.IN_FLIGHT ? message.deadline() : message.availableAt());
            columns.put("size", message.size());
            return columns;
        });
    }

    static String key(String tenant, String queue, String id) {
        return tenant + SEPARATOR + queue + SEPARATOR + id;
    }

    @Override
    public Future<Boolean> add(QueueMessage message) {
        return table.putIfAbsent(key(message.tenant(), message.queue(), message.id()), message.toJson());
    }

    @Override
    public Future<QueueMessage> get(String tenant, String queue, String id) {
        return table.get(key(tenant, queue, id)).map(json -> json == null ? null : QueueMessage.fromJson(json));
    }

    @Override
    public Future<Optional<QueueMessage>> update(String tenant, String queue, String id, Function<QueueMessage, Optional<QueueMessage>> change) {
        return table.update(key(tenant, queue, id), json -> change.apply(QueueMessage.fromJson(json)).map(QueueMessage::toJson))
                .map(updated -> updated.map(QueueMessage::fromJson));
    }

    @Override
    public Future<Boolean> removeIfUnchanged(QueueMessage expected) {
        return table.removeIfUnchanged(key(expected.tenant(), expected.queue(), expected.id()), expected.toJson());
    }

    @Override
    public Future<List<QueueMessage>> claimable(String tenant, String queue, long now, int limit) {
        // Ready and due, or in flight past the lease (the latter is the rare case: a consumer died).
        return table.queryJson("SELECT json FROM QUEUE WHERE tenant = ? AND queue = ? AND status = 'READY' AND due <= ? ORDER BY due LIMIT ?",
                        tenant, queue, now, limit)
                .compose(ready -> table.queryJson("SELECT json FROM QUEUE WHERE tenant = ? AND queue = ? AND status = 'IN_FLIGHT' AND due < ? "
                                + "ORDER BY due LIMIT ?", tenant, queue, now, limit)
                        .map(expired -> {
                            List<QueueMessage> all = new java.util.ArrayList<>();
                            ready.forEach(json -> all.add(QueueMessage.fromJson(json)));
                            expired.forEach(json -> all.add(QueueMessage.fromJson(json)));
                            return all.size() > limit ? all.subList(0, limit) : all;
                        }));
    }

    @Override
    public Future<JsonObject> counts(String tenant, String queue) {
        return table.query("SELECT status, COUNT(*) FROM QUEUE WHERE tenant = ? AND queue = ? GROUP BY status", tenant, queue).map(rows -> {
            JsonObject counts = new JsonObject().put("ready", 0L).put("inFlight", 0L).put("dead", 0L);
            for (List<?> row : rows) {
                long count = ((Number) row.get(1)).longValue();
                switch (QueueMessage.Status.valueOf((String) row.get(0))) {
                    case READY -> counts.put("ready", count);
                    case IN_FLIGHT -> counts.put("inFlight", count);
                    case DEAD -> counts.put("dead", count);
                }
            }
            return counts;
        });
    }

    @Override
    public Future<List<QueueMessage>> dead(String tenant, String queue, int limit, String cursor) {
        return (cursor == null
                ? table.queryJson("SELECT json FROM QUEUE WHERE tenant = ? AND queue = ? AND status = 'DEAD' ORDER BY id LIMIT ?", tenant, queue, limit)
                : table.queryJson("SELECT json FROM QUEUE WHERE tenant = ? AND queue = ? AND status = 'DEAD' AND id > ? ORDER BY id LIMIT ?",
                tenant, queue, cursor, limit))
                .map(rows -> rows.stream().map(QueueMessage::fromJson).toList());
    }

    @Override
    public Future<Long> usage(String tenant) {
        return table.query("SELECT COALESCE(SUM(size), 0) FROM QUEUE WHERE tenant = ?", tenant)
                .map(rows -> ((Number) rows.getFirst().getFirst()).longValue());
    }
}
