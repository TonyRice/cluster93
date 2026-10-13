package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * Host API RPC invocation from worker to node (worker-node-protocol.md §4, §6).
 */
public record CallMessage(int callId, String runId, String method, JsonObject params, Buffer binary) {

    public CallMessage(int callId, String runId, String method, JsonObject params) {
        this(callId, runId, method, params, Buffer.buffer());
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("runId", runId)
                .put("method", method)
                .put("params", params != null ? params : new JsonObject());
        return new Frame(FrameType.CALL, Frame.FLAG_NONE, callId, json, binary != null ? binary : Buffer.buffer());
    }

    public static CallMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.CALL) {
            throw new IllegalArgumentException("Expected CALL frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new CallMessage(
                frame.channelId(),
                json.getString("runId"),
                json.getString("method"),
                json.getJsonObject("params", new JsonObject()),
                frame.binary()
        );
    }
}
