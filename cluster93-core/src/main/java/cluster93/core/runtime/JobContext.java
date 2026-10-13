package cluster93.core.runtime;

import cluster93.core.job.Attempt;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.state.JobState;
import io.vertx.core.json.JsonObject;

/**
 * What a script sees as {@code ctx}: which attempt this is, what happened before, and (when resuming)
 * the state carried over. See docs/scripting/job-context.md and docs/scripting/services.md.
 *
 * @param previous the attempt before this one, or null
 * @param carried  checkpoint and completed steps from earlier attempts (or, for a service, the previous
 *                 run's checkpoint); empty otherwise
 * @param service  set when this job is a run of a service; exposed as {@code ctx.service}
 */
public record JobContext(String jobId, int attempt, OrphanPolicy policy, Attempt previous, JobState carried, JsonObject service) {

    public JobContext(String jobId, int attempt, OrphanPolicy policy, Attempt previous, JobState carried) {
        this(jobId, attempt, policy, previous, carried, null);
    }

    public static JobContext firstAttempt(String jobId, OrphanPolicy policy) {
        return new JobContext(jobId, 1, policy, null, JobState.EMPTY, null);
    }

    /** The JSON handed to the runtime to build {@code ctx}. Steps keep the order they completed in. */
    public JsonObject toScriptJson() {
        JsonObject steps = new JsonObject();
        carried.steps().forEach(step -> steps.put(step.name(), step.result()));
        return new JsonObject()
                .put("jobId", jobId)
                .put("attempt", attempt)
                .put("policy", policy.apiName())
                .put("previous", previous == null ? null : previous.toScriptJson())
                .put("checkpoint", carried.checkpoint())
                .put("steps", steps)
                .put("service", service);
    }

    /**
     * Serializes this context (including the previous attempt and carried state) so an out-of-process worker can
     * rebuild it before running the script (D-127, D-128).
     *
     * @return the JSON form accepted by {@link #fromJson(JsonObject)}
     */
    public JsonObject toJson() {
        return new JsonObject()
                .put("jobId", jobId)
                .put("attempt", attempt)
                .put("policy", policy.name())
                .put("previous", previous == null ? null : previous.toJson())
                .put("carried", carried == null ? JobState.EMPTY.toJson() : carried.toJson())
                .put("service", service);
    }

    /**
     * Restores a context serialized by {@link #toJson()}.
     *
     * @param json the serialized context, or null
     * @return the restored context, or null when {@code json} is null
     */
    public static JobContext fromJson(JsonObject json) {
        if (json == null) {
            return null;
        }
        JsonObject previous = json.getJsonObject("previous");
        return new JobContext(
                json.getString("jobId"),
                json.getInteger("attempt", 1),
                OrphanPolicy.valueOf(json.getString("policy", "FAIL")),
                previous == null ? null : Attempt.fromJson(previous),
                JobState.fromJson(json.getJsonObject("carried")),
                json.getJsonObject("service"));
    }
}
