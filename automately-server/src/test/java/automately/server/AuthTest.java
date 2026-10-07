package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tenants, scoped tokens and the admin token on a real node (D-044, D-045). */
class AuthTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "auth"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64")))));
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
    void everyRouteNeedsAToken() throws Exception {
        // The contract itself is public (D-046); everything else under /v1 needs a token.
        List<String> routes = node.apiRoutes().stream().filter(route -> route.contains(" /v1/") && !route.endsWith("/v1/openapi.json")).toList();
        assertThat(routes).hasSizeGreaterThan(25);

        for (String route : routes) {
            String method = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1).replaceAll(":[a-z]+", "x").replace(".+", "x");
            HttpResponse<Buffer> anonymous = await(client.request(HttpMethod.valueOf(method), node.httpPort(), "localhost", path).send());
            HttpResponse<Buffer> wrong = await(client.request(HttpMethod.valueOf(method), node.httpPort(), "localhost", path)
                    .putHeader("Authorization", "Bearer atly_not-a-real-token").send());

            // A route with no rule would answer 404 here: rules fail closed (D-045).
            assertThat(anonymous.statusCode()).as(route).isEqualTo(401);
            assertThat(anonymous.getHeader("WWW-Authenticate")).as(route).startsWith("Bearer");
            assertThat(wrong.statusCode()).as(route).isEqualTo(401);
        }
        assertThat(await(client.get(node.httpPort(), "localhost", "/health").send()).statusCode()).isEqualTo(200);
        assertThat(await(client.get(node.httpPort(), "localhost", "/v1/openapi.json").send()).statusCode()).isEqualTo(200);
    }

    @Test
    void aTokenCanDoWhatItsScopesSayAndNothingElse() throws Exception {
        createTenant("scoped");
        String reader = mint("scoped", List.of("jobs:read", "files:read"));

        assertThat(call("GET", "/v1/jobs", reader, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/v1/files", reader, null).statusCode()).isEqualTo(200);
        HttpResponse<Buffer> write = call("POST", "/v1/jobs", reader, new JsonObject().put("script", "export default () => 1"));
        assertThat(write.statusCode()).isEqualTo(403);
        assertThat(write.bodyAsJsonObject().getJsonObject("error").getString("message")).contains("jobs:write");
        assertThat(call("PUT", "/v1/services/web", reader, new JsonObject().put("script", "x")).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/v1/nodes", reader, null).statusCode()).as("needs nodes:read").isEqualTo(403);

        JsonObject whoami = call("GET", "/v1/whoami", reader, null).bodyAsJsonObject();
        assertThat(whoami.getString("tenant")).isEqualTo("scoped");
        assertThat(whoami.getJsonArray("scopes").getList()).containsExactly("files:read", "jobs:read");
    }

    @Test
    void theAdminManagesTenantsButCantTouchTheirData() throws Exception {
        assertThat(call("GET", "/v1/jobs", TestAuth.ADMIN, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/v1/files", TestAuth.ADMIN, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/v1/nodes", TestAuth.ADMIN, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/v1/whoami", TestAuth.ADMIN, null).bodyAsJsonObject().getBoolean("admin")).isTrue();

        String tenant = TestAuth.bearer("plain");
        assertThat(call("GET", "/v1/tenants", tenant, null).statusCode()).as("tenants can't manage tenants").isEqualTo(403);
        assertThat(call("POST", "/v1/admin/rotate", tenant, null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/v1/admin/rotate", TestAuth.ADMIN, null).statusCode()).as("configured from the environment").isEqualTo(409);
    }

    @Test
    void tenantsAreValidatedAndUnique() throws Exception {
        assertThat(call("POST", "/v1/tenants", TestAuth.ADMIN, new JsonObject().put("name", "Bad Name")).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/v1/tenants", TestAuth.ADMIN, new JsonObject().put("name", "unique")).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/v1/tenants", TestAuth.ADMIN, new JsonObject().put("name", "unique")).statusCode()).isEqualTo(409);
        assertThat(call("POST", "/v1/tenants/nobody/tokens", TestAuth.ADMIN, tokenRequest(List.of("*"))).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/v1/tenants", TestAuth.ADMIN, null).bodyAsJsonObject().getJsonArray("tenants").stream()
                .map(t -> ((JsonObject) t).getString("name")).toList()).contains("unique");
    }

    @Test
    void tokensOnlyMintNarrowerTokensForTheirOwnTenant() throws Exception {
        createTenant("minter");
        String minter = mint("minter", List.of("tokens:write", "jobs:read"));

        HttpResponse<Buffer> narrower = call("POST", "/v1/tokens", minter, tokenRequest(List.of("jobs:read")));
        assertThat(narrower.statusCode()).isEqualTo(201);
        assertThat(narrower.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(narrower.bodyAsJsonObject().getString("tenant")).isEqualTo("minter");
        assertThat(call("POST", "/v1/tokens", minter, tokenRequest(List.of("*"))).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/v1/tokens", minter, tokenRequest(List.of("jobs:write"))).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/v1/tokens", minter, tokenRequest(List.of("root"))).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/v1/tokens", minter, new JsonObject().put("scopes", new JsonArray().add("jobs:read"))).statusCode())
                .as("a name is required").isEqualTo(400);

        // Listed without secrets.
        JsonArray listed = call("GET", "/v1/tokens", minter, null).bodyAsJsonObject().getJsonArray("tokens");
        assertThat(listed).hasSize(2);
        for (Object token : listed) {
            assertThat(((JsonObject) token).containsKey("token")).isFalse();
            assertThat(((JsonObject) token).containsKey("hash")).isFalse();
        }
    }

    @Test
    void revokingATokenStopsItAtOnce() throws Exception {
        createTenant("revoker");
        String keeper = mint("revoker", List.of("*"));
        JsonObject victim = call("POST", "/v1/tokens", keeper, tokenRequest(List.of("jobs:read"))).bodyAsJsonObject();
        String victimBearer = "Bearer " + victim.getString("token");
        assertThat(call("GET", "/v1/jobs", victimBearer, null).statusCode()).isEqualTo(200);

        String otherTenant = TestAuth.bearer("someone-else");
        assertThat(call("DELETE", "/v1/tokens/" + victim.getString("id"), otherTenant, null).statusCode())
                .as("another tenant's token can't revoke it").isEqualTo(404);
        assertThat(call("DELETE", "/v1/tokens/" + victim.getString("id"), keeper, null).statusCode()).isEqualTo(204);

        assertThat(call("GET", "/v1/jobs", victimBearer, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/v1/jobs", keeper, null).statusCode()).isEqualTo(200);
    }

    @Test
    void aTenantIsDeletedOnlyOnceItIsEmpty() throws Exception {
        createTenant("leaving");
        String token = mint("leaving", List.of("*"));
        HttpResponse<Buffer> uploaded = await(client.put(node.httpPort(), "localhost", "/v1/files/notes.txt")
                .putHeader("Authorization", token).sendBuffer(Buffer.buffer("bye")));
        assertThat(uploaded.statusCode()).isEqualTo(201);

        assertThat(call("DELETE", "/v1/tenants/leaving", TestAuth.ADMIN, null).statusCode()).isEqualTo(409);
        assertThat(call("DELETE", "/v1/files/notes.txt", token, null).statusCode()).isEqualTo(204);
        assertThat(call("DELETE", "/v1/tenants/leaving", TestAuth.ADMIN, null).statusCode()).isEqualTo(204);

        assertThat(call("GET", "/v1/files", token, null).statusCode()).as("its tokens went with it").isEqualTo(401);
        assertThat(call("GET", "/v1/tenants/leaving", TestAuth.ADMIN, null).statusCode()).isEqualTo(404);
    }

    @Test
    void bodiesArriveIntactAfterTheAsyncTokenCheck() throws Exception {
        // A script big enough to arrive in many chunks while the token is being looked up.
        String padding = "// " + "x".repeat(200 * 1024) + "\n";
        String bearer = TestAuth.bearer("bodies");
        HttpResponse<Buffer> submitted = call("POST", "/v1/jobs", bearer,
                new JsonObject().put("script", padding + "export default () => 'intact'"));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);

        // Many requests in a row on pooled keep-alive connections, mixing body and no-body routes.
        for (int i = 0; i < 30; i++) {
            assertThat(call("GET", "/v1/jobs", bearer, null).statusCode()).isEqualTo(200);
            assertThat(call("POST", "/v1/bus/news", bearer, new JsonObject().put("i", i)).statusCode()).isEqualTo(202);
            assertThat(call("POST", "/v1/jobs/" + submitted.bodyAsJsonObject().getString("id") + "/cancel", bearer, null).statusCode())
                    .isIn(200, 202, 409);
        }
    }

    // --- helpers ---

    private static void createTenant(String name) throws Exception {
        HttpResponse<Buffer> created = call("POST", "/v1/tenants", TestAuth.ADMIN, new JsonObject().put("name", name));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
    }

    private static String mint(String tenant, List<String> scopes) throws Exception {
        HttpResponse<Buffer> minted = call("POST", "/v1/tenants/" + tenant + "/tokens", TestAuth.ADMIN, tokenRequest(scopes));
        assertThat(minted.statusCode()).as(minted.bodyAsString()).isEqualTo(201);
        return "Bearer " + minted.bodyAsJsonObject().getString("token");
    }

    private static JsonObject tokenRequest(List<String> scopes) {
        return new JsonObject().put("name", "test").put("scopes", new JsonArray(scopes));
    }

    private static HttpResponse<Buffer> call(String method, String path, String bearer, JsonObject body) throws Exception {
        var request = client.request(HttpMethod.valueOf(method), node.httpPort(), "localhost", path).putHeader("Authorization", bearer);
        return await(body == null ? request.send() : request.sendJsonObject(body));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
