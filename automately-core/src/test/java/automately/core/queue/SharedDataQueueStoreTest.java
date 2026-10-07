package automately.core.queue;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class SharedDataQueueStoreTest extends QueueStoreContract {

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataQueueStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }
}
