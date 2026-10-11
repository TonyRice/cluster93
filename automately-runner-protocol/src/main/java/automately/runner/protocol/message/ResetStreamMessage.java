package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * 0x33 RESET: Abrupt stream termination (RST_STREAM). Both directions.
 */
public record ResetStreamMessage(int streamId, String reason, int errorCode) {

    public ResetStreamMessage {
        if (streamId <= 0) {
            throw new IllegalArgumentException("streamId must be positive: " + streamId);
        }
        Objects.requireNonNull(reason, "reason");
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject().put("reason", reason).put("code", errorCode);
        return new Frame(FrameType.RESET, Frame.FLAG_NONE, streamId, json, Buffer.buffer());
    }

    public static ResetStreamMessage fromFrame(Frame frame) {
        JsonObject json = frame.json();
        String reason = json.getString("reason", "unknown");
        int code = json.getInteger("code", 0);
        return new ResetStreamMessage(frame.channelId(), reason, code);
    }
}
