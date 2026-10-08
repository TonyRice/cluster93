package automately.core.files.s3;

import automately.core.runtime.BodyStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * A ranged S3 download that carries on where it stopped if its connection closes partway (D-081). Some S3-compatible
 * servers close the connection as soon as they've sent the last byte ({@code Connection: close}); when the reader has
 * paused (backpressure), Vert.x reports that close as a failure and drops what it hadn't delivered yet. A range request
 * can be repeated safely, and we know how much we've passed on, so the rest is asked for again, up to
 * {@link #MAX_RESUMES} times. Nothing already delivered is sent twice.
 *
 * <p>Two more ways it was cut off under load (D-094): a close reported as an error just after the last byte (the
 * download was complete, so that's its end), and a request that failed to open because it went out on a pooled
 * connection the server was closing (retried like a resume). An answer that won't change, a 4xx, isn't retried.
 */
final class ResumingRangeBody implements BodyStream {

    static final int MAX_RESUMES = 3;

    /** Opens the range from {@code offset} bytes into it to its end. */
    private final LongFunction<Future<? extends ReadStream<Buffer>>> openFrom;
    private final long length;
    /** What the reader is told when it finally fails: the store's own wording for the cause. */
    private final java.util.function.UnaryOperator<Throwable> describe;
    private Consumer<Buffer> chunk;
    private Runnable end;
    private Consumer<Throwable> error;
    private ReadStream<Buffer> current;
    private boolean paused = true;
    private boolean opening;
    private boolean finished;
    private long delivered;
    private int resumes;

    ResumingRangeBody(LongFunction<Future<? extends ReadStream<Buffer>>> openFrom, long length) {
        this(openFrom, length, java.util.function.UnaryOperator.identity());
    }

    ResumingRangeBody(LongFunction<Future<? extends ReadStream<Buffer>>> openFrom, long length, java.util.function.UnaryOperator<Throwable> describe) {
        this.openFrom = openFrom;
        this.length = length;
        this.describe = describe;
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
        if (current != null) {
            current.pause();
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
            if (current != null) {
                current.resume();
                return;
            }
            open = !opening;
            opening = true;
        }
        if (open) {
            open();
        }
    }

    @Override
    public void cancel() {
        ReadStream<Buffer> stream;
        synchronized (this) {
            finished = true;
            stream = current;
        }
        if (stream != null) {
            stream.pause();
        }
    }

    private void open() {
        long from;
        synchronized (this) {
            from = delivered;
        }
        openFrom.apply(from).onComplete(ar -> {
            if (ar.failed()) {
                resumeOrFail(ar.cause());
                return;
            }
            ReadStream<Buffer> opened = ar.result();
            opened.pause();
            // A stream that has been given up on can still hand over chunks it had buffered: those are ignored, since the
            // stream that replaced it asks for exactly the bytes not yet delivered.
            opened.handler(data -> {
                synchronized (this) {
                    if (finished || current != opened) {
                        return;
                    }
                    delivered += data.length();
                }
                chunk.accept(data);
            });
            opened.endHandler(v -> {
                boolean complete;
                synchronized (this) {
                    if (current != opened) {
                        return;
                    }
                    complete = delivered >= length;
                }
                if (complete) {
                    done();
                } else {
                    resumeOrFail(new IllegalStateException("The download ended early, at " + delivered + " of " + length + " bytes"));
                }
            });
            opened.exceptionHandler(err -> {
                synchronized (this) {
                    if (current != opened) {
                        return;
                    }
                }
                resumeOrFail(err);
            });
            boolean resumeNow;
            synchronized (this) {
                opening = false;
                current = opened;
                resumeNow = !paused && !finished;
            }
            if (resumeNow) {
                opened.resume();
            }
        });
    }

    /**
     * Asks for the rest again if it can, keeping whether the reader wants data now. A download that already delivered
     * every byte has simply ended, whatever the connection said afterwards.
     */
    private void resumeOrFail(Throwable cause) {
        boolean again;
        boolean complete;
        synchronized (this) {
            if (finished) {
                return;
            }
            current = null;
            opening = false;
            complete = delivered >= length;
            again = !complete && resumes < MAX_RESUMES && retryable(cause);
            if (again) {
                resumes++;
                opening = true;
            }
        }
        if (complete) {
            done();
        } else if (again) {
            open();
        } else {
            fail(cause);
        }
    }

    /** Whether asking again could help: not when S3 answered with a client error (no such key, access denied, …). */
    static boolean retryable(Throwable cause) {
        return !(cause instanceof S3Client.S3Exception e && e.status() >= 400 && e.status() < 500);
    }

    private void done() {
        synchronized (this) {
            if (finished) {
                return;
            }
            finished = true;
        }
        end.run();
    }

    private void fail(Throwable cause) {
        synchronized (this) {
            if (finished) {
                return;
            }
            finished = true;
            opening = false;
        }
        error.accept(describe.apply(cause));
    }
}
