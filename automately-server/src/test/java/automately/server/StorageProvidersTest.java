package automately.server;

import automately.core.files.s3.S3Client;
import automately.core.files.s3.S3Settings;
import automately.core.files.s3.S3TestServer;
import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tenants' own storage on a real node (D-057): the admin's policy, an S3 bucket with the tenant's keys and a disk
 * folder under the admin's root, routing by path prefix, what counts against the quota, and what's refused.
 */
class StorageProvidersTest {

    private static final int KB = 1024;

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static S3Settings bucket;
    private static Path diskRoot;
    private static String bearer;

    @BeforeAll
    static void start() throws Exception {
        assumeThat(S3TestServer.available()).as("Docker is available").isTrue();
        bucket = S3TestServer.bucket("tenant-own");
        diskRoot = Files.createTempDirectory("automately-disk-root");
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "storage"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_SECRETS_KEY", "storage-test-secrets-key-0123456789"),
                Map.entry("AUTOMATELY_DISK_ROOT", diskRoot.toString()),
                Map.entry("AUTOMATELY_TENANT_STORAGE_MB", "5"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64")))));
        TestAuth.use(node);
        bearer = TestAuth.bearer("acme");
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        if (node != null) {
            await(clientVertx.close());
            await(node.stop());
        }
    }

    private static HttpResponse<Buffer> call(String method, String path, String auth, JsonObject body) throws Exception {
        var request = client.request(io.vertx.core.http.HttpMethod.valueOf(method), node.httpPort(), "localhost", path).putHeader("Authorization", auth);
        return await(body == null ? request.send() : request.sendJsonObject(body));
    }

    private static HttpResponse<Buffer> upload(String path, byte[] data) throws Exception {
        return await(client.put(node.httpPort(), "localhost", "/v1/files/" + path).putHeader("Authorization", bearer).sendBuffer(Buffer.buffer(data)));
    }

    private static JsonObject s3Provider(String secret) {
        return new JsonObject().put("type", "s3").put("endpoint", bucket.endpoint().toString()).put("bucket", bucket.bucket())
                .put("prefix", "acme/").put("accessKeyId", bucket.accessKeyId()).put("secretAccessKey", secret);
    }

    private static JsonObject policy(boolean s3, boolean allowPrivate) {
        JsonArray allow = new JsonArray().add("disk");
        if (s3) {
            allow.add("s3");
        }
        return new JsonObject().put("allow", allow).put("diskAllotmentMb", 1).put("allowPrivateEndpoints", allowPrivate);
    }

    private static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void aTenantStoresFilesInItsOwnBucketAndFolderUnderTheAdminsPolicy() throws Exception {
        // Nothing until the admin allows it.
        HttpResponse<Buffer> refused = call("PUT", "/v1/storage/providers/bucket", bearer, s3Provider(bucket.secretAccessKey()));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("storage_disabled");

        // Allowed, but the test bucket is on a private address, which the policy doesn't allow yet.
        assertThat(call("PUT", "/v1/tenants/acme/storage-policy", TestAuth.ADMIN, policy(true, false)).statusCode()).isEqualTo(200);
        HttpResponse<Buffer> privateEndpoint = call("PUT", "/v1/storage/providers/bucket", bearer, s3Provider(bucket.secretAccessKey()));
        assertThat(privateEndpoint.statusCode()).isEqualTo(400);
        assertThat(privateEndpoint.bodyAsString()).contains("private or reserved address");

        assertThat(call("PUT", "/v1/tenants/acme/storage-policy", TestAuth.ADMIN, policy(true, true)).statusCode()).isEqualTo(200);
        HttpResponse<Buffer> wrongKey = call("PUT", "/v1/storage/providers/bucket", bearer, s3Provider("not-the-secret"));
        assertThat(wrongKey.statusCode()).as("checked before it's saved").isEqualTo(400);
        assertThat(wrongKey.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("provider_check_failed");

        HttpResponse<Buffer> created = call("PUT", "/v1/storage/providers/bucket", bearer, s3Provider(bucket.secretAccessKey()));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        assertThat(created.bodyAsJsonObject().getBoolean("hasSecret")).isTrue();
        assertThat(created.bodyAsString()).doesNotContain(bucket.secretAccessKey()).doesNotContain("sealedSecret");
        assertThat(call("PUT", "/v1/storage/providers/local", bearer, new JsonObject().put("type", "disk")).statusCode()).isEqualTo(201);

        HttpResponse<Buffer> routes = call("PUT", "/v1/storage/routes", bearer, new JsonObject().put("default", "default")
                .put("prefixes", new JsonObject().put("media/", "bucket").put("backups/", "local")));
        assertThat(routes.statusCode()).as(routes.bodyAsString()).isEqualTo(200);
        assertThat(call("PUT", "/v1/storage/routes", bearer, new JsonObject().put("default", "nope")).statusCode()).isEqualTo(400);

        // media/ goes to the tenant's bucket, outside the cluster's quota (5 MB): 6 MB fits.
        byte[] video = bytes(6 * 1024 * KB, 1);
        HttpResponse<Buffer> stored = upload("media/intro.mp4", video);
        assertThat(stored.statusCode()).as(stored.bodyAsString()).isEqualTo(201);
        S3Client s3 = new S3Client(clientVertx, bucket);
        assertThat(await(s3.listKeys("acme/"))).hasSize(1);
        HttpResponse<Buffer> back = call("GET", "/v1/files/media/intro.mp4", bearer, null);
        assertThat(back.body().getBytes()).isEqualTo(video);

        // backups/ goes to the disk folder, within its 1 MB allotment.
        assertThat(upload("backups/a.bin", bytes(600 * KB, 2)).statusCode()).isEqualTo(201);
        HttpResponse<Buffer> over = upload("backups/b.bin", bytes(600 * KB, 3));
        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(over.bodyAsString()).contains("Disk allotment");
        try (Stream<Path> files = Files.walk(diskRoot.resolve("acme").resolve("local"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }

        // Everything else stays in the cluster, and only that counts against the quota.
        assertThat(upload("notes.txt", "hello".getBytes()).statusCode()).isEqualTo(201);
        JsonObject overview = call("GET", "/v1/storage", bearer, null).bodyAsJsonObject();
        assertThat(overview.getJsonObject("quota").getLong("usedBytes")).isEqualTo(5);
        assertThat(overview.getJsonObject("disk").getLong("usedBytes")).isEqualTo(600 * KB);

        // Rotating keys: an update without the secret keeps it, and reads keep working.
        JsonObject withoutSecret = s3Provider("unused").put("region", "us-east-1");
        withoutSecret.remove("secretAccessKey");
        HttpResponse<Buffer> updated = call("PUT", "/v1/storage/providers/bucket", bearer, withoutSecret);
        assertThat(updated.statusCode()).as(updated.bodyAsString()).isEqualTo(200);
        assertThat(call("GET", "/v1/files/media/intro.mp4", bearer, null).body().getBytes()).isEqualTo(video);

        // A provider in use can't be deleted.
        HttpResponse<Buffer> inUse = call("DELETE", "/v1/storage/providers/bucket", bearer, null);
        assertThat(inUse.statusCode()).isEqualTo(409);
        assertThat(inUse.bodyAsString()).contains("1 file(s)");

        // The admin turns S3 off: new writes there are refused, reads still work.
        assertThat(call("PUT", "/v1/tenants/acme/storage-policy", TestAuth.ADMIN, policy(false, true)).statusCode()).isEqualTo(200);
        HttpResponse<Buffer> disabled = upload("media/more.mp4", bytes(10, 4));
        assertThat(disabled.statusCode()).isEqualTo(403);
        assertThat(disabled.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("storage_disabled");
        assertThat(call("GET", "/v1/files/media/intro.mp4", bearer, null).statusCode()).isEqualTo(200);

        // Scripts follow the same routes: this lands in the tenant's disk folder.
        HttpResponse<Buffer> job = call("POST", "/v1/jobs", bearer, new JsonObject().put("script",
                "export default async () => { await files.write('backups/from-script.txt', 'x'); return files.text('backups/from-script.txt'); }"));
        String id = job.bodyAsJsonObject().getString("id");
        JsonObject done = null;
        for (int i = 0; i < 100 && (done == null || "QUEUED".equals(done.getString("status")) || "RUNNING".equals(done.getString("status"))); i++) {
            Thread.sleep(100);
            done = call("GET", "/v1/jobs/" + id, bearer, null).bodyAsJsonObject();
        }
        assertThat(done.getString("output")).as(done.encode()).isEqualTo("x");
        try (Stream<Path> files = Files.walk(diskRoot.resolve("acme").resolve("local"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(2);
        }
    }

    @Test
    void anotherTenantCantSeeOrUseTheProviders() throws Exception {
        String other = TestAuth.bearer("globex");
        assertThat(call("GET", "/v1/storage/providers", other, null).bodyAsJsonObject().getJsonArray("providers")).isEmpty();
        assertThat(call("GET", "/v1/storage/providers/bucket", other, null).statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/v1/storage/routes", other, new JsonObject().put("default", "bucket")).statusCode())
                .as("can't route to acme's provider").isEqualTo(400);
        assertThat(call("GET", "/v1/tenants/acme/storage-policy", other, null).statusCode()).as("admin only").isEqualTo(403);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
