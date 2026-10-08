package automately.cluster.ignite.store;

import automately.core.packages.PackageStore;
import automately.core.packages.PackageStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared PackageStoreContract on Ignite's tables. */
class IgnitePackageStoreTest extends PackageStoreContract {

    @BeforeEach
    void setUp() {
        IgniteStores stores = TestIgnite.stores();
        vertx = TestIgnite.vertx();
        versions = stores.records.open(PackageStore.VERSIONS);
        blobs = stores.records.open(PackageStore.BLOBS);
    }
}
