package automately.core.files;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class SharedDataChunksTest extends BlobStoreContract {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        blobs = new SharedDataChunks(vertx, StorageCapacity.UNLIMITED);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }
}
