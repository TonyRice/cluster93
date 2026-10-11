package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Final execution outcome returned by a worker (worker-node-protocol.md §4).
 */
public record ResultRunMessage(int channelId, String runId, String status, Object output, String error) {

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("runId", runId)
                .put("status", status)
                .put("output", output)
                .put("error", error);
        return Frame.json(FrameType.RESULT_RUN, channelId, json);
    }

    public static ResultRunMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.RESULT_RUN) {
            throw new IllegalArgumentException("Expected RESULT_RUN frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new ResultRunMessage(
                frame.channelId(),
                json.getString("runId"),
                json.getString("status"),
                json.getValue("output"),
                json.getString("error")
        );
    }
}
