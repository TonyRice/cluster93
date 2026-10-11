package automately.runner.protocol.frame;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * An in-cluster worker-node IPC binary frame (D-127, worker-node-protocol.md §3).
 *
 * @param type      semantics discriminator
 * @param flags     bitmask (e.g. END_STREAM, COMPRESSED, PRIORITY)
 * @param channelId 0 for control; else run ID, call ID, or stream ID
 * @param json      structured JSON payload (nullable)
 * @param binary    contiguous raw byte payload (nullable)
 */
public record Frame(FrameType type, byte flags, int channelId, JsonObject json, Buffer binary) {

    public static final byte FLAG_NONE = 0x00;
    public static final byte FLAG_END_STREAM = 0x01;
    public static final byte FLAG_COMPRESSED = 0x02;
    public static final byte FLAG_PRIORITY = 0x04;

    public Frame {
        Objects.requireNonNull(type, "type");
        if (json == null) {
            json = new JsonObject();
        }
        if (binary == null) {
            binary = Buffer.buffer();
        }
    }

    public boolean hasFlag(byte flag) {
        return (flags & flag) == flag;
    }

    public boolean isEndStream() {
        return hasFlag(FLAG_END_STREAM);
    }

    public boolean isCompressed() {
        return hasFlag(FLAG_COMPRESSED);
    }

    public boolean isPriority() {
        return hasFlag(FLAG_PRIORITY);
    }

    /** Helper for control frames (channelId = 0). */
    public static Frame control(FrameType type, JsonObject json) {
        return new Frame(type, FLAG_NONE, 0, json, Buffer.buffer());
    }

    /** Helper for channelized frames without binary data. */
    public static Frame json(FrameType type, int channelId, JsonObject json) {
        return new Frame(type, FLAG_NONE, channelId, json, Buffer.buffer());
    }

    /** Helper for streaming data frames. */
    public static Frame data(int channelId, Buffer binary, boolean endStream) {
        byte flags = endStream ? FLAG_END_STREAM : FLAG_NONE;
        return new Frame(FrameType.DATA, flags, channelId, new JsonObject(), binary);
    }

    /** Helper for ping / pong frames. */
    public static Frame ping() {
        return control(FrameType.PING, new JsonObject());
    }

    public static Frame pong() {
        return control(FrameType.PONG, new JsonObject());
    }
}
