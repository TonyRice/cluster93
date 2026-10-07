package automately.cluster.ignite.store;

import automately.core.files.FileStore;
import automately.core.files.FileStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared FileStoreContract against the Ignite implementation. */
class IgniteFileStoreTest extends FileStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().files(automately.core.files.StorageCapacity.UNLIMITED);
    }
}
