package cluster93.core.files;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/** An upload in progress. Nothing is visible until {@link #finish()}; then the new version replaces the old atomically. */
public interface FileWriter {

    /** Completes once the data is stored (or buffered), so callers naturally apply backpressure. */
    Future<Void> write(Buffer data);

    Future<FileInfo> finish();

    /** Discards the upload; its chunks are cleaned up. */
    Future<Void> abort();
}
