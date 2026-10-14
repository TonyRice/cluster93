package cluster93.core.runtime.api;

import cluster93.core.runtime.Inbound;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.function.Consumer;

/**
 * Durable queues, the bus's second mode ({@code bus.queue(name)}, D-061): at-least-once delivery to competing
 * consumers, retries with backoff, and a dead-letter list.
 */
public interface QueueApi {

    int MAX_CONCURRENCY = 32;

    /**
     * Queues a message; completes with its id.
     *
     * @param delayMs     null to deliver as soon as possible
     * @param key         null, or a key that makes the push idempotent while a message with it is queued
     * @param maxAttempts null for the default (5)
     */
    Future<String> push(String queue, Object message, Long delayMs, String key, Integer maxAttempts);

    /**
     * Delivers messages to {@code listener}, at most {@code concurrency} at a time. Each arrives as an {@link Inbound}
     * whose payload is {@code {queue, id, attempt, enqueuedAt, message}}; {@code reply} acknowledges it, {@code fail}
     * retries it later (status 503 gives it back untried).
     */
    Future<Registration> consume(String queue, int concurrency, Consumer<Inbound> listener);

    /** {@code {ready, inFlight, dead}}. */
    Future<JsonObject> stats(String queue);

    /** {@code {messages: [...], cursor}}. */
    Future<JsonObject> dead(String queue, int limit, String cursor);

    /** Puts a dead-lettered message back. False if there's no such dead message. */
    Future<Boolean> retry(String queue, String id);

    /** Deletes a dead-lettered message. False if there's no such dead message. */
    Future<Boolean> discard(String queue, String id);
}
