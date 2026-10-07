package automately.cluster.ignite.store;

import automately.core.auth.AuthStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared AuthStoreContract against the Ignite implementation. */
class IgniteAuthStoreTest extends AuthStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().auth;
    }
}
