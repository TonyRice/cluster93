package cluster93.cluster.ignite.store;

import cluster93.core.files.BlobStoreContract;
import cluster93.core.files.StorageCapacity;
import org.junit.jupiter.api.BeforeEach;

/** The file backend contract against the cluster backend on Ignite. */
class IgniteChunksTest extends BlobStoreContract {

    @BeforeEach
    void setUp() {
        blobs = TestIgnite.stores().chunks(StorageCapacity.UNLIMITED);
    }
}
