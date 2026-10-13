package cluster93.core.audit;

import cluster93.core.files.s3.S3Client;
import cluster93.core.files.s3.S3Settings;
import cluster93.core.files.s3.S3TestServer;
import cluster93.core.identity.Records;
import cluster93.core.identity.SharedDataRecords;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The audit export (D-073), against a real S3 server in Docker. */
class AuditExporterTest {

    private static S3Settings bucket;
    private S3Settings settings;
    private final SecureRandom random = new SecureRandom();
    private Vertx vertx;
    private S3Client s3;
    private MemoryAuditLog log;
    private Records state;

    @BeforeAll
    static void bucket() throws Exception {
        assumeThat(S3TestServer.available()).as("Docker is available").isTrue();
        bucket = S3TestServer.bucket("audit");
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        // Each test exports under its own prefix in the shared bucket.
        settings = new S3Settings(bucket.endpoint(), bucket.region(), bucket.bucket(), "run-" + System.nanoTime() + "/", bucket.pathStyle(),
                bucket.accessKeyId(), bucket.secretAccessKey(), bucket.sessionToken());
        s3 = new S3Client(vertx, settings);
        log = new MemoryAuditLog();
        state = new SharedDataRecords(vertx, AuditExporter.STATE);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private AuditExporter exporter(String holder) {
        return new AuditExporter(vertx, log, state, s3, holder, Duration.ofMinutes(5), Duration.ofMinutes(1));
    }

    private void record(long at, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            await(log.record(AuditEntry.of(at + i, random, "acme", "alice", "usr_1", "tok_1", false, "putService", "PUT", "/v1/x", 200, "10.0.0.1", null)));
        }
    }

    @Test
    void newEntriesGoOutOnceAsNdjsonAndRecentOnesWait() throws Exception {
        long old = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
        record(old, 3);
        record(System.currentTimeMillis(), 1);
        AuditExporter exporter = exporter("node-a");

        assertThat(await(exporter.run())).as("the recent one waits for the next round").isEqualTo(3);
        assertThat(await(exporter.run())).isZero();
        List<String> keys = await(s3.listKeys(settings.prefix() + "audit/"));
        assertThat(keys).hasSize(1);
        String body = await(s3.getRange(keys.getFirst(), 0, 10_000_000).compose(response -> { Future<io.vertx.core.buffer.Buffer> read = response.body(); response.resume(); return read; })).toString();
        assertThat(body.lines()).hasSize(3).allMatch(line -> line.contains("\"action\":\"putService\""));
        assertThat(await(exporter.exportedBefore())).isEqualTo(old + 2);

        record(old + 10, 2);
        assertThat(await(exporter.run())).isEqualTo(2);
        assertThat(await(s3.listKeys(settings.prefix() + "audit/"))).hasSize(2);
    }

    @Test
    void oneNodeExportsAtATime() throws Exception {
        record(System.currentTimeMillis() - Duration.ofHours(1).toMillis(), 2);
        assertThat(await(exporter("node-a").run())).isEqualTo(2);

        record(System.currentTimeMillis() - Duration.ofMinutes(30).toMillis(), 1);
        assertThat(await(exporter("node-b").run())).as("node-a holds the lease").isZero();
        assertThat(await(exporter("node-a").run())).isEqualTo(1);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
