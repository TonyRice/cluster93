package automately.server;

import automately.core.files.s3.S3Client;
import automately.core.files.s3.S3Settings;
import automately.core.files.s3.S3TestServer;
import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The file backends on real nodes (D-056): S3 behind a two-node cluster, and a disk folder. */
class FileBackendsTest {

    private static final String TENANT = "media";
    private static final int MB = 1024 * 1024;

    private Vertx clientVertx;
    private WebClient client;

    @TempDir
    Path dir;

    @BeforeEach
    void client() {
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterEach
    void closeClient() throws Exception {
        await(clientVertx.close());
    }

    private static Map<String, String> config(String nodeId, boolean clustered, Map<String, String> files) {
        Map<String, String> env = new HashMap<>(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_INGRESS_DOMAIN", "apps.test"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_CLUSTERED", String.valueOf(clustered)),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "30701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1")));
        env.putAll(files);
        return env;
    }

    private static Map<String, String> s3Env(S3Settings s3, String secret) {
        return Map.of("AUTOMATELY_FILES_BACKEND", "s3", "AUTOMATELY_S3_ENDPOINT", s3.endpoint().toString(),
                "AUTOMATELY_S3_BUCKET", s3.bucket(), "AUTOMATELY_S3_ACCESS_KEY_ID", s3.accessKeyId(),
                "AUTOMATELY_S3_SECRET_ACCESS_KEY", secret, "AUTOMATELY_S3_PREFIX", "cluster-files/");
    }

    @Test
    void aClusterServesFilesFromS3() throws Exception {
        assumeThat(S3TestServer.available()).as("Docker is available").isTrue();
        S3Settings s3 = S3TestServer.bucket("cluster");
        RunningNode nodeA = await(Automately.start(ServerConfig.fromEnv(config("node-a", true, s3Env(s3, s3.secretAccessKey())))));
        RunningNode nodeB = await(Automately.start(ServerConfig.fromEnv(config("node-b", true, s3Env(s3, s3.secretAccessKey())))));
        try {
            TestAuth.use(nodeA);
            String bearer = TestAuth.bearer(TENANT);
            byte[] video = new byte[20 * MB + 9];
            new Random(1).nextBytes(video);

            HttpResponse<Buffer> stored = await(client.put(nodeA.httpPort(), "localhost", "/v1/files/videos/intro.mp4")
                    .putHeader("Authorization", bearer).putHeader("Content-Type", "video/mp4").sendBuffer(Buffer.buffer(video)));
            assertThat(stored.statusCode()).as(stored.bodyAsString()).isEqualTo(201);

            HttpResponse<Buffer> range = await(client.get(nodeB.httpPort(), "localhost", "/v1/files/videos/intro.mp4")
                    .putHeader("Authorization", bearer).putHeader("Range", "bytes=8388600-8388615").send());
            assertThat(range.statusCode()).isEqualTo(206);
            assertThat(range.body().getBytes()).as("across a multipart boundary").isEqualTo(Arrays.copyOfRange(video, 8388600, 8388616));
            HttpResponse<Buffer> whole = await(client.get(nodeB.httpPort(), "localhost", "/v1/files/videos/intro.mp4")
                    .putHeader("Authorization", bearer).send());
            assertThat(whole.body().getBytes()).isEqualTo(video);

            // The bytes really are in the bucket, as one object.
            S3Client bucket = new S3Client(clientVertx, s3);
            List<String> keys = await(bucket.listKeys("cluster-files/"));
            assertThat(keys).hasSize(1);

            // And a service serves the file from S3 through the ingress, with ranges.
            HttpResponse<Buffer> service = await(client.put(nodeA.httpPort(), "localhost", "/v1/services/cdn").putHeader("Authorization", bearer)
                    .sendJsonObject(new JsonObject().put("script", """
                            export default async (input, ctx) => { await ctx.serve((req) => files.stream('videos' + req.path)); return ctx.stopRequested; }
                            """)));
            assertThat(service.statusCode()).as(service.bodyAsString()).isEqualTo(201);
            HttpResponse<Buffer> served = retryIngress(nodeB, "cdn--media.apps.test", "/intro.mp4", "bytes=100-199");
            assertThat(served.statusCode()).isEqualTo(206);
            assertThat(served.body().getBytes()).isEqualTo(Arrays.copyOfRange(video, 100, 200));
        } finally {
            await(nodeB.stop());
            await(nodeA.stop());
        }
    }

    @Test
    void aNodeStoresFilesInAFolder() throws Exception {
        Path files = dir.resolve("files");
        RunningNode node = await(Automately.start(ServerConfig.fromEnv(config("disk", false,
                Map.of("AUTOMATELY_FILES_BACKEND", "disk", "AUTOMATELY_FILES_DIR", files.toString())))));
        try {
            TestAuth.use(node);
            String bearer = TestAuth.bearer(TENANT);
            HttpResponse<Buffer> stored = await(client.put(node.httpPort(), "localhost", "/v1/files/notes/a.txt")
                    .putHeader("Authorization", bearer).sendBuffer(Buffer.buffer("on disk")));
            assertThat(stored.statusCode()).isEqualTo(201);

            assertThat(await(client.get(node.httpPort(), "localhost", "/v1/files/notes/a.txt").putHeader("Authorization", bearer).send())
                    .bodyAsString()).isEqualTo("on disk");
            try (Stream<Path> walk = Files.walk(files)) {
                assertThat(walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(".")).toList()).hasSize(1);
            }
        } finally {
            await(node.stop());
        }
    }

    @Test
    void aNodeWithAnUnusableBackendRefusesToStart() throws Exception {
        assumeThat(S3TestServer.available()).as("Docker is available").isTrue();
        S3Settings s3 = S3TestServer.bucket("refused");

        assertThatThrownBy(() -> await(Automately.start(ServerConfig.fromEnv(config("bad-s3", false, s3Env(s3, "wrong-secret"))))))
                .hasMessageContaining("Can't use s3://refused");
        assertThatThrownBy(() -> ServerConfig.fromEnv(config("bad-disk", false, Map.of("AUTOMATELY_FILES_BACKEND", "disk"))))
                .hasMessageContaining("AUTOMATELY_FILES_DIR");
        assertThatThrownBy(() -> ServerConfig.fromEnv(config("bad-kind", false, Map.of("AUTOMATELY_FILES_BACKEND", "ftp"))))
                .hasMessageContaining("cluster, disk, s3");
    }

    private HttpResponse<Buffer> retryIngress(RunningNode node, String host, String path, String range) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            HttpResponse<Buffer> response = await(client.get(node.ingressPort(), "localhost", path).putHeader("Host", host)
                    .putHeader("Range", range).send());
            if (response.statusCode() != 503 && response.statusCode() != 404 || System.nanoTime() > deadline) {
                return response;
            }
            Thread.sleep(100);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
