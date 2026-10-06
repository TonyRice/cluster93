package automately.cluster.ignite.store;

import automately.core.job.JobStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared JobStoreContract against the Ignite implementation. */
class IgniteJobStoreTest extends JobStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().jobs;
    }
}
