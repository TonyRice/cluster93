package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /v1/apps}: deployments, promotion, rollback, deletion and the {@code apps:*} scopes. */
class AppsRoutesTest {

    private static final String TENANT = "appco";
    /** The ingress test uses its own tenant so it doesn't disturb the others' app lists. */
    private static final String STORE_TENANT = "shopco";
    private static final JsonObject MANIFEST = new JsonObject().put("name", "shop");

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "apps"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_INGRESS_DOMAIN", "apps.test"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64")))));
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
    @DisplayName("Deployments register, promote, roll back and delete")
    void deploymentsPromoteRollbackAndDelete() throws Exception {
        String all = TestAuth.bearer(TENANT);

        HttpResponse<Buffer> first = post(all, "/v1/apps/shop/deployments",
                new JsonObject().put("deploymentId", "dpl_a").put("commitHash", "abc123").put("manifest", MANIFEST));
        assertThat(first.statusCode()).as(first.bodyAsString()).isEqualTo(201);
        assertThat(first.bodyAsJsonObject().getString("vfsPrefix")).isEqualTo("/.apps/shop/deployments/dpl_a");
        assertThat(first.bodyAsJsonObject().getJsonObject("manifest").getString("name")).isEqualTo("shop");

        assertThat(post(all, "/v1/apps/shop/deployments",
                new JsonObject().put("deploymentId", "dpl_b").put("manifest", MANIFEST)).statusCode()).isEqualTo(201);
        // The same id with different content is a conflict.
        assertThat(post(all, "/v1/apps/shop/deployments",
                new JsonObject().put("deploymentId", "dpl_a").put("commitHash", "other").put("manifest", MANIFEST)).statusCode())
                .isEqualTo(409);

        JsonArray deployments = get(all, "/v1/apps/shop/deployments").bodyAsJsonObject().getJsonArray("deployments");
        assertThat(deployments).hasSize(2);
        assertThat(get(all, "/v1/apps/shop/deployments/dpl_a").statusCode()).isEqualTo(200);
        assertThat(get(all, "/v1/apps/shop/deployments/missing").statusCode()).isEqualTo(404);

        JsonObject promoted = post(all, "/v1/apps/shop/promote", new JsonObject().put("deploymentId", "dpl_a")).bodyAsJsonObject();
        assertThat(promoted.getString("activeDeploymentId")).isEqualTo("dpl_a");
        assertThat(get(all, "/v1/apps").bodyAsJsonObject().getJsonArray("apps")).hasSize(1);
        assertThat(get(all, "/v1/apps/shop").bodyAsJsonObject().getString("activeDeploymentId")).isEqualTo("dpl_a");

        assertThat(post(all, "/v1/apps/shop/promote", new JsonObject().put("deploymentId", "dpl_missing")).statusCode())
                .isEqualTo(404);

        post(all, "/v1/apps/shop/promote", new JsonObject().put("deploymentId", "dpl_b"));
        JsonObject rolled = post(all, "/v1/apps/shop/rollback", null).bodyAsJsonObject();
        assertThat(rolled.getString("activeDeploymentId")).isEqualTo("dpl_a");

        assertThat(delete(all, "/v1/apps/shop").statusCode()).isEqualTo(204);
        assertThat(get(all, "/v1/apps/shop").statusCode()).isEqualTo(404);
        assertThat(delete(all, "/v1/apps/shop").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("A deployment's manifest must be valid and name the app")
    void manifestsAreValidated() throws Exception {
        String all = TestAuth.bearer(TENANT);

        assertThat(post(all, "/v1/apps/shop/deployments",
                new JsonObject().put("deploymentId", "bad_1").put("manifest", new JsonObject().put("name", "other"))).statusCode())
                .isEqualTo(400);
        assertThat(post(all, "/v1/apps/shop/deployments", new JsonObject().put("deploymentId", "bad_2")).statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("A token with apps:read can list but not deploy; apps:write can")
    void scopesAreEnforced() throws Exception {
        JsonObject minted = post(TestAuth.ADMIN, "/v1/tenants/" + TENANT + "/tokens",
                new JsonObject().put("name", "reader").put("scopes", new JsonArray().add("apps:read"))).bodyAsJsonObject();
        String reader = "Bearer " + minted.getString("token");

        assertThat(get(reader, "/v1/apps").statusCode()).isEqualTo(200);
        assertThat(post(reader, "/v1/apps/scoped/deployments",
                new JsonObject().put("manifest", new JsonObject().put("name", "scoped"))).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("The ingress serves a promoted app's static assets from the VFS, with ETag, 304, Range and SPA fallback")
    void ingressServesPromotedStaticAssets() throws Exception {
        String all = TestAuth.bearer(STORE_TENANT);
        String host = "store--" + STORE_TENANT + ".apps.test";
        String prefix = ".apps/store/deployments/dpl_s";

        HttpResponse<Buffer> uploaded = await(client.put(node.httpPort(), "localhost", "/v1/files/" + prefix + "/index.html")
                .putHeader("Authorization", all).putHeader("Content-Type", "text/html")
                .sendBuffer(Buffer.buffer("<h1>store</h1>")));
        assertThat(uploaded.statusCode()).as(uploaded.bodyAsString()).isEqualTo(201);

        assertThat(post(all, "/v1/apps/store/deployments", new JsonObject()
                .put("deploymentId", "dpl_s").put("manifest", new JsonObject().put("name", "store"))).statusCode()).isEqualTo(201);
        assertThat(post(all, "/v1/apps/store/promote", new JsonObject().put("deploymentId", "dpl_s")).statusCode()).isEqualTo(200);

        HttpResponse<Buffer> served = ingress(host, "/", null, null);
        assertThat(served.statusCode()).as(served.bodyAsString()).isEqualTo(200);
        assertThat(served.bodyAsString()).isEqualTo("<h1>store</h1>");
        assertThat(served.getHeader("ETag")).isNotBlank();
        assertThat(served.getHeader("Accept-Ranges")).isEqualTo("bytes");

        HttpResponse<Buffer> cached = ingress(host, "/", "If-None-Match", served.getHeader("ETag"));
        assertThat(cached.statusCode()).isEqualTo(304);

        HttpResponse<Buffer> range = ingress(host, "/", "Range", "bytes=0-3");
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.bodyAsString()).isEqualTo("<h1>");

        // A path with no file falls back to index.html for client-side routing.
        HttpResponse<Buffer> fallback = ingress(host, "/tenants/demo/jobs", null, null);
        assertThat(fallback.statusCode()).isEqualTo(200);
        assertThat(fallback.bodyAsString()).isEqualTo("<h1>store</h1>");
    }

    // --- helpers ---

    private static HttpResponse<Buffer> ingress(String host, String path, String header, String value) throws Exception {
        var request = client.get(node.ingressPort(), "localhost", path).putHeader("Host", host);
        if (header != null) {
            request.putHeader(header, value);
        }
        return await(request.send());
    }

    private static HttpResponse<Buffer> post(String bearer, String path, JsonObject body) throws Exception {
        var request = client.post(node.httpPort(), "localhost", path).putHeader("Authorization", bearer);
        return await(body == null ? request.send() : request.sendJsonObject(body));
    }

    private static HttpResponse<Buffer> get(String bearer, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).send());
    }

    private static HttpResponse<Buffer> delete(String bearer, String path) throws Exception {
        return await(client.delete(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).send());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
