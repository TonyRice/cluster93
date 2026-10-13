package cluster93.cluster.ignite.store;

import cluster93.core.files.FileStore;
import cluster93.core.files.FileStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared FileStoreContract against the Ignite implementation. */
class IgniteFileStoreTest extends FileStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().files(TestIgnite.stores().chunks(cluster93.core.files.StorageCapacity.UNLIMITED));
    }
}
