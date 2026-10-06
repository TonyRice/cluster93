package automately.core.runtime;

import io.vertx.core.buffer.Buffer;

import java.util.function.Consumer;

/**
 * A stream of body chunks (an HTTP request body, a fetch response body). It starts paused; the consumer sets
 * its handlers and then {@link #resume}s, pausing again whenever it falls behind. Methods may be called from
 * any thread.
 */
public interface BodyStream {

    void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error);

    void pause();

    void resume();

    /** Stops the stream early and releases what it holds (for example by resetting the connection). */
    void cancel();
}
