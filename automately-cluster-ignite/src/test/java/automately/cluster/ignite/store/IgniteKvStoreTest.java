package automately.cluster.ignite.store;

import automately.core.host.KvStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared KvStoreContract against the Ignite implementation. */
class IgniteKvStoreTest extends KvStoreContract {

    @BeforeEach
    void setUp() {
        kv = TestIgnite.stores().kv;
    }
}
