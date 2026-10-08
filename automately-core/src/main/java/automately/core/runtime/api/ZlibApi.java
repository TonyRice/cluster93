package automately.core.runtime.api;

import io.vertx.core.json.JsonObject;

/**
 * Compression for scripts (D-103): deflate, zlib, gzip and raw deflate both ways, and Brotli decompression, on the JVM's
 * zlib and a pure-Java Brotli decoder. Computation only. Every call's output is capped, so a small input can't
 * decompress into the node's memory (a zip bomb). Open streams are per-execution state.
 */
public interface ZlibApi {

    /** Most bytes one call outputs, whatever the script's {@code maxOutputLength} says (D-095). */
    int MAX_OUTPUT_BYTES = 32 * 1024 * 1024;
    /** Most streams an execution can have open at once. */
    int MAX_OPEN = 1_000;

    /**
     * Compresses or decompresses all of {@code data} at once. {@code kind} is one of deflate, inflate, gzip, gunzip,
     * deflateRaw, inflateRaw, unzip or brotliDecompress; options are Node's ({@code level}, {@code strategy},
     * {@code dictionary} as base64, {@code maxOutputLength}).
     */
    byte[] process(String kind, byte[] data, JsonObject options);

    /** Opens a stream of {@code kind}; answers its handle. */
    int open(String kind, JsonObject options);

    /** Feeds a stream; answers the output so far. */
    byte[] write(int handle, byte[] data);

    /** Ends a stream's input; answers the rest of its output and closes it. */
    byte[] finish(int handle);

    /** Drops a stream without finishing it. */
    void close(int handle);
}
