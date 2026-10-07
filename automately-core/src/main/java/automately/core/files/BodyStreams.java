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

    /** Opened on first resume; {@code opener} null means an empty stream. {@code onClose} runs once, however it ends. */
    public static BodyStream lazy(Supplier<Future<? extends ReadStream<Buffer>>> opener, Runnable onClose) {
        return opener == null ? ReadStreamBody.empty() : new ReadStreamBody(opener, onClose);
    }
}
