package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Dispatches a script execution to a bound worker (worker-node-protocol.md §4).
 */
public record RunMessage(int channelId, String runId, String script, JsonObject input, JsonObject context, long timeoutMs) {

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("runId", runId)
                .put("script", script)
                .put("input", input != null ? input : new JsonObject())
                .put("context", context != null ? context : new JsonObject())
                .put("timeoutMs", timeoutMs);
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
                json.getLong("timeoutMs", 60_000L)
        );
    }
}
