package cluster93.cluster.ignite.store;

import cluster93.core.host.DomainStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared DomainStoreContract against the Ignite implementation. */
class IgniteDomainStoreTest extends DomainStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().domains;
    }
}
