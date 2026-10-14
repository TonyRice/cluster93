package cluster93.cluster.ignite.store;

import cluster93.cluster.ignite.IgniteCluster;
import cluster93.core.files.ChunkedBlobStore;
import cluster93.core.files.StorageCapacity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.CacheConfiguration;

/**
 * The {@code cluster} file backend on Ignite: 1 MB chunks as raw bytes in their own cache, replicated like every
 * durable table, so any node can stream any file and files survive node loss and full restarts (D-040).
 */
public final class IgniteChunks extends ChunkedBlobStore {

    static final String CACHE = "FILE_CHUNKS";

    private final IgniteCache<String, byte[]> chunks;

    public IgniteChunks(Vertx vertx, Ignite ignite, StorageCapacity capacity) {
        super(vertx, capacity);
        this.chunks = ignite.cache(CACHE);
    }

    /** The chunk cache. Blocking; called at startup. */
    static void createCache(Ignite ignite, int backups) {
        ignite.getOrCreateCache(new CacheConfiguration<String, byte[]>(CACHE)
                .setCacheMode(CacheMode.PARTITIONED)
                .setBackups(backups)
                .setAffinity(new org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction(false, IgniteCluster.PARTITIONS))
                .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                .setWriteSynchronizationMode(org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC)
                .setDataRegionName(IgniteCluster.DATA_REGION));
    }

    @Override
    protected Future<Void> putChunk(String blobId, int index, Buffer data) {
        return IgniteFutures.async(vertx, () -> chunks.putAsync(blobId + ":" + index, data.getBytes()));
    }

    @Override
    protected Future<Buffer> getChunk(String blobId, int index) {
        return IgniteFutures.async(vertx, () -> chunks.getAsync(blobId + ":" + index)).map(bytes -> bytes == null ? null : Buffer.buffer(bytes));
    }

    @Override
    protected Future<Void> removeChunk(String blobId, int index) {
        return IgniteFutures.async(vertx, () -> chunks.removeAsync(blobId + ":" + index)).mapEmpty();
    }
}
