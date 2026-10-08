package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Files end to end on a real two-node cluster: written on one node, read on the other (milestone 3). */
class FilesTest {

    private static final String TENANT = "acme";
    private static final int MB = 1024 * 1024;

    private static RunningNode nodeA;
    private static RunningNode nodeB;
    private static Vertx clientVertx;
    private static WebClient client;
    private static HttpClient raw;

    @BeforeAll
    static void startCluster() throws Exception {
        nodeA = await(Automately.start(config("node-a")));
        TestAuth.use(nodeA);
        nodeB = await(Automately.start(config("node-b")));
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        raw = clientVertx.createHttpClient();
        awaitJson(nodeA, "/v1/nodes", json -> json.getJsonArray("nodes").size() == 2);
    }

    @AfterAll
    static void stopCluster() throws Exception {
        await(clientVertx.close());
        await(nodeB.stop());
        await(nodeA.stop());
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Object service : get(nodeA, "/v1/services").bodyAsJsonObject().getJsonArray("services")) {
            await(client.delete(nodeA.httpPort(), "localhost", "/v1/services/" + ((JsonObject) service).getString("name"))
                    .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        }
        for (Object file : get(nodeA, "/v1/files?limit=1000").bodyAsJsonObject().getJsonArray("files")) {
            await(client.delete(nodeA.httpPort(), "localhost", "/v1/files/" + ((JsonObject) file).getString("path"))
                    .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        }
    }

    private static ServerConfig config(String nodeId) {
        return ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_INGRESS_DOMAIN", "apps.test"),
                Map.entry("AUTOMATELY_TENANT_STORAGE_MB", "40"),
                Map.entry("AUTOMATELY_DATA_MEMORY_MB", "512"),
                Map.entry("AUTOMATELY_CAPACITY", "4"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "128"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_CLUSTERED", "true"),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "27701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1"))));
    }

    private static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static String etag(byte[] data) throws Exception {
        return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)) + "\"";
    }

    private static HttpResponse<Buffer> upload(RunningNode node, String path, byte[] data, String contentType) throws Exception {
        return upload(node, TENANT, path, data, contentType);
    }

    private static HttpResponse<Buffer> upload(RunningNode node, String tenant, String path, byte[] data, String contentType) throws Exception {
        return await(client.put(node.httpPort(), "localhost", "/v1/files/" + path).putHeader("Authorization", TestAuth.bearer(tenant))
                .putHeader("Content-Type", contentType).sendBuffer(Buffer.buffer(data)));
    }

    // --- the REST API ---

    @Test
    void foldersMadeOnOneNodeListOnTheOther() throws Exception {
        assertThat(upload(nodeA, "tree/docs/readme.md", "# hi".getBytes(), "text/markdown").statusCode()).isEqualTo(201);
        HttpResponse<Buffer> made = await(client.put(nodeA.httpPort(), "localhost", "/v1/dirs/tree/empty/").putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        assertThat(made.statusCode()).as(made.bodyAsString()).isEqualTo(201);
        assertThat(made.bodyAsJsonObject().getString("kind")).isEqualTo("dir");

        JsonObject listing = get(nodeB, "/v1/dirs/tree").bodyAsJsonObject();
        assertThat(listing.getJsonArray("entries").stream().map(e -> ((JsonObject) e).getString("name") + "/" + ((JsonObject) e).getString("kind")))
                .containsExactly("docs/dir", "empty/dir");
        assertThat(get(nodeB, "/v1/dirs/tree/docs").bodyAsJsonObject().getJsonArray("entries").getJsonObject(0).getLong("size")).isEqualTo(4);
        assertThat(get(nodeB, "/v1/dirs").bodyAsJsonObject().getJsonArray("entries").stream().map(e -> ((JsonObject) e).getString("name")))
                .contains("tree");

        assertThat(upload(nodeB, "tree/empty", "x".getBytes(), "text/plain").statusCode()).as("a folder is in the way").isEqualTo(409);
        assertThat(upload(nodeB, "tree/docs/readme.md/x", "x".getBytes(), "text/plain").statusCode()).as("a file is in the way").isEqualTo(409);
        assertThat(await(client.delete(nodeB.httpPort(), "localhost", "/v1/dirs/tree/docs").putHeader("Authorization", TestAuth.bearer(TENANT)).send())
                .statusCode()).as("not empty").isEqualTo(409);
        assertThat(await(client.delete(nodeB.httpPort(), "localhost", "/v1/dirs/tree/empty").putHeader("Authorization", TestAuth.bearer(TENANT)).send())
                .statusCode()).isEqualTo(204);
        assertThat(get(nodeA, "/v1/dirs/tree/empty").statusCode()).isEqualTo(404);
        assertThat(get(nodeA, "/v1/dirs/tree/docs/readme.md").statusCode()).isEqualTo(409);
    }

    @Test
    void foldersMoveAndCopyAcrossTheClusterWithoutMovingBytes() throws Exception {
        byte[] data = bytes(2 * MB, 9);
        assertThat(upload(nodeA, "albums/2026/a.jpg", data, "image/jpeg").statusCode()).isEqualTo(201);
        assertThat(upload(nodeA, "albums/2026/b.jpg", data, "image/jpeg").statusCode()).isEqualTo(201);
        long used = get(nodeA, "/v1/storage").bodyAsJsonObject().getJsonObject("quota").getLong("usedBytes");

        HttpResponse<Buffer> copied = await(client.post(nodeB.httpPort(), "localhost", "/v1/fs/copy").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("from", "albums").put("to", "backup/albums")));
        assertThat(copied.statusCode()).as(copied.bodyAsString()).isEqualTo(200);
        assertThat(copied.bodyAsJsonObject().getInteger("files")).isEqualTo(2);
        Thread.sleep(automately.core.storage.TenantQuota.FRESH.toMillis() + 500);
        assertThat(get(nodeA, "/v1/storage").bodyAsJsonObject().getJsonObject("quota").getLong("usedBytes")).as("copies count once").isEqualTo(used);

        HttpResponse<Buffer> moved = await(client.post(nodeA.httpPort(), "localhost", "/v1/fs/move").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("from", "albums/2026").put("to", "albums/summer")));
        assertThat(moved.statusCode()).as(moved.bodyAsString()).isEqualTo(200);
        assertThat(get(nodeB, "/v1/files/albums/summer/b.jpg").body().getBytes()).isEqualTo(data);
        assertThat(get(nodeB, "/v1/dirs/albums/2026").statusCode()).isEqualTo(404);

        assertThat(await(client.delete(nodeB.httpPort(), "localhost", "/v1/dirs/backup?recursive=true").putHeader("Authorization", TestAuth.bearer(TENANT))
                .send()).statusCode()).isEqualTo(204);
        assertThat(get(nodeA, "/v1/files/albums/summer/a.jpg").body().getBytes()).as("the original kept its bytes").isEqualTo(data);
    }

    @Test
    void uploadsOnOneNodeAndDownloadsOnTheOther() throws Exception {
        byte[] data = bytes(10 * MB + 123, 1);

        HttpResponse<Buffer> created = upload(nodeA, "media/clip.bin", data, "application/octet-stream");
        HttpResponse<Buffer> downloaded = get(nodeB, "/v1/files/media/clip.bin");

        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        assertThat(created.bodyAsJsonObject().getLong("size")).isEqualTo(data.length);
        assertThat(created.bodyAsJsonObject().getString("etag")).isEqualTo(etag(data));
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.getHeader("content-length")).isEqualTo(String.valueOf(data.length));
        assertThat(downloaded.getHeader("accept-ranges")).isEqualTo("bytes");
        assertThat(downloaded.getHeader("content-type")).isEqualTo("application/octet-stream");
        assertThat(downloaded.body().getBytes()).isEqualTo(data);
    }

    @Test
    void rangesConditionalRequestsAndHead() throws Exception {
        byte[] data = bytes(3 * MB, 2);
        String etag = upload(nodeA, "video.mp4", data, "video/mp4").bodyAsJsonObject().getString("etag");

        HttpResponse<Buffer> middle = await(client.get(nodeB.httpPort(), "localhost", "/v1/files/video.mp4")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).putHeader("Range", "bytes=1048570-1048589").send());
        HttpResponse<Buffer> tail = await(client.get(nodeB.httpPort(), "localhost", "/v1/files/video.mp4")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).putHeader("Range", "bytes=-10").send());
        HttpResponse<Buffer> beyond = await(client.get(nodeB.httpPort(), "localhost", "/v1/files/video.mp4")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).putHeader("Range", "bytes=99999999-").send());
        HttpResponse<Buffer> cached = await(client.get(nodeB.httpPort(), "localhost", "/v1/files/video.mp4")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).putHeader("If-None-Match", etag).send());
        HttpResponse<Buffer> head = await(client.head(nodeB.httpPort(), "localhost", "/v1/files/video.mp4")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).send());

        assertThat(middle.statusCode()).isEqualTo(206);
        assertThat(middle.getHeader("content-range")).isEqualTo("bytes 1048570-1048589/" + data.length);
        assertThat(middle.body().getBytes()).as("crosses the 1 MB chunk boundary").isEqualTo(Arrays.copyOfRange(data, 1048570, 1048590));
        assertThat(tail.statusCode()).isEqualTo(206);
        assertThat(tail.body().getBytes()).isEqualTo(Arrays.copyOfRange(data, data.length - 10, data.length));
        assertThat(beyond.statusCode()).isEqualTo(416);
        assertThat(cached.statusCode()).isEqualTo(304);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.getHeader("content-length")).isEqualTo(String.valueOf(data.length));
        assertThat(head.body()).isNull();
    }

    @Test
    void listsDeletesAndValidates() throws Exception {
        upload(nodeA, "site/index.html", "<h1>hi</h1>".getBytes(), "text/html");
        upload(nodeA, "site/app.js", "console.log(1)".getBytes(), "text/javascript");
        upload(nodeA, "other.txt", "x".getBytes(), "text/plain");

        JsonObject listed = get(nodeB, "/v1/files?prefix=site/").bodyAsJsonObject();
        assertThat(listed.getJsonArray("files").stream().map(f -> ((JsonObject) f).getString("path"))).containsExactly("site/app.js", "site/index.html");

        assertThat(await(client.delete(nodeB.httpPort(), "localhost", "/v1/files/other.txt").putHeader("Authorization", TestAuth.bearer(TENANT)).send())
                .statusCode()).isEqualTo(204);
        assertThat(get(nodeA, "/v1/files/other.txt").statusCode()).isEqualTo(404);
        assertThat(upload(nodeA, "a/%2E%2E/escape.txt", "x".getBytes(), "text/plain").statusCode()).isEqualTo(400);
        assertThat(await(client.get(nodeA.httpPort(), "localhost", "/v1/files/site/index.html").putHeader("Authorization", TestAuth.bearer("globex")).send())
                .statusCode()).as("another tenant can't see it").isEqualTo(404);
    }

    @Test
    void kvDataCountsTowardsTheQuotaForUploads() throws Exception {
        // About 1 MB of kv: four 250 KB values.
        JsonObject stored = runJob("kvquota", """
                export default async () => {
                  for (let i = 0; i < 4; i++) await kv.set(`blob:${i}`, 'k'.repeat(250 * 1024));
                  return 'stored';
                }
                """);
        assertThat(stored.getString("output")).as(stored.encode()).isEqualTo("stored");

        HttpResponse<Buffer> refused = upload(nodeB, "kvquota", "almost-40mb.bin", bytes(40 * MB - 512 * 1024, 8), "application/octet-stream");
        HttpResponse<Buffer> accepted = upload(nodeB, "kvquota", "39mb.bin", bytes(39 * MB, 9), "application/octet-stream");   // leaves about 24 KB
        // kv checks a count at most TenantQuota.FRESH old, and the upload went through the other node (D-043).
        Thread.sleep(automately.core.storage.TenantQuota.FRESH.toMillis() + 200);
        JsonObject full = runJob("kvquota", "export default () => kv.set('one-more', 'k'.repeat(250 * 1024))");

        assertThat(refused.statusCode()).as(refused.bodyAsString()).isEqualTo(413);
        assertThat(refused.bodyAsJsonObject().getJsonObject("error").getString("message")).contains("files, kv and queued messages together");
        assertThat(accepted.statusCode()).as(accepted.bodyAsString()).isEqualTo(201);
        assertThat(full.getString("status")).isEqualTo("FAILED");
        assertThat(full.getString("error")).contains("quota");
    }

    @Test
    void theTenantQuotaIsEnforced() throws Exception {
        assertThat(upload(nodeA, "big-1.bin", bytes(30 * MB, 3), "application/octet-stream").statusCode()).isEqualTo(201);

        HttpResponse<Buffer> over = upload(nodeA, "big-2.bin", bytes(20 * MB, 4), "application/octet-stream");

        assertThat(over.statusCode()).as(over.bodyAsString()).isEqualTo(413);
        assertThat(over.bodyAsJsonObject().getJsonObject("error").getString("message")).contains("quota");
        assertThat(get(nodeA, "/v1/files/big-2.bin").statusCode()).isEqualTo(404);
    }

    // --- scripts ---

    @Test
    void scriptsWriteReadListAndDeleteFiles() throws Exception {
        JsonObject job = runJob("""
                export default async () => {
                  const info = await files.write('notes/today.json', { mood: 'great' });
                  await files.write('notes/hello.txt', 'hello ' + 'files', { contentType: 'text/plain' });
                  const bytes = new TextEncoder().encode('binary!');
                  await files.write('notes/raw.bin', bytes);
                  const listed = (await files.list({ prefix: 'notes/' })).files.map((f) => f.path);
                  const result = {
                    jsonType: info.contentType,
                    json: await files.json('notes/today.json'),
                    text: await files.text('notes/hello.txt'),
                    raw: new TextDecoder().decode(await files.read('notes/raw.bin')),
                    listed,
                    deleted: await files.delete('notes/raw.bin'),
                    gone: await files.stat('notes/raw.bin'),
                  };
                  try { await files.write('../escape', 'x'); } catch (e) { result.invalid = e.name; }
                  return result;
                }
                """);

        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        JsonObject out = job.getJsonObject("output");
        assertThat(out.getString("jsonType")).isEqualTo("application/json");
        assertThat(out.getJsonObject("json")).isEqualTo(new JsonObject().put("mood", "great"));
        assertThat(out.getString("text")).isEqualTo("hello files");
        assertThat(out.getString("raw")).isEqualTo("binary!");
        assertThat(out.getJsonArray("listed").getList()).containsExactly("notes/hello.txt", "notes/raw.bin", "notes/today.json");
        assertThat(out.getBoolean("deleted")).isTrue();
        assertThat(out.getValue("gone")).isNull();
        assertThat(out.getString("invalid")).isEqualTo("TypeError");
        assertThat(get(nodeB, "/v1/files/notes/hello.txt").bodyAsString()).as("visible through the API too").isEqualTo("hello files");
    }

    @Test
    void aServiceStoresAStreamedUploadAndServesItWithRanges() throws Exception {
        serve("media", """
                async (req) => {
                  if (req.method === 'PUT') {
                    const info = await files.write('uploads' + req.path, req.body, { contentType: req.headers.get('content-type') });
                    return { status: 201, body: info };
                  }
                  return { headers: { 'cache-control': 'max-age=60' }, body: files.stream('uploads' + req.path) };
                }
                """);
        byte[] video = bytes(6 * MB, 5);

        Reply put = send(ingress(HttpMethod.PUT, "/movie.mp4").putHeader("Content-Type", "video/mp4"), Buffer.buffer(video));
        assertThat(put.status()).as(put.body().toString()).isEqualTo(201);
        JsonObject stored = put.body().toJsonObject();
        Reply full = send(ingress(HttpMethod.GET, "/movie.mp4"), null);
        Reply seek = send(ingress(HttpMethod.GET, "/movie.mp4").putHeader("Range", "bytes=4194300-4194309"), null);
        Reply cached = send(ingress(HttpMethod.GET, "/movie.mp4").putHeader("If-None-Match", etag(video)), null);
        Reply missing = send(ingress(HttpMethod.GET, "/nope.mp4"), null);

        assertThat(stored.getLong("size")).isEqualTo(video.length);
        assertThat(stored.getString("etag")).isEqualTo(etag(video));
        assertThat(full.status()).isEqualTo(200);
        assertThat(full.headers().get("content-length")).isEqualTo(String.valueOf(video.length));
        assertThat(full.headers().get("content-type")).isEqualTo("video/mp4");
        assertThat(full.headers().get("cache-control")).isEqualTo("max-age=60");
        assertThat(full.body().getBytes()).isEqualTo(video);
        assertThat(seek.status()).isEqualTo(206);
        assertThat(seek.body().getBytes()).isEqualTo(Arrays.copyOfRange(video, 4194300, 4194310));
        assertThat(cached.status()).isEqualTo(304);
        assertThat(missing.status()).isEqualTo(404);
    }

    // --- helpers ---

    /** A response read in full. The body is read in the same chain as the response arrives, so no bytes slip past. */
    private record Reply(int status, io.vertx.core.MultiMap headers, Buffer body) {
    }

    private static Reply send(RequestOptions options, Buffer body) throws Exception {
        return await(raw.request(options).compose(req -> body == null ? req.send() : req.send(body))
                .compose(response -> response.body().map(data -> new Reply(response.statusCode(), response.headers(), data))));
    }

    private static RequestOptions ingress(HttpMethod method, String path) {
        return new RequestOptions().setMethod(method).setHost("127.0.0.1").setPort(nodeB.ingressPort()).setURI(path)
                .putHeader("Host", "media--" + TENANT + ".apps.test");
    }

    private static void serve(String name, String handler) throws Exception {
        HttpResponse<Buffer> created = await(client.put(nodeA.httpPort(), "localhost", "/v1/services/" + name)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("script", """
                        export default async (input, ctx) => { await ctx.serve(%s); return ctx.stopRequested; }
                        """.formatted(handler))));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        awaitJson(nodeA, "/v1/services/" + name, s -> "RUNNING".equals(s.getString("status")));
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (send(ingress(HttpMethod.GET, "/probe"), null).status() != 503) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private static JsonObject runJob(String script) throws Exception {
        return runJob(TENANT, script);
    }

    private static JsonObject runJob(String tenant, String script) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(nodeA.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(tenant))
                .sendJsonObject(new JsonObject().put("script", script).put("timeoutMs", 20_000)));
        String id = submitted.bodyAsJsonObject().getString("id");
        return awaitJson(nodeA, tenant, "/v1/jobs/" + id, j -> !List.of("QUEUED", "RUNNING").contains(j.getString("status")));
    }

    private static HttpResponse<Buffer> get(RunningNode node, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private static JsonObject awaitJson(RunningNode node, String path, Predicate<JsonObject> condition) throws Exception {
        return awaitJson(node, TENANT, path, condition);
    }

    private static JsonObject awaitJson(RunningNode node, String tenant, String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(tenant)).send()).bodyAsJsonObject();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }
}
