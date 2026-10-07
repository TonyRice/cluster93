package automately.core.files;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/** Writes one blob. Each write completes once the backend can take more, which is the upload's backpressure. */
public interface BlobWriter {

    Future<Void> write(Buffer data);

    /** Completes once the whole blob is durably stored. */
    Future<Void> finish();

    /** Discards what was written; the blob never becomes visible. */
    Future<Void> abort();
}
