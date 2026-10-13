package cluster93.core.job;

import io.vertx.core.json.JsonObject;

/** One finished run of a job, kept in the job's history. */
public record Attempt(int attempt, String node, String nodeInstance, Long startedAt, long endedAt, JobStatus outcome) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("attempt", attempt)
                .put("node", node)
                .put("nodeInstance", nodeInstance)
                .put("startedAt", startedAt)
                .put("endedAt", endedAt)
                .put("outcome", outcome.name());
    }

    /** The view a script gets as {@code ctx.previous}. */
    public JsonObject toScriptJson() {
        return new JsonObject()
                .put("attempt", attempt)
                .put("node", node)
                .put("outcome", outcome.name())
                .put("startedAt", startedAt)
                .put("endedAt", endedAt);
    }

    public static Attempt fromJson(JsonObject json) {
        return new Attempt(
                json.getInteger("attempt"),
                json.getString("node"),
                json.getString("nodeInstance"),
                json.getLong("startedAt"),
                json.getLong("endedAt"),
                JobStatus.valueOf(json.getString("outcome")));
    }
}
