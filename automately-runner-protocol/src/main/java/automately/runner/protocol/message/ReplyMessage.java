package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * 0x21 REPLY: a worker listener's answer to an {@link EventMessage} that expected one (a bus request, a queue
 * acknowledgement, an HTTP response head). Worker -&gt; Node (worker-node-protocol.md §4, D-128).
 */
public record ReplyMessage(int channelId, String eventId, boolean ok, Object value, String errorCode, String errorMessage) {

    public static ReplyMessage success(int channelId, String eventId, Object value) {
        return new ReplyMessage(channelId, eventId, true, value, null, null);
    }

    public static ReplyMessage failure(int channelId, String eventId, String errorCode, String errorMessage) {
        return new ReplyMessage(channelId, eventId, false, null, errorCode, errorMessage);
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("eventId", eventId)
                .put("ok", ok);
        if (ok) {
            json.put("value", value);
        } else {
            json.put("errorCode", errorCode);
            json.put("errorMessage", errorMessage);
        }
        return Frame.json(FrameType.REPLY, channelId, json);
    }

    public static ReplyMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.REPLY) {
            throw new IllegalArgumentException("Expected REPLY frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new ReplyMessage(
                frame.channelId(),
                json.getString("eventId"),
                json.getBoolean("ok", false),
                json.getValue("value"),
                json.getString("errorCode"),
                json.getString("errorMessage")
        );
    }
}
