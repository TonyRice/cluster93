package automately.core.service;

import automately.core.job.Job;
import automately.core.job.JobStatus;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A service: its desired state (script, config, enabled) and the supervisor's bookkeeping. Immutable;
 * changed only through {@link ServiceStore#update}. See docs/design/services.md.
 *
 * @param revision           bumped whenever script, config or restart changes
 * @param currentJobId       the run the cluster considers current, or null
 * @param currentRevision    the revision {@code currentJobId} runs
 * @param currentSince       when {@code currentJobId} was claimed (a run whose job never appears is given up after a while)
 * @param runs               how many runs have been started
 * @param consecutiveFailures failures in a row, reset by a run that stays up long enough
 * @param nextStartAt        no new run before this time (backoff)
 * @param lastExit           how the previous run ended: {@code jobId, status, error, startedAt, endedAt}
 */
public record ServiceDefinition(
        String tenant,
        String name,
        String script,
        JsonObject config,
        RestartPolicy restart,
        boolean enabled,
        boolean completed,
        int revision,
        String currentJobId,
        int currentRevision,
        long currentSince,
        int runs,
        int consecutiveFailures,
        long nextStartAt,
        JsonObject lastExit,
        long createdAt,
        long updatedAt) {

    public ServiceDefinition {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(restart, "restart");
        config = config == null ? new JsonObject() : config;
    }

    public static ServiceDefinition create(String tenant, String name, String script, JsonObject config, RestartPolicy restart,
                                           boolean enabled, long now) {
        return new ServiceDefinition(tenant, name, script, config, restart, enabled, false, 1, null, 0, 0, 0, 0, 0, null, now, now);
    }

    public String key() {
        return key(tenant, name);
    }

    public static String key(String tenant, String name) {
        return tenant + "/" + name;
    }

    /** New script, config or restart policy: a new revision, which the supervisor rolls out. */
    public ServiceDefinition redefined(String script, JsonObject config, RestartPolicy restart, long now) {
        boolean changed = !this.script.equals(script) || !this.config.equals(config) || this.restart != restart;
        return new ServiceDefinition(tenant, name, script, config, restart, enabled, changed ? false : completed,
                changed ? revision + 1 : revision, currentJobId, currentRevision, currentSince, runs,
                changed ? 0 : consecutiveFailures, changed ? 0 : nextStartAt, lastExit, createdAt, now);
    }

    /** Desired state: running or not. Enabling clears completion and backoff. */
    public ServiceDefinition withEnabled(boolean enabled, long now) {
        return new ServiceDefinition(tenant, name, script, config, restart, enabled, enabled ? false : completed, revision,
                currentJobId, currentRevision, currentSince, runs, enabled ? 0 : consecutiveFailures, enabled ? 0 : nextStartAt,
                lastExit, createdAt, now);
    }

    /** Claims a new run. Only applies when no run is current and a start is due. */
    public Optional<ServiceDefinition> startingRun(String jobId, long now) {
        if (currentJobId != null || !enabled || completed || now < nextStartAt) {
            return Optional.empty();
        }
        return Optional.of(new ServiceDefinition(tenant, name, script, config, restart, enabled, completed, revision,
                jobId, revision, now, runs + 1, consecutiveFailures, nextStartAt, lastExit, createdAt, now));
    }

    /**
     * Records how run {@code jobId} ended and decides when the next one may start. {@code job} is null if
     * the run's job never appeared. A cancelled run (stop, restart, new revision) is not a failure.
     */
    public Optional<ServiceDefinition> runEnded(String jobId, Job job, Duration stableAfter, Duration baseBackoff,
                                                Duration maxBackoff, long now) {
        if (!jobId.equals(currentJobId)) {
            return Optional.empty();
        }
        JobStatus status = job == null ? JobStatus.FAILED : job.status();
        JsonObject exit = new JsonObject()
                .put("jobId", jobId)
                .put("status", status.name())
                .put("error", job == null ? "The run's job was never created" : job.error())
                .put("startedAt", job == null ? null : job.startedAt())
                .put("endedAt", job == null ? now : job.finishedAt());

        boolean done = completed;
        int failures = consecutiveFailures;
        long next = now;
        if (status == JobStatus.CANCELLED) {
            next = now;
        } else if (status == JobStatus.SUCCEEDED) {
            if (restart == RestartPolicy.ON_FAILURE) {
                done = true;
            } else {
                failures = 0;
                next = now + baseBackoff.toMillis();
            }
        } else {
            long uptime = job == null || job.startedAt() == null || job.finishedAt() == null ? 0 : job.finishedAt() - job.startedAt();
            failures = uptime >= stableAfter.toMillis() ? 1 : failures + 1;
            long delay = baseBackoff.toMillis() * (1L << Math.min(failures - 1, 20));
            next = now + Math.min(delay, maxBackoff.toMillis());
        }
        return Optional.of(new ServiceDefinition(tenant, name, script, config, restart, enabled, done, revision,
                null, 0, 0, runs, failures, next, exit, createdAt, now));
    }

    /** The state the API shows, given the current run's job (null if none or not found). */
    public ServiceStatus status(Job current, long now) {
        if (completed) {
            return ServiceStatus.COMPLETED;
        }
        if (current != null && !current.status().isTerminal()) {
            if (!enabled || current.cancelRequested() != null) {
                return ServiceStatus.STOPPING;
            }
            return current.status() == JobStatus.RUNNING ? ServiceStatus.RUNNING : ServiceStatus.STARTING;
        }
        if (!enabled) {
            return ServiceStatus.STOPPED;
        }
        return nextStartAt > now ? ServiceStatus.BACKOFF : ServiceStatus.STARTING;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("tenant", tenant)
                .put("name", name)
                .put("script", script)
                .put("config", config)
                .put("restart", restart.apiName())
                .put("enabled", enabled)
                .put("completed", completed)
                .put("revision", revision)
                .put("currentJobId", currentJobId)
                .put("currentRevision", currentRevision)
                .put("currentSince", currentSince)
                .put("runs", runs)
                .put("consecutiveFailures", consecutiveFailures)
                .put("nextStartAt", nextStartAt)
                .put("lastExit", lastExit)
                .put("createdAt", createdAt)
                .put("updatedAt", updatedAt);
    }

    public static ServiceDefinition fromJson(JsonObject json) {
        return new ServiceDefinition(
                json.getString("tenant"),
                json.getString("name"),
                json.getString("script"),
                json.getJsonObject("config"),
                RestartPolicy.fromApi(json.getString("restart")),
                json.getBoolean("enabled"),
                json.getBoolean("completed", false),
                json.getInteger("revision"),
                json.getString("currentJobId"),
                json.getInteger("currentRevision", 0),
                json.getLong("currentSince", 0L),
                json.getInteger("runs", 0),
                json.getInteger("consecutiveFailures", 0),
                json.getLong("nextStartAt", 0L),
                json.getJsonObject("lastExit"),
                json.getLong("createdAt", 0L),
                json.getLong("updatedAt", 0L));
    }
}
