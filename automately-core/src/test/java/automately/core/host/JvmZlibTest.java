package automately.core.host;

import automately.core.runtime.api.ZlibApi;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compression both ways, gzip read in any pieces, and the output cap that stops a zip bomb (D-103). */
class JvmZlibTest {

    private static final JsonObject NONE = new JsonObject();

    private static byte[] text(int repeat) {
        return "Hello, compression! ".repeat(repeat).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void everyKindRoundTripsAndGzipIsReadableByTheJdk() throws Exception {
        JvmZlib zlib = new JvmZlib();
        byte[] data = text(1000);
        for (String[] pair : new String[][] {{"gzip", "gunzip"}, {"deflate", "inflate"}, {"deflateRaw", "inflateRaw"}, {"gzip", "unzip"}, {"deflate", "unzip"}}) {
            byte[] packed = zlib.process(pair[0], data, NONE);
            assertThat(packed.length).as(pair[0]).isLessThan(data.length / 10);
            assertThat(zlib.process(pair[1], packed, NONE)).as(pair[1]).isEqualTo(data);
        }
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(zlib.process("gzip", data, new JsonObject().put("level", 9))))) {
            assertThat(in.readAllBytes()).as("the JDK's own gunzip reads ours").isEqualTo(data);
        }
    }

    @Test
    void gzipCanBeFedOneByteAtATimeAndInSeveralMembers() {
        JvmZlib zlib = new JvmZlib();
        byte[] packed = new ByteArrayOutputStream() {{
            writeBytes(zlib.process("gzip", "one ".getBytes(StandardCharsets.UTF_8), NONE));
            writeBytes(zlib.process("gzip", "two".getBytes(StandardCharsets.UTF_8), NONE));
        }}.toByteArray();
        int stream = zlib.open("gunzip", NONE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : packed) {
            out.writeBytes(zlib.write(stream, new byte[] {b}));
        }
        out.writeBytes(zlib.finish(stream));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("one two");
        assertThatThrownBy(() -> zlib.write(stream, new byte[1])).as("finish closes it").hasMessageContaining("already ended");
    }

    @Test
    void aZipBombStopsAtTheCap() {
        JvmZlib zlib = new JvmZlib();
        // 64 MB of zeros packs into about 64 KB.
        int stream = zlib.open("gzip", new JsonObject().put("level", 9));
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        byte[] zeros = new byte[1024 * 1024];
        for (int i = 0; i < 64; i++) {
            bomb.writeBytes(zlib.write(stream, zeros));
        }
        bomb.writeBytes(zlib.finish(stream));
        assertThat(bomb.size()).isLessThan(200_000);
        assertThatThrownBy(() -> zlib.process("gunzip", bomb.toByteArray(), NONE)).hasMessageContaining("larger than " + ZlibApi.MAX_OUTPUT_BYTES);
        assertThatThrownBy(() -> zlib.process("gunzip", bomb.toByteArray(), new JsonObject().put("maxOutputLength", 1000L * 1000 * 1000 * 10)))
                .as("asking for more doesn't lift the cap").hasMessageContaining("larger than " + ZlibApi.MAX_OUTPUT_BYTES);
        assertThatThrownBy(() -> zlib.process("gunzip", bomb.toByteArray(), new JsonObject().put("maxOutputLength", 100))).hasMessageContaining("larger than 100");
    }

    @Test
    void badInputSaysWhatZlibSays() {
        JvmZlib zlib = new JvmZlib();
        assertThatThrownBy(() -> zlib.process("gunzip", "not gzip".getBytes(StandardCharsets.UTF_8), NONE)).hasMessage("incorrect header check");
        assertThatThrownBy(() -> zlib.process("inflate", "not zlib".getBytes(StandardCharsets.UTF_8), NONE)).hasMessage("incorrect header check");
        byte[] packed = zlib.process("gzip", text(100), NONE);
        assertThatThrownBy(() -> zlib.process("gunzip", java.util.Arrays.copyOf(packed, 20), NONE)).hasMessage("unexpected end of file");
        assertThatThrownBy(() -> zlib.process("brotliCompress", text(1), NONE)).hasMessageContaining("not available in the isolate");
        assertThatThrownBy(() -> zlib.process("gzip", text(1), new JsonObject().put("level", 10))).hasMessageContaining("out of range");
    }

    @Test
    void anExecutionCanHoldOnlySoManyStreams() {
        JvmZlib zlib = new JvmZlib();
        for (int i = 0; i < ZlibApi.MAX_OPEN; i++) {
            zlib.open("deflate", NONE);
        }
        assertThatThrownBy(() -> zlib.open("deflate", NONE)).hasMessageContaining("At most " + ZlibApi.MAX_OPEN);
    }
}
