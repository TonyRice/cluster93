package automately.core.runtime;

import automately.core.job.Attempt;
import automately.core.job.OrphanPolicy;
import automately.core.state.JobState;
import io.vertx.core.json.JsonObject;

/**
 * What a script sees as {@code ctx}: which attempt this is, what happened before, and (when resuming)
 * the state carried over. See docs/scripting/job-context.md.
 *
 * @param previous the attempt before this one, or null
 * @param carried  checkpoint and completed steps from earlier attempts; empty unless resuming
 */
public record JobContext(String jobId, int attempt, OrphanPolicy policy, Attempt previous, JobState carried) {

    public static JobContext firstAttempt(String jobId, OrphanPolicy policy) {
        return new JobContext(jobId, 1, policy, null, JobState.EMPTY);
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
                .put("steps", steps);
    }
}
