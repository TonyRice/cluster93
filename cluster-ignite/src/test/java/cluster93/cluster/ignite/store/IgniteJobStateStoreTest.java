package cluster93.cluster.ignite.store;

import cluster93.core.state.JobStateStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared JobStateStoreContract against the Ignite implementation. */
class IgniteJobStateStoreTest extends JobStateStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().state;
    }
}
