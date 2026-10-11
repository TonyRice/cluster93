package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

/**
 * Worker announcement upon process startup (worker-node-protocol.md §4).
 */
public record HelloMessage(String engine, String version, long pid) {

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("engine", engine)
                .put("version", version)
                .put("pid", pid);
        return Frame.control(FrameType.HELLO, json);
    }

    public static HelloMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.HELLO) {
            throw new IllegalArgumentException("Expected HELLO frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new HelloMessage(
                json.getString("engine", "unknown"),
                json.getString("version", "unknown"),
                json.getLong("pid", 0L)
        );
    }
}
