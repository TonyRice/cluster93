package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * 0x20 EVENT: asynchronous delivery of a bus message, a queue message, an HTTP exchange or a job event from the node
 * to a listener registered in the worker (worker-node-protocol.md §4, D-128). When {@code expectsReply} is set the
 * worker answers with a {@link ReplyMessage} carrying the same {@code eventId}.
 */
public record EventMessage(int channelId, String eventId, String type, String topic, JsonObject payload, boolean expectsReply) {

    public EventMessage {
        payload = payload != null ? payload : new JsonObject();
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("eventId", eventId)
                .put("type", type)
                .put("topic", topic)
                .put("payload", payload)
                .put("expectsReply", expectsReply);
        return Frame.json(FrameType.EVENT, channelId, json);
    }

    public static EventMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.EVENT) {
            throw new IllegalArgumentException("Expected EVENT frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new EventMessage(
                frame.channelId(),
                json.getString("eventId"),
                json.getString("type"),
                json.getString("topic"),
                json.getJsonObject("payload", new JsonObject()),
                json.getBoolean("expectsReply", false)
        );
    }
}
