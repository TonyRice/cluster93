package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Permanently binds an in-cluster worker to one tenant (worker-node-protocol.md §4).
 */
public record BindMessage(String tenantId, JsonObject limits, JsonObject env) {

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("tenantId", tenantId)
                .put("limits", limits != null ? limits : new JsonObject())
                .put("env", env != null ? env : new JsonObject());
        return Frame.control(FrameType.BIND, json);
    }

    public static BindMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.BIND) {
            throw new IllegalArgumentException("Expected BIND frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new BindMessage(
                json.getString("tenantId"),
                json.getJsonObject("limits", new JsonObject()),
                json.getJsonObject("env", new JsonObject())
        );
    }
}
