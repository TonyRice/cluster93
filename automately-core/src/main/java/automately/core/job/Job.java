package automately.core.job;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable snapshot of a job. Jobs travel across the cluster as JSON, never as Java serialization.
 *
 * <p>Every state change is a transition method that returns {@link Optional#empty()} when it does not
 * apply, most importantly when it names an attempt (or node instance) that is no longer current. This
 * is the fencing that stops a node that only looked dead from overwriting the attempt that replaced it
 * (see docs/design/orphaned-jobs.md). Transitions are applied with {@link JobStore#update}.
 *
 * @param attempt      the current attempt, starting at 1. It is the fencing epoch.
 * @param node         the node running (or that last ran) the current attempt
 * @param nodeInstance the process incarnation of {@code node}; a restarted node gets a new one
 * @param attempts     history of attempts that have ended, oldest first
 * @param queuedAt     when the current attempt was (re)queued, used to re-dispatch jobs no node picked up
 * @param output       the JSON value the script returned
 */
public record Job(
        String id,
        String tenant,
        String script,
        JsonObject input,
        long timeoutMs,
        OrphanPolicy onOrphan,
        int maxAttempts,
        JobStatus status,
        int attempt,
        String node,
        String nodeInstance,
        Object output,
        String error,
        List<String> logs,
        List<Attempt> attempts,
        long createdAt,
        long queuedAt,
        Long startedAt,
        Long finishedAt) {

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(onOrphan, "onOrphan");
        Objects.requireNonNull(status, "status");
        input = input == null ? new JsonObject() : input;
        logs = logs == null ? List.of() : List.copyOf(logs);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public static Job queued(String id, String tenant, String script, JsonObject input, long timeoutMs,
                             OrphanPolicy onOrphan, int maxAttempts, long now) {
        return new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, JobStatus.QUEUED, 1,
                null, null, null, null, List.of(), List.of(), now, now, null, null);
    }

    /** True when {@code attempt} on {@code nodeInstance} is still the one that owns this job. */
    public boolean isCurrent(int attempt, String nodeInstance) {
        return status == JobStatus.RUNNING && this.attempt == attempt && Objects.equals(this.nodeInstance, nodeInstance);
    }

    /** The attempt before the current one, or null on the first attempt. */
    public Attempt previousAttempt() {
        return attempts.isEmpty() ? null : attempts.getLast();
    }

    /** A node claims the queued attempt. Fails if the attempt moved on or another node already claimed it. */
    public Optional<Job> started(int attempt, String node, String nodeInstance, long now) {
        if (status != JobStatus.QUEUED || this.attempt != attempt) {
            return Optional.empty();
        }
        return Optional.of(new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, JobStatus.RUNNING, attempt,
                node, nodeInstance, null, null, List.of(), attempts, createdAt, queuedAt, now, null));
    }

    /** The attempt ended normally. Rejected unless that attempt, on that node instance, is still current. */
    public Optional<Job> finished(int attempt, String nodeInstance, JobStatus outcome, Object output, String error,
                                  List<String> logs, long now) {
        if (!outcome.isTerminal() || outcome == JobStatus.ORPHANED) {
            throw new IllegalArgumentException("Not a normal end of an attempt: " + outcome);
        }
        if (!isCurrent(attempt, nodeInstance)) {
            return Optional.empty();
        }
        return Optional.of(new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, outcome, attempt,
                node, nodeInstance, output, error, logs, withHistory(outcome, now), createdAt, queuedAt, startedAt, now));
    }

    /** No node could take the job when it was submitted. */
    public Optional<Job> rejected(String error, long now) {
        if (status != JobStatus.QUEUED || attempt != 1) {
            return Optional.empty();
        }
        return Optional.of(new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, JobStatus.FAILED, attempt,
                null, null, null, error, List.of(), attempts, createdAt, queuedAt, null, now));
    }

    /**
     * The node running {@code attempt} disappeared. Depending on the policy and the attempts left, the
     * job either ends as {@link JobStatus#ORPHANED} or is queued again as the next attempt.
     */
    public Optional<Job> orphaned(int attempt, String nodeInstance, long now) {
        if (!isCurrent(attempt, nodeInstance)) {
            return Optional.empty();
        }
        List<Attempt> history = withHistory(JobStatus.ORPHANED, now);
        if (onOrphan == OrphanPolicy.FAIL || attempt >= maxAttempts) {
            String reason = onOrphan == OrphanPolicy.FAIL
                    ? "Node " + node + " disappeared while running attempt " + attempt + "; onOrphan is \"fail\""
                    : "Node " + node + " disappeared while running attempt " + attempt + "; no attempts left (max " + maxAttempts + ")";
            return Optional.of(new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, JobStatus.ORPHANED, attempt,
                    node, nodeInstance, null, reason, logs, history, createdAt, queuedAt, startedAt, now));
        }
        return Optional.of(new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, JobStatus.QUEUED, attempt + 1,
                null, null, null, null, List.of(), history, createdAt, now, null, null));
    }

    /**
     * Re-queues an attempt that no node picked up. Always safe, whatever the policy, because a node
     * records {@link JobStatus#RUNNING} before it runs any script.
     */
    public Optional<Job> requeued(int attempt, long now) {
        if (status != JobStatus.QUEUED || this.attempt != attempt) {
            return Optional.empty();
        }
        return Optional.of(new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, status, attempt,
                null, null, null, null, List.of(), attempts, createdAt, now, null, null));
    }

    private List<Attempt> withHistory(JobStatus outcome, long now) {
        List<Attempt> history = new ArrayList<>(attempts);
        history.add(new Attempt(attempt, node, nodeInstance, startedAt, now, outcome));
        return history;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("tenant", tenant)
                .put("script", script)
                .put("input", input)
                .put("timeoutMs", timeoutMs)
                .put("onOrphan", onOrphan.apiName())
                .put("maxAttempts", maxAttempts)
                .put("status", status.name())
                .put("attempt", attempt)
                .put("node", node)
                .put("nodeInstance", nodeInstance)
                .put("output", output)
                .put("error", error)
                .put("logs", new JsonArray(logs))
                .put("attempts", new JsonArray(attempts.stream().map(Attempt::toJson).toList()))
                .put("createdAt", createdAt)
                .put("queuedAt", queuedAt)
                .put("startedAt", startedAt)
                .put("finishedAt", finishedAt);
    }

    /** The public view of a job, without the script source or internal node instance ids. */
    public JsonObject toSummaryJson() {
        JsonObject json = toJson();
        json.remove("script");
        json.remove("nodeInstance");
        json.getJsonArray("attempts").forEach(a -> ((JsonObject) a).remove("nodeInstance"));
        return json;
    }

    public static Job fromJson(JsonObject json) {
        return new Job(
                json.getString("id"),
                json.getString("tenant"),
                json.getString("script"),
                json.getJsonObject("input"),
                json.getLong("timeoutMs", 0L),
                OrphanPolicy.fromApi(json.getString("onOrphan", "fail")),
                json.getInteger("maxAttempts", 1),
                JobStatus.valueOf(json.getString("status")),
                json.getInteger("attempt", 1),
                json.getString("node"),
                json.getString("nodeInstance"),
                json.getValue("output"),
                json.getString("error"),
                json.getJsonArray("logs", new JsonArray()).stream().map(String::valueOf).toList(),
                json.getJsonArray("attempts", new JsonArray()).stream().map(a -> Attempt.fromJson((JsonObject) a)).toList(),
                json.getLong("createdAt", 0L),
                json.getLong("queuedAt", json.getLong("createdAt", 0L)),
                json.getLong("startedAt"),
                json.getLong("finishedAt"));
    }
}
