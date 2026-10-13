package cluster93.cluster.ignite.store;

import cluster93.core.host.KvStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared KvStoreContract against the Ignite implementation. */
class IgniteKvStoreTest extends KvStoreContract {

    @BeforeEach
    void setUp() {
        kv = TestIgnite.stores().kv;
    }
}
