package automately.core.runtime;

import io.vertx.core.json.JsonObject;

import java.time.Duration;

/** Everything a runtime needs to run one attempt of a job. */
public record Execution(String jobId, String tenant, String script, JsonObject input, Duration timeout, JobContext context) {
}
