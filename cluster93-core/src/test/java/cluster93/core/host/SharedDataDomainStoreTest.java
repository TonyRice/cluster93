package cluster93.core.host;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.TimeUnit;

class SharedDataDomainStoreTest extends DomainStoreContract {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataDomainStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
