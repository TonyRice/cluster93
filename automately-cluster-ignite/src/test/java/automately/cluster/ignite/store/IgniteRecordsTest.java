package automately.cluster.ignite.store;

import automately.core.identity.Identity;
import automately.core.identity.RecordsContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared RecordsContract against the Ignite implementation. */
class IgniteRecordsTest extends RecordsContract {

    @BeforeEach
    void setUp() {
        records = TestIgnite.stores().records.open(Identity.MEMBERS);
    }
}
