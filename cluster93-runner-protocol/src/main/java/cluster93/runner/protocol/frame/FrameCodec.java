package cluster93.runner.protocol.frame;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes and decodes binary frames for the in-cluster worker-node protocol (D-127, worker-node-protocol.md §3).
 */
public final class FrameCodec {

    /** Maximum allowed frame length following the 4-byte length field: 1 MiB (1,048,576 bytes). */
    public static final int MAX_FRAME_LENGTH = 1_048_576;

    /** Minimum frame length following the length field: 8 bytes (type + flags + reserved + channelId) + 4 bytes jsonLength = 12 bytes. */
    public static final int MIN_FRAME_CONTENT_LENGTH = 12;

    private FrameCodec() {
    }

    /**
     * Serializes a {@link Frame} into a wire-format {@link Buffer}.
     *
     * @param frame the frame to encode
     * @return the serialized buffer
     * @throws IllegalArgumentException if the serialized frame exceeds {@link #MAX_FRAME_LENGTH}
     */
    public static Buffer encode(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] jsonBytes = frame.json().isEmpty() ? new byte[0] : frame.json().encode().getBytes(StandardCharsets.UTF_8);
        Buffer binary = frame.binary();
        int binaryLength = binary.length();

        int frameLength = 8 + 4 + jsonBytes.length + binaryLength; // 8 bytes (type..channel) + 4 (jsonLen) + json + binary
        if (frameLength > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("Frame length " + frameLength + " exceeds maximum allowed " + MAX_FRAME_LENGTH);
        }

        Buffer buf = Buffer.buffer(4 + frameLength);
        // 1. Frame Length (u32, big-endian)
        buf.appendInt(frameLength);
        // 2. Frame Type (u8)
        buf.appendByte(frame.type().code());
        // 3. Flags (u8)
        buf.appendByte(frame.flags());
        // 4. Reserved (u16 = 0)
        buf.appendShort((short) 0);
        // 5. Channel ID (u32, big-endian)
        buf.appendInt(frame.channelId());
        // 6. JSON Length (u32, big-endian)
        buf.appendInt(jsonBytes.length);
        // 7. JSON Payload (UTF-8)
        if (jsonBytes.length > 0) {
            buf.appendBytes(jsonBytes);
        }
        // 8. Raw Binary Buffers
        if (binaryLength > 0) {
            buf.appendBuffer(binary);
        }
        return buf;
    }

    /**
     * Creates a stateful streaming {@link FrameParser} that receives arbitrary byte chunks and emits parsed {@link Frame}s.
     */
    public static FrameParser parser(Handler<Frame> frameHandler, Handler<Throwable> errorHandler) {
        return new FrameParser(frameHandler, errorHandler);
    }

    /**
     * Stateful streaming parser that reassembles frames across chunk boundaries.
     */
    public static final class FrameParser implements Handler<Buffer> {

        private final Handler<Frame> frameHandler;
        private final Handler<Throwable> errorHandler;
        private Buffer accumulator = Buffer.buffer();

        public FrameParser(Handler<Frame> frameHandler, Handler<Throwable> errorHandler) {
            this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler");
            this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        }

        @Override
        public void handle(Buffer chunk) {
            if (chunk == null || chunk.length() == 0) {
                return;
            }
            accumulator.appendBuffer(chunk);
            try {
                parseAccumulator();
            } catch (Exception e) {
                errorHandler.handle(e);
            }
        }

        private void parseAccumulator() {
            while (accumulator.length() >= 4) {
                int frameLength = accumulator.getInt(0);
                if (frameLength < MIN_FRAME_CONTENT_LENGTH) {
                    throw new ProtocolViolationException("Malformed frame length: " + frameLength + " (minimum is " + MIN_FRAME_CONTENT_LENGTH + ")");
                }
                if (frameLength > MAX_FRAME_LENGTH) {
                    throw new ProtocolViolationException("Frame length " + frameLength + " exceeds maximum allowed " + MAX_FRAME_LENGTH);
                }

                int totalFrameBytes = 4 + frameLength;
                if (accumulator.length() < totalFrameBytes) {
                    // Incomplete frame, wait for more data
                    return;
                }

                // We have a complete frame
                byte typeCode = accumulator.getByte(4);
                byte flags = accumulator.getByte(5);
                // short reserved = accumulator.getShort(6);
                int channelId = accumulator.getInt(8);
                int jsonLength = accumulator.getInt(12);

                if (jsonLength < 0 || 12 + jsonLength > frameLength) {
                    throw new ProtocolViolationException("Invalid JSON length: " + jsonLength + " with frame length " + frameLength);
                }

                JsonObject json;
                if (jsonLength == 0) {
                    json = new JsonObject();
                } else {
                    String jsonStr = accumulator.getString(16, 16 + jsonLength, StandardCharsets.UTF_8.name());
                    json = new JsonObject(jsonStr);
                }

                int binaryOffset = 16 + jsonLength;
                int binaryLength = totalFrameBytes - binaryOffset;
                Buffer binary = binaryLength > 0 ? accumulator.slice(binaryOffset, totalFrameBytes) : Buffer.buffer();

                FrameType type = FrameType.fromCode(typeCode);
                Frame frame = new Frame(type, flags, channelId, json, binary);

                // Advance accumulator. The leftover bytes must go into a growable buffer: Buffer.slice() returns a
                // fixed-capacity view, so appending the next chunk to it throws and silently kills the parser, which
                // stalls any frame that doesn't line up with a read boundary (large bodies).
                if (accumulator.length() == totalFrameBytes) {
                    accumulator = Buffer.buffer();
                } else {
                    accumulator = Buffer.buffer(accumulator.getBytes(totalFrameBytes, accumulator.length()));
                }

                frameHandler.handle(frame);
            }
        }
    }

    /**
     * Thrown when an incoming frame violates framing or length constraints.
     */
    public static final class ProtocolViolationException extends RuntimeException {
        public ProtocolViolationException(String message) {
            super(message);
        }
    }
}
