package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * Successful response from node to worker for a host API RPC (worker-node-protocol.md §4).
 */
public record ResultMessage(int callId, Object value, Buffer binary) {

    public ResultMessage(int callId, Object value) {
        this(callId, value, Buffer.buffer());
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject().put("value", value);
        return new Frame(FrameType.RESULT, Frame.FLAG_NONE, callId, json, binary != null ? binary : Buffer.buffer());
    }

    public static ResultMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.RESULT) {
            throw new IllegalArgumentException("Expected RESULT frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new ResultMessage(
                frame.channelId(),
                json.getValue("value"),
                frame.binary()
        );
    }
}
