package automately.core.packages;

import automately.core.identity.SharedDataRecords;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class SharedDataPackageStoreTest extends PackageStoreContract {

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        versions = new SharedDataRecords(vertx, PackageStore.VERSIONS);
        blobs = new SharedDataRecords(vertx, PackageStore.BLOBS);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }
}
