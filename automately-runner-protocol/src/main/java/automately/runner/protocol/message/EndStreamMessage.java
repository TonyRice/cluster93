package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * 0x32 END: Orderly half-close of a streaming channel. Both directions.
 */
public record EndStreamMessage(int streamId) {

    public EndStreamMessage {
        if (streamId <= 0) {
            throw new IllegalArgumentException("streamId must be positive: " + streamId);
        }
    }

    public Frame toFrame() {
        return new Frame(FrameType.END, Frame.FLAG_END_STREAM, streamId, new JsonObject(), Buffer.buffer());
    }

    public static EndStreamMessage fromFrame(Frame frame) {
        return new EndStreamMessage(frame.channelId());
    }
}
