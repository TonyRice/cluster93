package automately.cluster.ignite.store;

import automately.core.files.FileStore;
import automately.core.files.FileStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared FileStoreContract against the Ignite implementation. */
class IgniteFileStoreTest extends FileStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().files(Long.MAX_VALUE, automately.core.files.StorageCapacity.UNLIMITED);
    }

    @Override
    protected FileStore storeWithQuota(long quotaBytes) {
        return TestIgnite.stores().files(quotaBytes, automately.core.files.StorageCapacity.UNLIMITED);
    }
}
