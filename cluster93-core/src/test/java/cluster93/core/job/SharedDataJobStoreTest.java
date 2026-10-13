package cluster93.core.job;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.TimeUnit;

class SharedDataJobStoreTest extends JobStoreContract {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataJobStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
