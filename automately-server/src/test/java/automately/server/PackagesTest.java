package automately.server;

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Publishing packages over the API (D-083): only new files travel, tenants keep to theirs, operators publish the cluster's. */
class PackagesTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "packages"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_TENANT_STORAGE_MB", "1"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        await(clientVertx.close());
        await(node.stop());
    }

    @Test
    void aTenantPublishesUploadingOnlyWhatsNew() throws Exception {
        String acme = TestAuth.bearer("pk-acme");
        String index = "export const slug = s => s.toLowerCase().replace(/ /g, '-');";
        String helper = "export const trim = s => s.trim();";

        JsonArray missing = post(acme, "/v1/packages/blobs/missing", new JsonObject().put("sha256", new JsonArray().add(sha(index)).add(sha(helper))))
                .bodyAsJsonObject().getJsonArray("missing");
        assertThat(missing).containsExactly(sha(index), sha(helper));
        assertThat(upload(acme, sha(index), index).statusCode()).isEqualTo(201);
        assertThat(upload(acme, sha(index), index).statusCode()).as("already there").isEqualTo(200);

        JsonObject manifest = manifest("strings", "1.0.0", Map.of("index.js", index, "lib/trim.js", helper));
        HttpResponse<Buffer> early = post(acme, "/v1/packages", manifest);
        assertThat(early.statusCode()).as(early.bodyAsString()).isEqualTo(409);
        assertThat(early.bodyAsString()).contains("missing_files");

        assertThat(upload(acme, sha(helper), helper).statusCode()).isEqualTo(201);
        HttpResponse<Buffer> published = post(acme, "/v1/packages", manifest);
        assertThat(published.statusCode()).as(published.bodyAsString()).isEqualTo(201);
        assertThat(post(acme, "/v1/packages", manifest).statusCode()).as("versions never change").isEqualTo(409);

        JsonObject info = get(acme, "/v1/packages/strings").bodyAsJsonObject();
        assertThat(info.getString("latest")).isEqualTo("1.0.0");
        JsonObject version = get(acme, "/v1/packages/strings/versions/1.0.0").bodyAsJsonObject();
        assertThat(version.getJsonObject("files").getString("lib/trim.js")).isEqualTo("sha256:" + sha(helper));
        assertThat(get(TestAuth.bearer("pk-globex"), "/v1/packages/strings").statusCode()).as("other tenants can't see it").isEqualTo(404);

        JsonArray audited = get(acme, "/v1/audit?limit=50").bodyAsJsonObject().getJsonArray("entries");
        assertThat(audited.stream().map(e -> ((JsonObject) e).getString("action")))
                .contains("publishPackage").doesNotContain("putPackageFile", "findMissingPackageFiles");
    }

    @Test
    void aFileMustMatchItsHashAndFitTheQuota() throws Exception {
        String bearer = TestAuth.bearer("pk-strict");
        HttpResponse<Buffer> lying = upload(bearer, sha("the real thing"), "something else");
        assertThat(lying.statusCode()).isEqualTo(400);
        assertThat(lying.bodyAsString()).contains("SHA-256");

        assertThat(upload(bearer, "not-a-hash", "x").statusCode()).as("the contract checks the hash's form").isEqualTo(400);

        String big = "x".repeat(1_100_000);
        HttpResponse<Buffer> tooBig = upload(bearer, sha(big), big);
        assertThat(tooBig.statusCode()).as(tooBig.bodyAsString()).isEqualTo(413);
        assertThat(post(bearer, "/v1/packages/blobs/missing", new JsonObject().put("sha256", new JsonArray().add(sha(big))))
                .bodyAsJsonObject().getJsonArray("missing")).hasSize(1);
    }

    @Test
    void operatorsPublishTheClustersPackagesWhichEveryTenantSees() throws Exception {
        String code = "export const shout = s => s.toUpperCase();";
        String tenant = TestAuth.bearer("pk-user");

        assertThat(upload(tenant, sha(code), code).statusCode()).isEqualTo(201);
        HttpResponse<Buffer> impostor = post(tenant, "/v1/packages", manifest("@cluster/shout", "1.0.0", Map.of("index.js", code)));
        assertThat(impostor.statusCode()).as("tenants can't publish under @cluster/").isEqualTo(403);

        assertThat(upload(TestAuth.ADMIN, sha(code), code).statusCode()).as("the cluster keeps its own copy").isEqualTo(201);
        assertThat(post(TestAuth.ADMIN, "/v1/packages", manifest("shout", "1.0.0", Map.of("index.js", code))).statusCode())
                .as("operators publish @cluster/ names only").isEqualTo(403);
        HttpResponse<Buffer> published = post(TestAuth.ADMIN, "/v1/packages", manifest("@cluster/shout", "1.0.0", Map.of("index.js", code)));
        assertThat(published.statusCode()).as(published.bodyAsString()).isEqualTo(201);

        assertThat(get(tenant, "/v1/packages").bodyAsJsonObject().getJsonArray("packages").stream()
                .map(p -> ((JsonObject) p).getString("name"))).contains("@cluster/shout");
        HttpResponse<Buffer> seen = get(tenant, "/v1/packages/%40cluster%2Fshout");
        assertThat(seen.statusCode()).as(seen.bodyAsString()).isEqualTo(200);
        assertThat(seen.bodyAsJsonObject().getBoolean("cluster")).isTrue();
        assertThat(get(tenant, "/v1/packages/%40cluster%2Fshout/versions/1.0.0").statusCode()).isEqualTo(200);

        JsonObject app = manifest("app", "1.0.0", Map.of("index.js", code)).put("dependencies", new JsonObject().put("@cluster/shout", "^1.0.0"));
        assertThat(post(tenant, "/v1/packages", app).statusCode()).isEqualTo(201);
    }

    @Test
    void deletingATenantDeletesItsPackages() throws Exception {
        String bearer = TestAuth.bearer("pk-leaving");
        String code = "export default 'bye';";
        upload(bearer, sha(code), code);
        assertThat(post(bearer, "/v1/packages", manifest("bye", "1.0.0", Map.of("index.js", code))).statusCode()).isEqualTo(201);

        HttpResponse<Buffer> deleted = await(client.delete(node.httpPort(), "localhost", "/v1/tenants/pk-leaving")
                .putHeader("Authorization", TestAuth.ADMIN).send());
        assertThat(deleted.statusCode()).as(deleted.bodyAsString()).isEqualTo(204);
        HttpResponse<Buffer> again = await(client.post(node.httpPort(), "localhost", "/v1/tenants")
                .putHeader("Authorization", TestAuth.ADMIN).sendJsonObject(new JsonObject().put("name", "pk-leaving")));
        assertThat(again.statusCode()).isEqualTo(201);
        JsonObject token = await(client.post(node.httpPort(), "localhost", "/v1/tenants/pk-leaving/tokens")
                .putHeader("Authorization", TestAuth.ADMIN)
                .sendJsonObject(new JsonObject().put("name", "t").put("scopes", new JsonArray().add("*")))).bodyAsJsonObject();
        String fresh = "Bearer " + token.getString("token");
        assertThat(get(fresh, "/v1/packages/bye").statusCode()).isEqualTo(404);
        assertThat(post(fresh, "/v1/packages/blobs/missing", new JsonObject().put("sha256", new JsonArray().add(sha(code))))
                .bodyAsJsonObject().getJsonArray("missing")).hasSize(1);
    }

    // --- helpers ---

    private static JsonObject manifest(String name, String version, Map<String, String> files) throws Exception {
        JsonObject hashes = new JsonObject();
        for (Map.Entry<String, String> file : files.entrySet()) {
            hashes.put(file.getKey(), "sha256:" + sha(file.getValue()));
        }
        return new JsonObject().put("name", name).put("version", version).put("files", hashes);
    }

    private static HttpResponse<Buffer> upload(String bearer, String hash, String content) throws Exception {
        return await(client.put(node.httpPort(), "localhost", "/v1/packages/blobs/" + hash).putHeader("Authorization", bearer)
                .putHeader("Content-Type", "application/octet-stream").sendBuffer(Buffer.buffer(content)));
    }

    private static HttpResponse<Buffer> post(String bearer, String path, JsonObject body) throws Exception {
        return await(client.post(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).sendJsonObject(body));
    }

    private static HttpResponse<Buffer> get(String bearer, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).send());
    }

    static String sha(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
