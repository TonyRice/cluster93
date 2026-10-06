package automately.cluster.ignite.store;

import automately.core.state.JobStateStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared JobStateStoreContract against the Ignite implementation. */
class IgniteJobStateStoreTest extends JobStateStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().state;
    }
}
