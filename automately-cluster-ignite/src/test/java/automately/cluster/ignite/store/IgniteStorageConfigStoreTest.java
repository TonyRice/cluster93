package automately.cluster.ignite.store;

import automately.core.storage.StorageConfigStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared StorageConfigStoreContract against the Ignite implementation. */
class IgniteStorageConfigStoreTest extends StorageConfigStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().storage;
    }
}
