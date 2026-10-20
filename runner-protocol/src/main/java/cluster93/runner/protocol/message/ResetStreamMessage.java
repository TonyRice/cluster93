package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * 0x33 RESET: Abrupt stream termination (RST_STREAM). Both directions.
 *
 * <p>When a <em>host</em> failure cut the stream, the frame also carries that failure's {@code name} and {@code code}
 * (D-184): a body stream that fails after its headers were delivered has no second stream to report on, so without
 * these the reader can only say "reset (code 502)" — the numbers, not the words (D-181's remaining gap). Both fields
 * are optional and absent for every ordinary reset, so the frame a peer that knows nothing of them sees is unchanged.
 */
public record ResetStreamMessage(int streamId, String reason, int errorCode, String errorName, String hostCode) {

    /** A reset with no host failure behind it: the shape every ordinary reset sends. */
    public ResetStreamMessage(int streamId, String reason, int errorCode) {
        this(streamId, reason, errorCode, null, null);
    }

    public ResetStreamMessage {
        if (streamId <= 0) {
            throw new IllegalArgumentException("streamId must be positive: " + streamId);
        }
        Objects.requireNonNull(reason, "reason");
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject().put("reason", reason).put("code", errorCode);
        if (errorName != null) {
            json.put("errorName", errorName);
        }
        if (hostCode != null) {
            json.put("hostCode", hostCode);
        }
        return new Frame(FrameType.RESET, Frame.FLAG_NONE, streamId, json, Buffer.buffer());
    }

    public static ResetStreamMessage fromFrame(Frame frame) {
        JsonObject json = frame.json();
        String reason = json.getString("reason", "unknown");
        int code = json.getInteger("code", 0);
        return new ResetStreamMessage(frame.channelId(), reason, code,
                json.getString("errorName"), json.getString("hostCode"));
    }
}
