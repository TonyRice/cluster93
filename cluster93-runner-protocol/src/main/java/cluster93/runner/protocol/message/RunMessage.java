package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Dispatches a script execution to a bound worker with everything the run needs to execute out-of-process:
 * the script, its input, the serialized {@code ctx}, the package lock, its {@code process.env}, and whether it is a
 * long-running service (worker-node-protocol.md §4, D-128).
 */
public record RunMessage(int channelId, String runId, String script, JsonObject input, JsonObject context, long timeoutMs,
                         boolean longRunning, JsonObject packages, JsonObject env) {

    public RunMessage {
        input = input != null ? input : new JsonObject();
        context = context != null ? context : new JsonObject();
        packages = packages != null ? packages : new JsonObject();
        env = env != null ? env : new JsonObject();
    }

    /** A finite job with no packages and the default environment. */
    public RunMessage(int channelId, String runId, String script, JsonObject input, JsonObject context, long timeoutMs) {
        this(channelId, runId, script, input, context, timeoutMs, false, new JsonObject(), new JsonObject());
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("runId", runId)
                .put("script", script)
                .put("input", input)
                .put("context", context)
                .put("timeoutMs", timeoutMs)
                .put("longRunning", longRunning)
                .put("packages", packages)
                .put("env", env);
        return Frame.json(FrameType.RUN, channelId, json);
    }

    public static RunMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.RUN) {
            throw new IllegalArgumentException("Expected RUN frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new RunMessage(
                frame.channelId(),
                json.getString("runId"),
                json.getString("script"),
                json.getJsonObject("input", new JsonObject()),
                json.getJsonObject("context", new JsonObject()),
                json.getLong("timeoutMs", 60_000L),
                json.getBoolean("longRunning", false),
                json.getJsonObject("packages", new JsonObject()),
                json.getJsonObject("env", new JsonObject())
        );
    }
}
