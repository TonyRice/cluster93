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
 * @param timeoutMs       wall-clock limit per attempt; 0 means none (service runs)
 * @param attempt         the current attempt, starting at 1. It is the fencing epoch.
 * @param node            the node running (or that last ran) the current attempt
 * @param nodeInstance    the process incarnation of {@code node}; a restarted node gets a new one
 * @param cancelRequested why someone asked to cancel or stop the job, or null. Durable, so the node and
 *                        the reaper both honour it even if the cancel message to the node is lost.
 * @param service         set when the job is a run of a service
 * @param attempts        history of attempts that have ended, oldest first
 * @param queuedAt        when the current attempt was (re)queued, used to re-dispatch jobs no node picked up
 * @param output          the JSON value the script returned
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
        String cancelRequested,
        ServiceRef service,
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
        return queued(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, null, now);
    }

    public static Job queued(String id, String tenant, String script, JsonObject input, long timeoutMs,
                             OrphanPolicy onOrphan, int maxAttempts, ServiceRef service, long now) {
        return new Job(id, tenant, script, input, timeoutMs, onOrphan, maxAttempts, JobStatus.QUEUED, 1,
                null, null, null, service, null, null, List.of(), List.of(), now, now, null, null);
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
        return Optional.of(edit().status(JobStatus.RUNNING).node(node, nodeInstance).logs(List.of()).startedAt(now).build());
    }

    /** The attempt ended. Rejected unless that attempt, on that node instance, is still current. */
    public Optional<Job> finished(int attempt, String nodeInstance, JobStatus outcome, Object output, String error,
                                  List<String> logs, long now) {
        if (!outcome.isTerminal() || outcome == JobStatus.ORPHANED) {
            throw new IllegalArgumentException("Not a normal end of an attempt: " + outcome);
        }
        if (!isCurrent(attempt, nodeInstance)) {
            return Optional.empty();
        }
        return Optional.of(edit().status(outcome).output(output).error(error).logs(logs)
                .attempts(withHistory(outcome, now)).finishedAt(now).build());
    }

    /** No node could take the job when it was submitted. */
    public Optional<Job> rejected(String error, long now) {
        if (status != JobStatus.QUEUED || attempt != 1) {
            return Optional.empty();
        }
        return Optional.of(edit().status(JobStatus.FAILED).error(error).finishedAt(now).build());
    }

    /**
     * Someone asks to cancel the job. A queued job is cancelled at once; a running one is marked, and its
     * node stops it. Does not apply to a finished job or one already being cancelled.
     */
    public Optional<Job> cancelRequested(String reason, long now) {
        if (status == JobStatus.QUEUED) {
            return Optional.of(edit().status(JobStatus.CANCELLED).cancelRequested(reason)
                    .error("Cancelled: " + reason).finishedAt(now).build());
        }
        if (status != JobStatus.RUNNING || cancelRequested != null) {
            return Optional.empty();
        }
        return Optional.of(edit().cancelRequested(reason).build());
    }

    /**
     * The node running {@code attempt} disappeared. A job with a pending cancel ends as
     * {@link JobStatus#CANCELLED}. Otherwise, depending on the policy and the attempts left, it either
     * ends as {@link JobStatus#ORPHANED} or is queued again as the next attempt.
     */
    public Optional<Job> orphaned(int attempt, String nodeInstance, long now) {
        if (!isCurrent(attempt, nodeInstance)) {
            return Optional.empty();
        }
        List<Attempt> history = withHistory(JobStatus.ORPHANED, now);
        if (cancelRequested != null) {
            return Optional.of(edit().status(JobStatus.CANCELLED).attempts(history).finishedAt(now)
                    .error("Cancelled: " + cancelRequested + " (node " + node + " disappeared before it stopped)").build());
        }
        if (onOrphan == OrphanPolicy.FAIL || attempt >= maxAttempts) {
            String reason = onOrphan == OrphanPolicy.FAIL
                    ? "Node " + node + " disappeared while running attempt " + attempt + "; onOrphan is \"fail\""
                    : "Node " + node + " disappeared while running attempt " + attempt + "; no attempts left (max " + maxAttempts + ")";
            return Optional.of(edit().status(JobStatus.ORPHANED).error(reason).attempts(history).finishedAt(now).build());
        }
        return Optional.of(edit().status(JobStatus.QUEUED).attempt(attempt + 1).node(null, null).output(null).error(null)
                .logs(List.of()).attempts(history).queuedAt(now).startedAt(null).build());
    }

    /**
     * Re-queues an attempt that no node picked up. Always safe, whatever the policy, because a node
     * records {@link JobStatus#RUNNING} before it runs any script.
     */
    public Optional<Job> requeued(int attempt, long now) {
        if (status != JobStatus.QUEUED || this.attempt != attempt) {
            return Optional.empty();
        }
        return Optional.of(edit().node(null, null).queuedAt(now).build());
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
                .put("cancelRequested", cancelRequested)
                .put("service", service == null ? null : service.toJson())
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
                json.getString("cancelRequested"),
                ServiceRef.fromJson(json.getJsonObject("service")),
                json.getValue("output"),
                json.getString("error"),
                json.getJsonArray("logs", new JsonArray()).stream().map(String::valueOf).toList(),
                json.getJsonArray("attempts", new JsonArray()).stream().map(a -> Attempt.fromJson((JsonObject) a)).toList(),
                json.getLong("createdAt", 0L),
                json.getLong("queuedAt", json.getLong("createdAt", 0L)),
                json.getLong("startedAt"),
                json.getLong("finishedAt"));
    }

    /** Starts a modified copy; used by transitions and tests. */
    Editor edit() {
        return new Editor(this);
    }

    /** A mutable copy of a job, so each transition names only the fields it changes. */
    static final class Editor {

        private final Job base;
        private JobStatus status;
        private int attempt;
        private String node;
        private String nodeInstance;
        private String cancelRequested;
        private Object output;
        private String error;
        private List<String> logs;
        private List<Attempt> attempts;
        private long queuedAt;
        private Long startedAt;
        private Long finishedAt;

        private Editor(Job base) {
            this.base = base;
            this.status = base.status;
            this.attempt = base.attempt;
            this.node = base.node;
            this.nodeInstance = base.nodeInstance;
            this.cancelRequested = base.cancelRequested;
            this.output = base.output;
            this.error = base.error;
            this.logs = base.logs;
            this.attempts = base.attempts;
            this.queuedAt = base.queuedAt;
            this.startedAt = base.startedAt;
            this.finishedAt = base.finishedAt;
        }

        Editor status(JobStatus value) { status = value; return this; }
        Editor attempt(int value) { attempt = value; return this; }
        Editor node(String id, String instance) { node = id; nodeInstance = instance; return this; }
        Editor cancelRequested(String value) { cancelRequested = value; return this; }
        Editor output(Object value) { output = value; return this; }
        Editor error(String value) { error = value; return this; }
        Editor logs(List<String> value) { logs = value; return this; }
        Editor attempts(List<Attempt> value) { attempts = value; return this; }
        Editor queuedAt(long value) { queuedAt = value; return this; }
        Editor startedAt(Long value) { startedAt = value; return this; }
        Editor finishedAt(Long value) { finishedAt = value; return this; }

        Job build() {
            return new Job(base.id, base.tenant, base.script, base.input, base.timeoutMs, base.onOrphan, base.maxAttempts, status,
                    attempt, node, nodeInstance, cancelRequested, base.service, output, error, logs, attempts, base.createdAt,
                    queuedAt, startedAt, finishedAt);
        }
    }
}
