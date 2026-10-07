package automately.cluster.ignite.store;

import automately.core.files.BlobStoreContract;
import automately.core.files.StorageCapacity;
import org.junit.jupiter.api.BeforeEach;

/** The file backend contract against the cluster backend on Ignite. */
class IgniteChunksTest extends BlobStoreContract {

    @BeforeEach
    void setUp() {
        blobs = TestIgnite.stores().chunks(StorageCapacity.UNLIMITED);
    }
}
