package automately.core.runtime;

import automately.core.job.Attempt;
import automately.core.job.OrphanPolicy;
import automately.core.state.JobState;
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
}
