package cluster93.cluster.ignite.store;

import cluster93.core.identity.Identity;
import cluster93.core.identity.RecordsContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared RecordsContract against the Ignite implementation. */
class IgniteRecordsTest extends RecordsContract {

    @BeforeEach
    void setUp() {
        records = TestIgnite.stores().records.open(Identity.MEMBERS);
    }
}
