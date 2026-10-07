package automately.core.files.s3;

import automately.core.files.FileStoreContract;
import automately.core.files.SharedDataFileStore;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assumptions.assumeThat;

/** The whole file store contract with the bytes in an S3-compatible bucket (needs Docker). */
class S3FileStoreTest extends FileStoreContract {

    private static S3Settings settings;
    private Vertx vertx;

    @BeforeAll
    static void bucket() throws Exception {
        assumeThat(S3TestServer.available()).as("Docker is available").isTrue();
        settings = S3TestServer.bucket("files");
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataFileStore(vertx, new S3BlobStore(vertx, settings));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
