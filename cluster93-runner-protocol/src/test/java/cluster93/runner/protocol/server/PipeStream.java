package cluster93.runner.protocol.server;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal in-memory {@link ReadStream}/{@link WriteStream} of framed bytes for protocol tests, where a test can
 * deliver bytes and simulate EOF explicitly.
 */
final class PipeStream implements ReadStream<Buffer>, WriteStream<Buffer> {

    private Handler<Buffer> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    final List<Buffer> written = new ArrayList<>();

    /** Hands {@code bytes} to the registered data handler, as the transport would. */
    void deliver(Buffer bytes) {
        if (dataHandler != null) {
            dataHandler.handle(bytes);
        }
    }

    /** Simulates the peer closing its end of the pipe (EOF). */
    void simulateEnd() {
        if (endHandler != null) {
            endHandler.handle(null);
        }
    }

    /** Simulates a transport error. */
    void simulateError(Throwable error) {
        if (exceptionHandler != null) {
            exceptionHandler.handle(error);
        }
    }

    @Override
    public PipeStream handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
        return this;
    }

    @Override
    public PipeStream exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }

    @Override
    public PipeStream endHandler(Handler<Void> handler) {
        this.endHandler = handler;
        return this;
    }

    @Override
    public PipeStream pause() {
        return this;
    }

    @Override
    public PipeStream resume() {
        return this;
    }

    @Override
    public PipeStream fetch(long amount) {
        return this;
    }

    @Override
    public Future<Void> write(Buffer data) {
        written.add(data);
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
        return Future.succeededFuture();
    }

    @Override
    public PipeStream setWriteQueueMaxSize(int maxSize) {
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        return false;
    }

    @Override
    public PipeStream drainHandler(Handler<Void> handler) {
        return this;
    }
}
