package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Flow-control credit grant frame (worker-node-protocol.md §4, §5).
 */
public record CreditMessage(int channelId, int bytesToAdd) {

    public int streamId() {
        return channelId;
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject().put("bytesToAdd", bytesToAdd);
        return Frame.json(FrameType.CREDIT, channelId, json);
    }

    public static CreditMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.CREDIT) {
            throw new IllegalArgumentException("Expected CREDIT frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new CreditMessage(
                frame.channelId(),
                json.getInteger("bytesToAdd", 0)
        );
    }
}
