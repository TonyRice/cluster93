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
 *
 * <p>Asking again (D-098) is told it's a retry, so the store can use a fresh connection rather than another pooled one that may
 * be closing too, and it waits a little longer each time. Only failures that brought no new bytes count towards
 * {@link #MAX_RESUMES}: a long download over a flaky connection keeps going as long as it makes progress.
 */
final class ResumingRangeBody implements BodyStream {

    static final int MAX_RESUMES = 3;
    /** The first wait before asking again; it doubles each time. */
    static final long BACKOFF_MS = 50;

    /** Opens the range from {@code offset} bytes into it to its end; {@code retry} says it's being asked for again. */
    @FunctionalInterface
    interface Opener {
        Future<? extends ReadStream<Buffer>> open(long offset, boolean retry);
    }

    /** Runs a task after a delay; immediately when there's no delay. */
    @FunctionalInterface
    interface Later {
        void run(long delayMs, Runnable task);

        Later NOW = (delayMs, task) -> task.run();
    }

    private final Opener openFrom;
    private final Later later;
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
    /** Failures in a row that brought no new bytes. */
    private int resumes;
    /** Every time it has asked again, so retries know they're retries. */
    private int attempts;
    /** How much had been delivered when the current stream was opened. */
    private long openedAt;

    ResumingRangeBody(LongFunction<Future<? extends ReadStream<Buffer>>> openFrom, long length) {
        this((offset, retry) -> openFrom.apply(offset), length, Later.NOW, java.util.function.UnaryOperator.identity());
    }

    ResumingRangeBody(Opener openFrom, long length, Later later, java.util.function.UnaryOperator<Throwable> describe) {
        this.openFrom = openFrom;
        this.length = length;
        this.later = later;
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
        boolean retry;
        synchronized (this) {
            from = delivered;
            openedAt = delivered;
            retry = attempts > 0;
        }
        openFrom.open(from, retry).onComplete(ar -> {
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
        long wait = 0;
        synchronized (this) {
            if (finished) {
                return;
            }
            current = null;
            opening = false;
            complete = delivered >= length;
            if (delivered > openedAt) {
                // It got further this time: only failures without progress count towards giving up.
                resumes = 0;
            }
            again = !complete && resumes < MAX_RESUMES && retryable(cause);
            if (again) {
                wait = BACKOFF_MS << resumes;
                resumes++;
                attempts++;
                opening = true;
            }
        }
        if (complete) {
            done();
        } else if (again) {
            later.run(wait, this::open);
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
