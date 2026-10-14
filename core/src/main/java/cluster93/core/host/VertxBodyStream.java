package cluster93.core.host;

import cluster93.core.runtime.BodyStream;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.function.Consumer;

/**
 * A {@link BodyStream} over a Vert.x {@link ReadStream}. Pausing, resuming and cancelling hop onto the stream's
 * own context, so callers on other threads (such as an isolate) are safe.
 */
final class VertxBodyStream implements BodyStream {

    private final ReadStream<Buffer> stream;
    private final Context context;
    private final Runnable onCancel;

    /** The stream must already be paused. */
    VertxBodyStream(ReadStream<Buffer> stream, Context context, Runnable onCancel) {
        this.stream = stream;
        this.context = context;
        this.onCancel = onCancel;
    }

    @Override
    public void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
        context.runOnContext(v -> {
            stream.handler(chunk::accept);
            stream.endHandler(e -> end.run());
            stream.exceptionHandler(error::accept);
        });
    }

    @Override
    public void pause() {
        context.runOnContext(v -> stream.pause());
    }

    @Override
    public void resume() {
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
