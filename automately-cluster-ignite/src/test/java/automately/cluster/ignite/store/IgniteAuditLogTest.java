package automately.cluster.ignite.store;

import automately.core.audit.AuditLogContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared AuditLogContract against the Ignite implementation. */
class IgniteAuditLogTest extends AuditLogContract {

    @BeforeEach
    void setUp() {
        log = TestIgnite.stores().audit;
    }
}
