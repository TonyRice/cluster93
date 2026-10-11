package automately.runner.protocol.message;

import automately.runner.protocol.frame.Frame;
import io.vertx.core.buffer.Buffer;

import java.util.Objects;

/**
 * 0x31 DATA: Transmits binary data chunks on an active streaming channel. Both directions.
 */
public record DataStreamMessage(int streamId, Buffer data, boolean endStream) {

    public DataStreamMessage {
        if (streamId <= 0) {
            throw new IllegalArgumentException("streamId must be positive: " + streamId);
        }
        Objects.requireNonNull(data, "data");
    }

    public Frame toFrame() {
        return Frame.data(streamId, data, endStream);
    }

    public static DataStreamMessage fromFrame(Frame frame) {
        return new DataStreamMessage(frame.channelId(), frame.binary(), frame.isEndStream());
    }
}
