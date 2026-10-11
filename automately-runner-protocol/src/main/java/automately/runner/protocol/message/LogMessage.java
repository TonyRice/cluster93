package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Console log line stream from worker to node (worker-node-protocol.md §4).
 */
public record LogMessage(int channelId, String runId, String level, String line) {

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("runId", runId)
                .put("level", level)
                .put("line", line);
        return Frame.json(FrameType.LOG, channelId, json);
    }

    public static LogMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.LOG) {
            throw new IllegalArgumentException("Expected LOG frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new LogMessage(
                frame.channelId(),
                json.getString("runId"),
                json.getString("level", "INFO"),
                json.getString("line", "")
        );
    }
}
