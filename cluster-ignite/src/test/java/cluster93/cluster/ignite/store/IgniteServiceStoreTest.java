package cluster93.cluster.ignite.store;

import cluster93.core.service.ServiceStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared ServiceStoreContract against the Ignite implementation. */
class IgniteServiceStoreTest extends ServiceStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().services;
    }
}
