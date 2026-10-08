package automately.core.audit;

import org.junit.jupiter.api.BeforeEach;

class MemoryAuditLogTest extends AuditLogContract {

    @BeforeEach
    void setUp() {
        log = new MemoryAuditLog();
    }
}
