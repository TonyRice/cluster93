package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Checkpoint or step state commit from worker to node (worker-node-protocol.md §4).
 */
public record StateMessage(int channelId, String runId, String type, String stepName, Object value) {

    public static StateMessage checkpoint(int channelId, String runId, Object value) {
        return new StateMessage(channelId, runId, "checkpoint", null, value);
    }

    public static StateMessage step(int channelId, String runId, String stepName, Object value) {
        return new StateMessage(channelId, runId, "step", stepName, value);
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("runId", runId)
                .put("type", type)
                .put("stepName", stepName)
                .put("value", value);
        return Frame.json(FrameType.STATE, channelId, json);
    }

    public static StateMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.STATE) {
            throw new IllegalArgumentException("Expected STATE frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new StateMessage(
                frame.channelId(),
                json.getString("runId"),
                json.getString("type", "checkpoint"),
                json.getString("stepName"),
                json.getValue("value")
        );
    }
}
