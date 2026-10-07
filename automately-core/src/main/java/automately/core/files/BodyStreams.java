package automately.core.files;

import automately.core.runtime.BodyStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.function.Supplier;

/** Factories for {@link BodyStream}s over Vert.x streams, for the blob backends. */
public final class BodyStreams {

    private BodyStreams() {
    }

    /** A stream that only exists once {@code later} completes (its store had to be looked up first). */
    public static BodyStream deferred(Future<BodyStream> later) {
        return new BodyStream() {
            private java.util.function.Consumer<Buffer> chunk;
            private Runnable end;
            private java.util.function.Consumer<Throwable> error;
            private boolean resumed;
            private boolean cancelled;
            private boolean failed;

            @Override
            public synchronized void handler(java.util.function.Consumer<Buffer> chunk, Runnable end, java.util.function.Consumer<Throwable> error) {
                this.chunk = chunk;
                this.end = end;
                this.error = error;
                later.onSuccess(stream -> stream.handler(chunk, end, error));
            }

            @Override
            public synchronized void pause() {
                resumed = false;
                later.onSuccess(BodyStream::pause);
            }

            @Override
            public synchronized void resume() {
                resumed = true;
                later.onComplete(ar -> {
                    if (ar.failed()) {
                        java.util.function.Consumer<Throwable> once;
                        synchronized (this) {
                            once = failed ? null : error;
                            failed = true;
                        }
                        if (once != null) {
                            once.accept(ar.cause());
                        }
                    } else if (resumed && !cancelled) {
                        ar.result().resume();
                    }
                });
            }

            @Override
            public synchronized void cancel() {
                cancelled = true;
                later.onSuccess(BodyStream::cancel);
            }
        };
    }

    /** Opened on first resume; {@code opener} null means an empty stream. {@code onClose} runs once, however it ends. */
    public static BodyStream lazy(Supplier<Future<? extends ReadStream<Buffer>>> opener, Runnable onClose) {
        return opener == null ? ReadStreamBody.empty() : new ReadStreamBody(opener, onClose);
    }
}
