package cluster93.core.host;

import cluster93.core.runtime.BodyStream;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.impl.VertxConnection;
import io.vertx.core.streams.ReadStream;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * A {@link BodyStream} over a Vert.x {@link ReadStream}. Pausing, resuming and cancelling hop onto the stream's
 * own context, so callers on other threads (such as an isolate) are safe.
 *
 * <p>A reader that pauses shares the stream with Vert.x itself (D-180). Vert.x reads and decodes a body into the
 * connection and hands it to the reader afterwards, and while the reader has the stream paused that handover stops:
 * the decoded chunks wait inside the connection. If the peer closes at that point, Vert.x fails the stream with
 * {@code Connection was closed} and throws away what it was holding. A peer that writes a whole body and then closes
 * — python's {@code http.server} speaks HTTP/1.0 and does exactly that — is then reported as a cut-short response
 * whenever the reader happened to be paused as the close was processed, which for a body larger than the reader's
 * window is most of the time. So when the connection closes before the body ended, we ask it for what it is holding
 * before Vert.x gets to judge the close: the end of the body reaches Vert.x, the close stays clean, and the reader
 * gets the tail on its own schedule as if the peer were still there. Only a body that really was cut short still
 * fails, and it is reported after the bytes that did arrive have been delivered.
 */
final class VertxBodyStream implements BodyStream {

    private final ReadStream<Buffer> stream;
    private final Context context;
    private final Runnable onCancel;

    /** Body bytes the peer declared, or -1 when the framing does not give a length. */
    private final long declared;

    /** Whether the peer framed the body as a chunked stream, whose end only Vert.x can tell us about. */
    private final boolean chunked;

    private Consumer<Buffer> onChunk;
    private Runnable onEnd;
    private Consumer<Throwable> onError;

    /** Whether the body has been reported to the reader, as ended or as failed. */
    private boolean reported;

    /** Body bytes handed to the reader. */
    private long delivered;

    /** Body bytes at the last check while waiting for a closed connection to hand over its tail. */
    private long seen;

    /** True while the connection's tail is being released, so the reader cannot pause it short. */
    private boolean releasing;

    /** The reader's last word on pausing, re-applied once a release is over. */
    private volatile boolean paused;

    /** The stream must already be paused. */
    VertxBodyStream(ReadStream<Buffer> stream, Context context, Runnable onCancel) {
        this.stream = stream;
        this.context = context;
        this.onCancel = onCancel;
        String length = header(stream, HttpHeaders.CONTENT_LENGTH);
        long parsed = -1;
        if (length != null) {
            try {
                parsed = Long.parseLong(length.trim());
            } catch (NumberFormatException ignored) {
                parsed = -1;
            }
        }
        String encoding = header(stream, HttpHeaders.TRANSFER_ENCODING);
        this.declared = parsed;
        this.chunked = parsed < 0 && encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    @Override
    public void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
        onChunk = chunk;
        onEnd = end;
        onError = error;
        context.runOnContext(v -> {
            stream.handler(this::arrived);
            stream.endHandler(e -> ended());
            stream.exceptionHandler(this::failed);
            HttpConnection connection = connection();
            if (connection instanceof VertxConnection http1) {
                // Only an HTTP/1 connection buffers the body this way, and only it can be asked to release it.
                http1.closeHandler(v2 -> closed(http1));
            }
        });
    }

    private void arrived(Buffer buffer) {
        delivered += buffer.length();
        onChunk.accept(buffer);
    }

    private void ended() {
        if (reported) {
            return;
        }
        reported = true;
        onEnd.run();
    }

    /** The peer is gone. Take back what Vert.x was holding for this body before believing the close. */
    private void closed(VertxConnection connection) {
        if (reported) {
            return;
        }
        // Vert.x only fails the stream over the holdover because the end of the body is still inside the connection,
        // so releasing it first lets a whole body end normally, on the reader's own schedule, and leaves a body that
        // really was cut short to fail as it should.
        connection.doResume();
    }

    private void failed(Throwable err) {
        if (reported) {
            return;
        }
        if (!(err instanceof HttpClosedException)) {
            report(err);
            return;
        }
        // The peer closed. Vert.x may have failed the stream over what it was holding: release that, and only report
        // the failure once nothing more is coming, so a body that turns out to be whole ends instead.
        release();
        settle(err, 0);
    }

    /** Waits for the released tail to stop arriving, then reports the failure unless the body is whole after all. */
    private void settle(Throwable err, int quiet) {
        context.runOnContext(v -> {
            if (reported) {
                return;
            }
            if (whole()) {
                ended();
            } else if (delivered != seen) {
                seen = delivered;
                settle(err, 0);
            } else if (quiet < 2) {
                settle(err, quiet + 1);
            } else {
                report(err);
            }
        });
    }

    /** Whether what the reader has is the body the peer framed: a declared length reached, or a body the close ends. */
    private boolean whole() {
        return declared >= 0 ? delivered == declared : !chunked;
    }

    private void report(Throwable err) {
        if (reported) {
            return;
        }
        reported = true;
        onError.accept(err);
    }

    /**
     * Releases what the connection was holding for this stream. The reader's pauses are ignored for the duration: the
     * backlog is what Vert.x had already read, so draining it does not grow without bound.
     */
    private void release() {
        if (releasing) {
            return;
        }
        releasing = true;
        stream.resume();
        context.runOnContext(v -> {
            releasing = false;
            if (paused) {
                stream.pause();
            }
        });
    }

    /** The connection this stream reads, or null when the read stream is not an HTTP one. */
    private HttpConnection connection() {
        if (stream instanceof HttpClientResponse response) {
            return response.request() == null ? null : response.request().connection();
        }
        if (stream instanceof HttpServerRequest request) {
            return request.connection();
        }
        return null;
    }

    /** The named header of the HTTP message this stream reads, or null when it is not one. */
    private static String header(ReadStream<Buffer> stream, CharSequence name) {
        if (stream instanceof HttpClientResponse response) {
            return response.getHeader(name);
        }
        if (stream instanceof HttpServerRequest request) {
            return request.getHeader(name);
        }
        return null;
    }

    @Override
    public void pause() {
        paused = true;
        context.runOnContext(v -> {
            if (!releasing) {
                stream.pause();
            }
        });
    }

    @Override
    public void resume() {
        paused = false;
        context.runOnContext(v -> stream.resume());
    }

    @Override
    public void cancel() {
        context.runOnContext(v -> {
            stream.handler(null);
            onCancel.run();
        });
    }
}
