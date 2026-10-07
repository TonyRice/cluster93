package automately.core.queue;

import automately.core.files.StorageCapacity;
import automately.core.host.Addresses;
import automately.core.runtime.HostException;
import automately.core.runtime.api.BusApi;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Durable queues (D-061): at-least-once delivery to competing consumers, with leases, retries with backoff and a
 * dead-letter list. The rules live here, over any {@link QueueStore}:
 * <ul>
 *   <li>A claim moves a due message to {@code IN_FLIGHT} with an owner and a lease, by compare-and-set, so one consumer
 *   gets it. It also takes back messages whose lease ran out (their consumer died or stopped extending).</li>
 *   <li>An ack removes the message, only if the caller still owns that delivery; a late ack after a lease ran out does
 *   nothing (the message was, or will be, delivered again).</li>
 *   <li>A failure puts it back after a backoff, or dead-letters it once its attempts are used up.</li>
 * </ul>
 */
public final class QueueService {

    /** The tenant that owns the system's own queues, such as completion callbacks. Never a real tenant's name. */
    public static final String SYSTEM_TENANT = "@system";
    public static final int MAX_MESSAGE_BYTES = 256 * 1024;
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    public static final int MAX_ATTEMPTS = 20;
    public static final long MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1000;
    public static final long LEASE_MS = 30_000;
    public static final int MAX_LIST_LIMIT = 1_000;
    static final long MAX_BACKOFF_MS = 10 * 60 * 1000;
    static final int CANDIDATES = 8;

    private final Vertx vertx;
    private final QueueStore store;
    private final TenantQuota quota;
    private final StorageCapacity capacity;
    private final long leaseMs;

    public QueueService(Vertx vertx, QueueStore store, TenantQuota quota, StorageCapacity capacity) {
        this(vertx, store, quota, capacity, LEASE_MS);
    }

    /** With a lease other than {@value #LEASE_MS} ms: tests use a short one to show expiry. */
    public QueueService(Vertx vertx, QueueStore store, TenantQuota quota, StorageCapacity capacity, long leaseMs) {
        this.vertx = vertx;
        this.store = store;
        this.quota = quota;
        this.capacity = capacity;
        this.leaseMs = leaseMs;
    }

    public Vertx vertx() {
        return vertx;
    }

    long leaseMs() {
        return leaseMs;
    }

    /** Wait before delivery {@code attempt + 1} after delivery {@code attempt} failed: 1 s, 4 s, 16 s, … up to 10 min. */
    static long backoff(int attempt) {
        long ms = 1000L << Math.min(2L * Math.max(0, attempt - 1), 20);
        return Math.min(ms, MAX_BACKOFF_MS);
    }

    public static String name(String queue) {
        if (queue == null || !queue.matches(BusApi.TOPIC_PATTERN)) {
            throw HostException.invalid("A queue name must match " + BusApi.TOPIC_PATTERN);
        }
        return queue;
    }

    /**
     * Stores a message and wakes the queue's consumers. With a {@code key}, a message with the same key that is still
     * queued makes this a no-op: the push is idempotent. Completes with the message's id.
     */
    public Future<String> push(String tenant, String queue, Object body, Long delayMs, String key, Integer maxAttempts) {
        try {
            name(queue);
            if (delayMs != null && (delayMs < 0 || delayMs > MAX_DELAY_MS)) {
                throw HostException.invalid("\"delayMs\" must be between 0 and " + MAX_DELAY_MS);
            }
            if (maxAttempts != null && (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS)) {
                throw HostException.invalid("\"maxAttempts\" must be between 1 and " + MAX_ATTEMPTS);
            }
            if (key != null && (key.isEmpty() || !key.matches(BusApi.TOPIC_PATTERN))) {
                throw HostException.invalid("A key must match " + BusApi.TOPIC_PATTERN);
            }
            long size = Json.encode(body).getBytes(StandardCharsets.UTF_8).length;
            if (size > MAX_MESSAGE_BYTES) {
                throw HostException.limit("The message is over " + MAX_MESSAGE_BYTES + " bytes of JSON");
            }
            long now = System.currentTimeMillis();
            String id = key != null ? "k-" + key : newId(now);
            QueueMessage message = new QueueMessage(tenant, queue, id, body, QueueMessage.Status.READY, 0,
                    maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts, now + (delayMs == null ? 0 : delayMs), 0, null, now, null,
                    size + queue.length() + id.length());
            Future<Void> room = tenant.equals(SYSTEM_TENANT) ? capacity.ensureRoom()
                    : capacity.ensureRoom().compose(v -> quota.ensureFits(tenant, message.size()));
            return room.compose(v -> store.add(message)).map(added -> {
                if (added) {
                    metrics().queueMessage("pushed");
                    hint(tenant, queue);
                }
                return id;
            });
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
    }

    /** Time-ordered, so messages pushed together come out roughly in order. */
    private static String newId(long now) {
        return String.format("%013d-%s", now, UUID.randomUUID().toString().substring(0, 8));
    }

    private automately.core.metrics.Metrics metrics() {
        return automately.core.metrics.Metrics.of(vertx);
    }

    void hint(String tenant, String queue) {
        vertx.eventBus().publish(Addresses.queueHint(tenant, queue), queue);
    }

    /**
     * Claims one due message for {@code owner}, or empty if none is due. Candidates are tried in a random order so
     * consumers racing for the same queue mostly don't collide. A message whose last lease ran out with no attempts left
     * is dead-lettered on the way.
     */
    public Future<Optional<QueueMessage>> claim(String tenant, String queue, String owner) {
        long now = System.currentTimeMillis();
        return store.claimable(tenant, queue, now, CANDIDATES).compose(candidates -> {
            List<QueueMessage> order = new ArrayList<>(candidates);
            Collections.shuffle(order, ThreadLocalRandom.current());
            return claimFirst(order, 0, owner, now);
        });
    }

    private Future<Optional<QueueMessage>> claimFirst(List<QueueMessage> candidates, int index, String owner, long now) {
        if (index >= candidates.size()) {
            return Future.succeededFuture(Optional.empty());
        }
        QueueMessage candidate = candidates.get(index);
        boolean[] deadLettered = {false};
        boolean[] reclaimed = {false};
        return store.update(candidate.tenant(), candidate.queue(), candidate.id(), current -> {
            // Compare-and-set may run this more than once; only the last run counts.
            deadLettered[0] = false;
            reclaimed[0] = current.status() == QueueMessage.Status.IN_FLIGHT;
            if (!current.claimable(now)) {
                return Optional.empty();
            }
            if (current.status() == QueueMessage.Status.IN_FLIGHT && current.attempt() >= current.maxAttempts()) {
                deadLettered[0] = true;
                return Optional.of(current.with(QueueMessage.Status.DEAD, current.attempt(), current.availableAt(), 0, null,
                        "The consumer's lease ran out on the last attempt"));
            }
            return Optional.of(current.with(QueueMessage.Status.IN_FLIGHT, current.attempt() + 1, current.availableAt(), now + leaseMs,
                    owner, current.lastError()));
        }).compose(updated -> {
            if (updated.isPresent()) {
                metrics().queueMessage(deadLettered[0] ? "dead" : reclaimed[0] ? "reclaimed" : "claimed");
            }
            return updated.isPresent() && !deadLettered[0] ? Future.succeededFuture(updated) : claimFirst(candidates, index + 1, owner, now);
        });
    }

    private static boolean owns(QueueMessage current, QueueMessage claimed) {
        return current.status() == QueueMessage.Status.IN_FLIGHT && claimed.owner().equals(current.owner())
                && current.attempt() == claimed.attempt();
    }

    /** Done with it: removes the message if this delivery still owns it. Completes with whether it did. */
    public Future<Boolean> ack(QueueMessage claimed) {
        return store.get(claimed.tenant(), claimed.queue(), claimed.id())
                .compose(current -> current != null && owns(current, claimed) ? store.removeIfUnchanged(current) : Future.succeededFuture(false))
                .onSuccess(removed -> {
                    if (removed) {
                        metrics().queueMessage("acked");
                    }
                });
    }

    /**
     * The delivery failed: back to the queue after a backoff, or dead-lettered once its attempts are used up. With
     * {@code release}, the consumer is giving the message back without having tried it (its script ended): it's due again
     * at once and the delivery doesn't count.
     */
    public Future<Boolean> fail(QueueMessage claimed, String error, boolean release) {
        long now = System.currentTimeMillis();
        String reason = error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error;
        return store.update(claimed.tenant(), claimed.queue(), claimed.id(), current -> {
            if (!owns(current, claimed)) {
                return Optional.empty();
            }
            if (release) {
                return Optional.of(current.with(QueueMessage.Status.READY, current.attempt() - 1, now, 0, null, current.lastError()));
            }
            if (current.attempt() >= current.maxAttempts()) {
                return Optional.of(current.with(QueueMessage.Status.DEAD, current.attempt(), current.availableAt(), 0, null, reason));
            }
            return Optional.of(current.with(QueueMessage.Status.READY, current.attempt(), now + backoff(current.attempt()), 0, null, reason));
        }).map(updated -> {
            updated.ifPresent(m -> metrics().queueMessage(release ? "released" : m.status() == QueueMessage.Status.DEAD ? "dead" : "retried"));
            if (updated.isPresent() && updated.get().status() == QueueMessage.Status.READY) {
                // Delayed retries are found by the consumers' periodic look; an immediate one wakes them now.
                if (release) {
                    hint(claimed.tenant(), claimed.queue());
                }
            }
            return updated.isPresent();
        });
    }

    /** Extends a delivery's lease while its handler is still working. False if it no longer owns the message. */
    public Future<Boolean> extend(QueueMessage claimed) {
        long now = System.currentTimeMillis();
        return store.update(claimed.tenant(), claimed.queue(), claimed.id(), current -> owns(current, claimed)
                        ? Optional.of(current.with(current.status(), current.attempt(), current.availableAt(), now + leaseMs, current.owner(),
                        current.lastError()))
                        : Optional.empty())
                .map(Optional::isPresent);
    }

    /** {@code {ready, inFlight, dead}}. */
    public Future<JsonObject> stats(String tenant, String queue) {
        try {
            return store.counts(tenant, name(queue));
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
    }

    /** Dead-lettered messages: {@code {messages: [...], cursor}}, cursor null on the last page. */
    public Future<JsonObject> dead(String tenant, String queue, int limit, String cursor) {
        try {
            name(queue);
            if (limit < 1 || limit > MAX_LIST_LIMIT) {
                throw HostException.invalid("\"limit\" must be between 1 and " + MAX_LIST_LIMIT);
            }
            return store.dead(tenant, queue, limit + 1, cursor).map(found -> {
                boolean more = found.size() > limit;
                List<QueueMessage> page = more ? found.subList(0, limit) : found;
                return new JsonObject().put("messages", new io.vertx.core.json.JsonArray(page.stream().map(QueueMessage::toView).toList()))
                        .put("cursor", more ? page.getLast().id() : null);
            });
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
    }

    /** Puts a dead-lettered message back, with its attempts reset. False if there's no such dead message. */
    public Future<Boolean> retry(String tenant, String queue, String id) {
        long now = System.currentTimeMillis();
        return store.update(tenant, queue, id, current -> current.status() == QueueMessage.Status.DEAD
                        ? Optional.of(current.with(QueueMessage.Status.READY, 0, now, 0, null, current.lastError()))
                        : Optional.empty())
                .map(updated -> {
                    updated.ifPresent(m -> hint(tenant, queue));
                    return updated.isPresent();
                });
    }

    /** Deletes a dead-lettered message for good. False if there's no such dead message. */
    public Future<Boolean> discard(String tenant, String queue, String id) {
        return store.get(tenant, queue, id).compose(current -> current != null && current.status() == QueueMessage.Status.DEAD
                ? store.removeIfUnchanged(current)
                : Future.succeededFuture(false));
    }
}
