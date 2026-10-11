package automately.runner.protocol.transport;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges standard Java {@link InputStream} and {@link OutputStream} pipes into Vert.x {@link ReadStream} and {@link WriteStream}.
 */
public final class StreamBridge {

    private StreamBridge() {
    }

    /**
     * Wraps an {@link InputStream} as a Vert.x {@link ReadStream}. Spawns a virtual or daemon reader thread
     * that pumps chunks into the Vert.x context.
     */
    public static ReadStream<Buffer> readStream(Vertx vertx, InputStream in) {
        return new InputStreamReadStream(vertx, in);
    }

    /**
     * Wraps an {@link OutputStream} as a Vert.x {@link WriteStream}.
     */
    public static WriteStream<Buffer> writeStream(Vertx vertx, OutputStream out) {
        return new OutputStreamWriteStream(vertx, out);
    }

    private static final class InputStreamReadStream implements ReadStream<Buffer> {
        private final Vertx vertx;
        private final InputStream in;
        private final Context context;
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Handler<Buffer> dataHandler;
        private Handler<Void> endHandler;
        private Handler<Throwable> exceptionHandler;
        private Thread readerThread;

        InputStreamReadStream(Vertx vertx, InputStream in) {
            this.vertx = Objects.requireNonNull(vertx, "vertx");
            this.in = Objects.requireNonNull(in, "in");
            this.context = vertx.getOrCreateContext();
        }

        private synchronized void startPump() {
            if (readerThread != null) {
                return;
            }
            readerThread = Thread.ofVirtual().name("stream-bridge-reader").start(this::pumpLoop);
        }

        private void pumpLoop() {
            byte[] buf = new byte[8192];
            try {
                while (!closed.get()) {
                    while (paused.get() && !closed.get()) {
                        Thread.sleep(10);
                    }
                    int read = in.read(buf);
                    if (read == -1) {
                        break;
                    }
                    if (read > 0) {
                        Buffer chunk = Buffer.buffer(read);
                        chunk.appendBytes(buf, 0, read);
                        dispatch(() -> {
                            if (dataHandler != null) {
                                dataHandler.handle(chunk);
                            }
                        });
                    }
                }
                dispatch(() -> {
                    if (endHandler != null) {
                        endHandler.handle(null);
                    }
                });
            } catch (Exception e) {
                if (!closed.get()) {
                    dispatch(() -> {
                        if (exceptionHandler != null) {
                            exceptionHandler.handle(e);
                        }
                    });
                }
            }
        }

        /** Hands {@code task} to the Vert.x context, unless the context's event loop has already terminated. */
        private void dispatch(Runnable task) {
            if (closed.get()) {
                return;
            }
            try {
                context.runOnContext(v -> task.run());
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // The node is shutting down and took the event loop with it; nothing left to deliver to.
                closed.set(true);
            }
        }

        @Override
        public synchronized ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.dataHandler = handler;
            if (handler != null) {
                startPump();
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused.set(true);
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            paused.set(false);
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return resume();
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }
    }

    private static final class OutputStreamWriteStream implements WriteStream<Buffer> {
        private final Vertx vertx;
        private final OutputStream out;

        OutputStreamWriteStream(Vertx vertx, OutputStream out) {
            this.vertx = Objects.requireNonNull(vertx, "vertx");
            this.out = Objects.requireNonNull(out, "out");
        }

        @Override
        public synchronized Future<Void> write(Buffer data) {
            if (data == null || data.length() == 0) {
                return Future.succeededFuture();
            }
            try {
                out.write(data.getBytes());
                out.flush();
                return Future.succeededFuture();
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }

        @Override
        public Future<Void> end() {
            try {
                out.flush();
                return Future.succeededFuture();
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }

        @Override public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) { return this; }
        @Override public boolean writeQueueFull() { return false; }
        @Override public WriteStream<Buffer> drainHandler(Handler<Void> handler) { return this; }
        @Override public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) { return this; }
    }
}
