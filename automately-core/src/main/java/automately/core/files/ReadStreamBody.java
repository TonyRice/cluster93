package automately.core.files;

import automately.core.runtime.BodyStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link BodyStream} over a Vert.x {@link ReadStream} (a file, an HTTP response) that is only opened when first
 * resumed. Handlers are wired in the callback that delivers the stream, so no chunk can arrive before them.
 */
final class ReadStreamBody implements BodyStream {

    private final Supplier<Future<? extends ReadStream<Buffer>>> opener;
    private final Runnable onClose;
    private Consumer<Buffer> chunk;
    private Runnable end;
    private Consumer<Throwable> error;
    private ReadStream<Buffer> stream;
    private boolean opening;
    private boolean paused = true;
    private boolean finished;

    ReadStreamBody(Supplier<Future<? extends ReadStream<Buffer>>> opener, Runnable onClose) {
        this.opener = opener;
        this.onClose = onClose;
    }

    /** A stream with nothing in it (an empty range). */
    static BodyStream empty() {
        return new ReadStreamBody(null, () -> { });
    }

    @Override
    public synchronized void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
        this.chunk = chunk;
        this.end = end;
        this.error = error;
    }

    @Override
    public synchronized void pause() {
        paused = true;
        if (stream != null) {
            stream.pause();
        }
    }

    @Override
    public void resume() {
        boolean open;
        synchronized (this) {
            paused = false;
            if (finished) {
                return;
            }
            if (stream != null) {
                stream.resume();
                return;
            }
            open = !opening;
            opening = true;
        }
        if (!open) {
            return;
        }
        if (opener == null) {
            finish();
            if (end != null) {
                end.run();
            }
            return;
        }
        opener.get().onComplete(ar -> {
            if (ar.failed()) {
                finish();
                if (error != null) {
                    error.accept(ar.cause());
                }
                return;
            }
            ReadStream<Buffer> opened = ar.result();
            opened.pause();
            opened.handler(data -> chunk.accept(data));
            opened.endHandler(v -> {
                finish();
                end.run();
            });
            opened.exceptionHandler(err -> {
                finish();
                error.accept(err);
            });
            boolean resumeNow;
            synchronized (this) {
                stream = opened;
                resumeNow = !paused && !finished;
            }
            if (resumeNow) {
                opened.resume();
            }
        });
    }

    @Override
    public void cancel() {
        finish();
        ReadStream<Buffer> current;
        synchronized (this) {
            current = stream;
        }
        if (current != null) {
            current.pause();
        }
    }

    private void finish() {
        boolean first;
        synchronized (this) {
            first = !finished;
            finished = true;
        }
        if (first) {
            onClose.run();
        }
    }
}
