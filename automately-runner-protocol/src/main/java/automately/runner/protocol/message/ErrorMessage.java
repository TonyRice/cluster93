package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Host API failure returned from node to worker (worker-node-protocol.md §4).
 */
public record ErrorMessage(int callId, String code, String message) {

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("code", code)
                .put("message", message);
        return Frame.json(FrameType.ERROR, callId, json);
    }

    public static ErrorMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.ERROR) {
            throw new IllegalArgumentException("Expected ERROR frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new ErrorMessage(
                frame.channelId(),
                json.getString("code", "INTERNAL_ERROR"),
                json.getString("message", "Unknown error")
        );
    }
}
