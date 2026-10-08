package automately.core.host;

import automately.core.runtime.HostException;
import automately.core.runtime.api.ZlibApi;
import io.vertx.core.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * {@link ZlibApi} on the JVM's {@link Deflater} and {@link Inflater} (which are zlib itself) and Google's pure-Java
 * Brotli decoder (D-103). Gzip's header and trailer are written and read here, as zlib writes them, so output matches
 * Node's and a stream can be fed in any pieces. One per execution; its open streams are dropped with it.
 */
public final class JvmZlib implements ZlibApi {

    private static final int CHUNK = 64 * 1024;

    private final Map<Integer, Codec> open = new HashMap<>();
    private int next = 1;

    @Override
    public byte[] process(String kind, byte[] data, JsonObject options) {
        Codec codec = codec(kind, options);
        try {
            Output out = new Output(limit(options));
            codec.write(data, out);
            codec.finish(out);
            return out.bytes();
        } finally {
            codec.end();
        }
    }

    @Override
    public synchronized int open(String kind, JsonObject options) {
        if (open.size() >= MAX_OPEN) {
            throw HostException.limit("At most " + MAX_OPEN + " compression streams can be open at once");
        }
        int handle = next++;
        open.put(handle, codec(kind, options));
        return handle;
    }

    @Override
    public synchronized byte[] write(int handle, byte[] data) {
        Codec codec = stream(handle);
        try {
            Output out = new Output(codec.limit);
            codec.write(data, out);
            return out.bytes();
        } catch (RuntimeException e) {
            close(handle);
            throw e;
        }
    }

    @Override
    public synchronized byte[] finish(int handle) {
        Codec codec = stream(handle);
        try {
            Output out = new Output(codec.limit);
            codec.finish(out);
            return out.bytes();
        } finally {
            close(handle);
        }
    }

    @Override
    public synchronized void close(int handle) {
        Codec codec = open.remove(handle);
        if (codec != null) {
            codec.end();
        }
    }

    private Codec stream(int handle) {
        Codec codec = open.get(handle);
        if (codec == null) {
            throw new HostException("Error", "ERR_STREAM_DESTROYED", "The compression stream has already ended");
        }
        return codec;
    }

    private static int limit(JsonObject options) {
        Number asked = options.getNumber("maxOutputLength");
        return asked == null ? MAX_OUTPUT_BYTES : (int) Math.max(1, Math.min(asked.longValue(), MAX_OUTPUT_BYTES));
    }

    private static Codec codec(String kind, JsonObject options) {
        int level = options.getInteger("level", Deflater.DEFAULT_COMPRESSION);
        if (level < -1 || level > 9) {
            throw new HostException("RangeError", "ERR_OUT_OF_RANGE", "The value of \"options.level\" is out of range. It must be >= -1 and <= 9. Received " + level);
        }
        int strategy = options.getInteger("strategy", 0);
        if (strategy < 0 || strategy > 2) {
            throw HostException.invalid("The isolate's zlib supports strategies 0 (default), 1 (filtered) and 2 (Huffman only)");
        }
        byte[] dictionary = options.getString("dictionary") == null ? null : Base64.getDecoder().decode(options.getString("dictionary"));
        Codec codec = switch (kind == null ? "" : kind) {
            case "deflate" -> new Deflating(level, strategy, false, false, dictionary);
            case "deflateRaw" -> new Deflating(level, strategy, true, false, dictionary);
            case "gzip" -> new Deflating(level, strategy, true, true, dictionary);
            case "inflate" -> new Inflating(false, dictionary);
            case "inflateRaw" -> new Inflating(true, dictionary);
            case "gunzip" -> new Gunzip();
            case "unzip" -> new Unzip(dictionary);
            case "brotliDecompress" -> new BrotliDecoding();
            case "brotliCompress" -> throw new HostException("Error", "ERR_FEATURE_UNAVAILABLE_ON_PLATFORM",
                    "Brotli compression is not available in the isolate; gzip or deflate can be used instead");
            default -> throw HostException.invalid("Unknown compression: " + kind);
        };
        codec.limit = limit(options);
        return codec;
    }

    /** Output with a cap: past it, the call fails rather than fill the node's memory. */
    static final class Output {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Output(int limit) {
            this.limit = limit;
        }

        void write(byte[] buffer, int length) {
            if ((long) bytes.size() + length > limit) {
                throw new HostException("RangeError", "ERR_BUFFER_TOO_LARGE", "Cannot create a Buffer larger than " + limit + " bytes");
            }
            bytes.write(buffer, 0, length);
        }

        void write(byte[] buffer) {
            write(buffer, buffer.length);
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    static HostException dataError(String message) {
        return new HostException("Error", "Z_DATA_ERROR", message == null ? "invalid data" : message);
    }

    static HostException unexpectedEnd() {
        return new HostException("Error", "Z_BUF_ERROR", "unexpected end of file");
    }

    abstract static class Codec {
        int limit = MAX_OUTPUT_BYTES;

        abstract void write(byte[] data, Output out);

        abstract void finish(Output out);

        void end() {
        }
    }

    /** deflate (zlib wrapper), raw deflate, or gzip around raw deflate. */
    static final class Deflating extends Codec {
        private final Deflater deflater;
        private final boolean gzip;
        private final int level;
        private final int strategy;
        private final CRC32 crc = new CRC32();
        private long size;
        private boolean headerWritten;
        private final byte[] buffer = new byte[CHUNK];

        Deflating(int level, int strategy, boolean raw, boolean gzip, byte[] dictionary) {
            this.deflater = new Deflater(level, raw);
            this.deflater.setStrategy(strategy == 1 ? Deflater.FILTERED : strategy == 2 ? Deflater.HUFFMAN_ONLY : Deflater.DEFAULT_STRATEGY);
            if (dictionary != null) {
                deflater.setDictionary(dictionary);
            }
            this.gzip = gzip;
            this.level = level == Deflater.DEFAULT_COMPRESSION ? 6 : level;
            this.strategy = strategy;
        }

        private void header(Output out) {
            if (gzip && !headerWritten) {
                headerWritten = true;
                // As zlib writes it: no name, mtime 0, XFL from the level, OS 3 (Unix).
                int xfl = level == 9 ? 2 : (strategy >= 2 || level < 2 ? 4 : 0);
                out.write(new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, (byte) xfl, 3});
            }
        }

        @Override
        void write(byte[] data, Output out) {
            header(out);
            if (gzip) {
                crc.update(data);
                size += data.length;
            }
            deflater.setInput(data);
            while (!deflater.needsInput()) {
                int n = deflater.deflate(buffer, 0, buffer.length, Deflater.NO_FLUSH);
                out.write(buffer, n);
            }
        }

        @Override
        void finish(Output out) {
            header(out);
            deflater.finish();
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, n);
            }
            if (gzip) {
                long c = crc.getValue();
                out.write(new byte[] {(byte) c, (byte) (c >>> 8), (byte) (c >>> 16), (byte) (c >>> 24),
                        (byte) size, (byte) (size >>> 8), (byte) (size >>> 16), (byte) (size >>> 24)});
            }
        }

        @Override
        void end() {
            deflater.end();
        }
    }

    /** inflate (zlib wrapper) or raw inflate. */
    static final class Inflating extends Codec {
        private final Inflater inflater;
        private final boolean raw;
        private final byte[] dictionary;
        private final byte[] buffer = new byte[CHUNK];

        Inflating(boolean raw, byte[] dictionary) {
            this.inflater = new Inflater(raw);
            this.raw = raw;
            this.dictionary = dictionary;
            if (raw && dictionary != null) {
                inflater.setDictionary(dictionary);
            }
        }

        /** Inflates what it's given; answers the bytes after the end of the compressed data, if it ended. */
        byte[] feed(byte[] data, Output out, CRC32 crc) {
            if (inflater.finished()) {
                return data;
            }
            inflater.setInput(data);
            try {
                while (!inflater.finished()) {
                    int n = inflater.inflate(buffer);
                    if (n > 0) {
                        out.write(buffer, n);
                        if (crc != null) {
                            crc.update(buffer, 0, n);
                        }
                        continue;
                    }
                    if (inflater.needsDictionary()) {
                        if (dictionary == null) {
                            throw new HostException("Error", "Z_NEED_DICT", "Missing dictionary");
                        }
                        inflater.setDictionary(dictionary);
                        continue;
                    }
                    if (inflater.needsInput()) {
                        break;
                    }
                }
            } catch (DataFormatException e) {
                throw dataError(e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new HostException("Error", "Z_DATA_ERROR", "Bad dictionary");
            }
            if (!inflater.finished()) {
                return null;
            }
            int remaining = inflater.getRemaining();
            return java.util.Arrays.copyOfRange(data, data.length - remaining, data.length);
        }

        boolean finished() {
            return inflater.finished();
        }

        long total() {
            return inflater.getBytesWritten();
        }

        @Override
        void write(byte[] data, Output out) {
            feed(data, out, null);
        }

        @Override
        void finish(Output out) {
            if (!inflater.finished() && raw) {
                // Raw inflate may need one more (dummy) byte to see the end (Inflater's own note on "nowrap").
                feed(new byte[1], out, null);
            }
            if (!inflater.finished()) {
                throw unexpectedEnd();
            }
        }

        @Override
        void end() {
            inflater.end();
        }
    }

    /** gzip, read as zlib reads it: header, raw deflate, CRC and size; then another member if one follows. */
    static final class Gunzip extends Codec {
        private enum State { HEADER, BODY, TRAILER, AFTER }

        private State state = State.HEADER;
        private ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private Inflating body;
        private CRC32 crc;
        private boolean anyMember;

        @Override
        void write(byte[] data, Output out) {
            byte[] input = data;
            while (input != null && input.length > 0) {
                switch (state) {
                    case HEADER -> {
                        pending.writeBytes(input);
                        input = null;
                        byte[] buffered = pending.toByteArray();
                        int headerLength = headerLength(buffered);
                        if (headerLength < 0) {
                            break;
                        }
                        pending = new ByteArrayOutputStream();
                        body = new Inflating(true, null);
                        crc = new CRC32();
                        state = State.BODY;
                        input = java.util.Arrays.copyOfRange(buffered, headerLength, buffered.length);
                    }
                    case BODY -> {
                        input = body.feed(input, out, crc);
                        if (body.finished()) {
                            state = State.TRAILER;
                        }
                    }
                    case TRAILER -> {
                        pending.writeBytes(input);
                        input = null;
                        byte[] buffered = pending.toByteArray();
                        if (buffered.length < 8) {
                            break;
                        }
                        long storedCrc = le32(buffered, 0);
                        long storedSize = le32(buffered, 4);
                        if (storedCrc != crc.getValue()) {
                            throw dataError("incorrect data check");
                        }
                        if (storedSize != (body.total() & 0xffffffffL)) {
                            throw dataError("incorrect length check");
                        }
                        body.end();
                        body = null;
                        anyMember = true;
                        pending = new ByteArrayOutputStream();
                        state = State.AFTER;
                        input = java.util.Arrays.copyOfRange(buffered, 8, buffered.length);
                    }
                    case AFTER -> {
                        // Another member follows only if it starts like one; anything else after a member is ignored, as zlib does.
                        if ((input[0] & 0xff) == 0x1f) {
                            state = State.HEADER;
                        } else {
                            input = null;
                        }
                    }
                }
            }
        }

        /** The header's length once all of it is here; -1 until then. */
        private static int headerLength(byte[] b) {
            if (b.length >= 1 && (b[0] & 0xff) != 0x1f || b.length >= 2 && (b[1] & 0xff) != 0x8b) {
                throw dataError("incorrect header check");
            }
            if (b.length < 10) {
                return -1;
            }
            if (b[2] != 8) {
                throw dataError("unknown compression method");
            }
            int flags = b[3] & 0xff;
            int at = 10;
            if ((flags & 4) != 0) {
                if (b.length < at + 2) {
                    return -1;
                }
                at += 2 + ((b[at] & 0xff) | (b[at + 1] & 0xff) << 8);
            }
            for (int bit : new int[] {8, 16}) {
                if ((flags & bit) != 0) {
                    while (at < b.length && b[at] != 0) {
                        at++;
                    }
                    if (at >= b.length) {
                        return -1;
                    }
                    at++;
                }
            }
            if ((flags & 2) != 0) {
                at += 2;
            }
            return at <= b.length ? at : -1;
        }

        private static long le32(byte[] b, int at) {
            return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16 | (b[at + 3] & 0xffL) << 24;
        }

        @Override
        void finish(Output out) {
            if (state != State.AFTER || !anyMember) {
                throw unexpectedEnd();
            }
        }

        @Override
        void end() {
            if (body != null) {
                body.end();
            }
        }
    }

    /** gzip or zlib, whichever the data starts as. */
    static final class Unzip extends Codec {
        private final byte[] dictionary;
        private final ByteArrayOutputStream start = new ByteArrayOutputStream();
        private Codec chosen;

        Unzip(byte[] dictionary) {
            this.dictionary = dictionary;
        }

        @Override
        void write(byte[] data, Output out) {
            if (chosen == null) {
                start.writeBytes(data);
                byte[] first = start.toByteArray();
                if (first.length < 2) {
                    return;
                }
                chosen = (first[0] & 0xff) == 0x1f && (first[1] & 0xff) == 0x8b ? new Gunzip() : new Inflating(false, dictionary);
                chosen.write(first, out);
                return;
            }
            chosen.write(data, out);
        }

        @Override
        void finish(Output out) {
            if (chosen == null) {
                throw unexpectedEnd();
            }
            chosen.finish(out);
        }

        @Override
        void end() {
            if (chosen != null) {
                chosen.end();
            }
        }
    }

    /** Brotli: the decoder reads a whole input, so a stream collects it (at most the output cap) and decodes at the end. */
    static final class BrotliDecoding extends Codec {
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();

        @Override
        void write(byte[] data, Output out) {
            if ((long) input.size() + data.length > MAX_OUTPUT_BYTES) {
                throw HostException.limit("At most " + MAX_OUTPUT_BYTES + " bytes of Brotli input at once");
            }
            input.writeBytes(data);
        }

        @Override
        void finish(Output out) {
            try (InputStream in = new org.brotli.dec.BrotliInputStream(new java.io.ByteArrayInputStream(input.toByteArray()))) {
                byte[] buffer = new byte[CHUNK];
                for (int n; (n = in.read(buffer)) >= 0; ) {
                    out.write(buffer, n);
                }
            } catch (IOException e) {
                throw new HostException("Error", "ERR_BROTLI_DECOMPRESSION_FAILED", "Decompression failed");
            }
        }
    }
}
