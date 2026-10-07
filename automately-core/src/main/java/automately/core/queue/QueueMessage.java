package automately.core.queue;

import io.vertx.core.json.JsonObject;

/**
 * One message in a durable queue (D-061). {@code attempt} counts deliveries. While {@code IN_FLIGHT} it belongs to
 * {@code owner} until {@code deadline}; a later claim takes it back once the deadline has passed (the owner died or
 * stopped extending). {@code size} is what it counts against the tenant's quota.
 */
public record QueueMessage(String tenant, String queue, String id, Object body, Status status, int attempt, int maxAttempts,
                           long availableAt, long deadline, String owner, long enqueuedAt, String lastError, long size) {

    public enum Status { READY, IN_FLIGHT, DEAD }

    /** Whether a claim at {@code now} may take it: ready and due, or in flight past its deadline. */
    public boolean claimable(long now) {
        return (status == Status.READY && availableAt <= now) || (status == Status.IN_FLIGHT && deadline < now);
    }

    QueueMessage with(Status status, int attempt, long availableAt, long deadline, String owner, String lastError) {
        return new QueueMessage(tenant, queue, id, body, status, attempt, maxAttempts, availableAt, deadline, owner, enqueuedAt, lastError, size);
    }

    public JsonObject toJson() {
        return new JsonObject().put("tenant", tenant).put("queue", queue).put("id", id).put("body", body).put("status", status.name())
                .put("attempt", attempt).put("maxAttempts", maxAttempts).put("availableAt", availableAt).put("deadline", deadline)
                .put("owner", owner).put("enqueuedAt", enqueuedAt).put("lastError", lastError).put("size", size);
    }

    public static QueueMessage fromJson(JsonObject json) {
        return new QueueMessage(json.getString("tenant"), json.getString("queue"), json.getString("id"), json.getValue("body"),
                Status.valueOf(json.getString("status")), json.getInteger("attempt"), json.getInteger("maxAttempts"), json.getLong("availableAt"),
                json.getLong("deadline"), json.getString("owner"), json.getLong("enqueuedAt"), json.getString("lastError"), json.getLong("size"));
    }

    /** What scripts and the API see of a message. */
    public JsonObject toView() {
        return new JsonObject().put("id", id).put("queue", queue).put("message", body).put("status", status.name()).put("attempt", attempt)
                .put("maxAttempts", maxAttempts).put("enqueuedAt", enqueuedAt).put("availableAt", availableAt).put("lastError", lastError);
    }
}
