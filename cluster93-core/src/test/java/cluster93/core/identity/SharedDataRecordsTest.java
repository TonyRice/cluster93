package cluster93.core.identity;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class SharedDataRecordsTest extends RecordsContract {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        records = new SharedDataRecords(vertx, Identity.MEMBERS);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }
}
