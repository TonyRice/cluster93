package cluster93.core.auth;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class SharedDataAuthStoreTest extends AuthStoreContract {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataAuthStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }
}
