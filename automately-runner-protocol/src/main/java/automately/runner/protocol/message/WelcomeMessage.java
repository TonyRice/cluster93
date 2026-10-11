package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Node acceptance message assigning worker identity and protocol limits (worker-node-protocol.md §4).
 */
public record WelcomeMessage(String workerId, JsonObject config) {

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("workerId", workerId)
                .put("config", config != null ? config : new JsonObject());
        return Frame.control(FrameType.WELCOME, json);
    }

    public static WelcomeMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.WELCOME) {
            throw new IllegalArgumentException("Expected WELCOME frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new WelcomeMessage(
                json.getString("workerId"),
                json.getJsonObject("config", new JsonObject())
        );
    }
}
