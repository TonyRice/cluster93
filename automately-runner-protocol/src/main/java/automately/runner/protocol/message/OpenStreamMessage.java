package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * 0x30 OPEN: Establishes a new streaming channel (HTTP body, socket, file). Both directions.
 */
public record OpenStreamMessage(int streamId, String streamType, JsonObject metadata) {

    public OpenStreamMessage {
        if (streamId <= 0) {
            throw new IllegalArgumentException("streamId must be positive: " + streamId);
        }
        Objects.requireNonNull(streamType, "streamType");
        if (metadata == null) {
            metadata = new JsonObject();
        }
    }

    public Frame toFrame() {
        JsonObject json = metadata.copy().put("type", streamType);
        return new Frame(FrameType.OPEN, Frame.FLAG_NONE, streamId, json, Buffer.buffer());
    }

    public static OpenStreamMessage fromFrame(Frame frame) {
        JsonObject json = frame.json();
        String type = json.getString("type", "generic");
        return new OpenStreamMessage(frame.channelId(), type, json);
    }
}
