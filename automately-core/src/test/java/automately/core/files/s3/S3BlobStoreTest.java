package automately.core.files.s3;

import automately.core.files.BlobStoreContract;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The backend contract against a real S3-compatible server (needs Docker). */
class S3BlobStoreTest extends BlobStoreContract {

    private static S3Settings settings;
    private Vertx vertx;

    @BeforeAll
    static void bucket() throws Exception {
        assumeThat(S3TestServer.available()).as("Docker is available").isTrue();
        settings = S3TestServer.bucket("blobs");
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        blobs = new S3BlobStore(vertx, settings);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void largeBlobsAreOneObjectAndAbortsLeaveNoUploadBehind() throws Exception {
        S3Client s3 = new S3Client(vertx, settings);
        String id = write(bytes(S3BlobStore.PART_SIZE * 2 + 3, 9));
        assertThat(await(s3.listKeys(settings.prefix()))).contains(settings.prefix() + id);
        assertThat(await(s3.unfinishedUploads(settings.prefix() + id))).isEmpty();

        String aborted = java.util.UUID.randomUUID().toString();
        var writer = await(blobs.create(aborted, "application/test"));
        await(writer.write(io.vertx.core.buffer.Buffer.buffer(bytes(S3BlobStore.PART_SIZE + 1, 10))));
        assertThat(await(s3.unfinishedUploads(settings.prefix() + aborted))).hasSize(1);
        await(writer.abort());
        assertThat(await(s3.unfinishedUploads(settings.prefix() + aborted))).isEmpty();
    }

    @Test
    void deletingCleansUpAnUploadADeadNodeLeft() throws Exception {
        S3Client s3 = new S3Client(vertx, settings);
        String id = java.util.UUID.randomUUID().toString();
        var writer = await(blobs.create(id, "application/test"));
        await(writer.write(io.vertx.core.buffer.Buffer.buffer(bytes(S3BlobStore.PART_SIZE + 1, 11))));
        // The writer is never finished or aborted, as if its node had died.

        await(blobs.delete(id));

        assertThat(await(s3.unfinishedUploads(settings.prefix() + id))).isEmpty();
        assertThat(await(s3.listKeys(settings.prefix() + id))).isEmpty();
    }

    @Test
    void wrongCredentialsAndMissingBucketsFailTheCheck() {
        S3Settings wrongKey = new S3Settings(settings.endpoint(), settings.region(), settings.bucket(), settings.prefix(), true,
                settings.accessKeyId(), "not-the-secret", null);
        S3Settings noBucket = new S3Settings(settings.endpoint(), settings.region(), "does-not-exist", settings.prefix(), true,
                settings.accessKeyId(), settings.secretAccessKey(), null);

        for (S3Settings broken : List.of(wrongKey, noBucket)) {
            assertThatThrownBy(() -> await(new S3BlobStore(vertx, broken).check()))
                    .isInstanceOf(ExecutionException.class).cause().hasMessageContaining("Can't use s3://");
        }
    }
}
