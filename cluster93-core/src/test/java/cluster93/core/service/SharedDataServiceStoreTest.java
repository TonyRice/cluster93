package cluster93.core.service;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.TimeUnit;

class SharedDataServiceStoreTest extends ServiceStoreContract {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataServiceStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
