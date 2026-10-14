package cluster93.core.queue;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where queued messages live (D-061): durable and replicated on Ignite, in memory for a single node and tests. Only the
 * primitives; claiming, acknowledging and retrying are {@link QueueService}'s, the same over every store.
 */
public interface QueueStore {

    /** Stores a new message; false if one with its id already exists (a deduplicating key). */
    Future<Boolean> add(QueueMessage message);

    Future<QueueMessage> get(String tenant, String queue, String id);

    /** Compare-and-set, as in the job store (D-011): empty if the message is gone or {@code change} doesn't apply. */
    Future<Optional<QueueMessage>> update(String tenant, String queue, String id, Function<QueueMessage, Optional<QueueMessage>> change);

    /** Removes the message only if it's still exactly {@code expected}. */
    Future<Boolean> removeIfUnchanged(QueueMessage expected);

    /** Up to {@code limit} messages a claim at {@code now} may take ({@link QueueMessage#claimable}), oldest due first. */
    Future<List<QueueMessage>> claimable(String tenant, String queue, long now, int limit);

    /** {@code {ready, inFlight, dead}}; ready includes delayed messages. */
    Future<JsonObject> counts(String tenant, String queue);

    /** Dead-lettered messages in id order after {@code cursor}. */
    Future<List<QueueMessage>> dead(String tenant, String queue, int limit, String cursor);

    /** Bytes the tenant's messages take; counted against its storage quota (D-043). */
    Future<Long> usage(String tenant);
}
