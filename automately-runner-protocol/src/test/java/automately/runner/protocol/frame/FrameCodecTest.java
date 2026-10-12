package automately.runner.protocol.frame;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameCodecTest {

    @Test
    void encodesAndDecodesControlFrame() {
        JsonObject json = new JsonObject().put("engine", "javet").put("pid", 12345L);
        Frame frame = Frame.control(FrameType.HELLO, json);

        Buffer encoded = FrameCodec.encode(frame);
        assertThat(encoded.length()).isGreaterThan(12);

        List<Frame> decoded = new ArrayList<>();
        FrameCodec.FrameParser parser = FrameCodec.parser(decoded::add, err -> { throw new RuntimeException(err); });
        parser.handle(encoded);

        assertThat(decoded).hasSize(1);
        Frame parsed = decoded.getFirst();
        assertThat(parsed.type()).isEqualTo(FrameType.HELLO);
        assertThat(parsed.flags()).isEqualTo(Frame.FLAG_NONE);
        assertThat(parsed.channelId()).isEqualTo(0);
        assertThat(parsed.json().getString("engine")).isEqualTo("javet");
        assertThat(parsed.json().getLong("pid")).isEqualTo(12345L);
        assertThat(parsed.binary().length()).isEqualTo(0);
    }

    @Test
    void encodesAndDecodesSplitJsonAndContiguousBinary() {
        JsonObject json = new JsonObject().put("path", "/test.txt").put("size", 11);
        byte[] rawBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        Buffer binary = Buffer.buffer(rawBytes);

        Frame frame = new Frame(FrameType.CALL, Frame.FLAG_PRIORITY, 42, json, binary);
        Buffer encoded = FrameCodec.encode(frame);

        List<Frame> decoded = new ArrayList<>();
        FrameCodec.FrameParser parser = FrameCodec.parser(decoded::add, err -> { throw new RuntimeException(err); });
        parser.handle(encoded);

        assertThat(decoded).hasSize(1);
        Frame parsed = decoded.getFirst();
        assertThat(parsed.type()).isEqualTo(FrameType.CALL);
        assertThat(parsed.flags()).isEqualTo(Frame.FLAG_PRIORITY);
        assertThat(parsed.isPriority()).isTrue();
        assertThat(parsed.channelId()).isEqualTo(42);
        assertThat(parsed.json().getString("path")).isEqualTo("/test.txt");
        assertThat(parsed.binary().toString(StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    @Test
    void handlesStreamingChunksAndArbitraryFragmentation() {
        JsonObject json = new JsonObject().put("message", "chunked test");
        Buffer binary = Buffer.buffer(new byte[100]);
        Frame frame = new Frame(FrameType.DATA, Frame.FLAG_END_STREAM, 7, json, binary);
        Buffer encoded = FrameCodec.encode(frame);

        List<Frame> decoded = new ArrayList<>();
        FrameCodec.FrameParser parser = FrameCodec.parser(decoded::add, err -> { throw new RuntimeException(err); });

        // Feed bytes one by one or in small odd-sized slices
        int step = 7;
        for (int i = 0; i < encoded.length(); i += step) {
            int end = Math.min(encoded.length(), i + step);
            parser.handle(encoded.slice(i, end));
        }

        assertThat(decoded).hasSize(1);
        Frame parsed = decoded.getFirst();
        assertThat(parsed.type()).isEqualTo(FrameType.DATA);
        assertThat(parsed.isEndStream()).isTrue();
        assertThat(parsed.channelId()).isEqualTo(7);
        assertThat(parsed.binary().length()).isEqualTo(100);
    }

    @Test
    void parsesLargeFramesSplitAtReadBoundaries() {
        // The StreamBridge reader hands the parser fixed 8 KiB reads, so a large DATA frame's end never lines up with a
        // read: the leftover must stay in a growable buffer or the next append throws and the parser silently dies.
        byte[] pattern = new byte[8192];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = (byte) (i * 31 + 7);
        }
        Buffer binary = Buffer.buffer(pattern);
        Buffer encoded = FrameCodec.encode(new Frame(FrameType.DATA, Frame.FLAG_NONE, 4, new JsonObject(), binary));

        List<Frame> decoded = new ArrayList<>();
        FrameCodec.FrameParser parser = FrameCodec.parser(decoded::add, err -> { throw new RuntimeException(err); });
        for (int i = 0; i < encoded.length(); i += 8192) {
            parser.handle(encoded.slice(i, Math.min(encoded.length(), i + 8192)));
        }

        assertThat(decoded).hasSize(1);
        assertThat(decoded.getFirst().binary().getBytes()).isEqualTo(pattern);
    }

    @Test
    void enforcesMaxFrameLengthLimit() {
        Buffer hugeBuffer = Buffer.buffer(new byte[FrameCodec.MAX_FRAME_LENGTH + 10]);
        Frame oversizedFrame = new Frame(FrameType.DATA, Frame.FLAG_NONE, 1, new JsonObject(), hugeBuffer);

        assertThatThrownBy(() -> FrameCodec.encode(oversizedFrame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum allowed");
    }

    @Test
    void detectsMalformedOversizedFrameInParser() {
        Buffer malformed = Buffer.buffer();
        malformed.appendInt(FrameCodec.MAX_FRAME_LENGTH + 500); // Frame Length > 1 MiB
        malformed.appendByte(FrameType.DATA.code());

        AtomicReference<Throwable> caught = new AtomicReference<>();
        FrameCodec.FrameParser parser = FrameCodec.parser(frame -> {}, caught::set);
        parser.handle(malformed);

        assertThat(caught.get()).isInstanceOf(FrameCodec.ProtocolViolationException.class);
    }

    @Test
    void detectsMalformedSmallFrameInParser() {
        Buffer malformed = Buffer.buffer();
        malformed.appendInt(5); // Frame Length < MIN_FRAME_CONTENT_LENGTH (12)
        malformed.appendBytes(new byte[5]);

        AtomicReference<Throwable> caught = new AtomicReference<>();
        FrameCodec.FrameParser parser = FrameCodec.parser(frame -> {}, caught::set);
        parser.handle(malformed);

        assertThat(caught.get()).isInstanceOf(FrameCodec.ProtocolViolationException.class);
    }
}
