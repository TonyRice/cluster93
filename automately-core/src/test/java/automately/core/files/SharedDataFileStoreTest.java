package automately.core.files;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.TimeUnit;

class SharedDataFileStoreTest extends FileStoreContract {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataFileStore(vertx, Long.MAX_VALUE);
    }

    @Override
    protected FileStore storeWithQuota(long quotaBytes) {
        return new SharedDataFileStore(vertx, quotaBytes);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
