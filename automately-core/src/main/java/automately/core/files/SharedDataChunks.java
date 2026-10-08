package automately.core.files;

import io.vertx.core.Future;
import automately.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.shareddata.AsyncMap;

/** The {@code cluster} backend's chunks in a Vert.x {@link AsyncMap}, for a single in-memory node. */
public final class SharedDataChunks extends ChunkedBlobStore {

    private final SharedMap<String, Buffer> chunks;

    public SharedDataChunks(Vertx vertx, StorageCapacity capacity) {
        super(vertx, capacity);
        this.chunks = new SharedMap<>(vertx, "automately.file-chunks");
    }

    @Override
    protected Future<Void> putChunk(String blobId, int index, Buffer data) {
        return chunks.compose(m -> m.put(blobId + ":" + index, data));
    }

    @Override
    protected Future<Buffer> getChunk(String blobId, int index) {
        return chunks.compose(m -> m.get(blobId + ":" + index));
    }

    @Override
    protected Future<Void> removeChunk(String blobId, int index) {
        return chunks.compose(m -> m.remove(blobId + ":" + index)).mapEmpty();
    }
}
