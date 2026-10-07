package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens for tests (D-044, D-045). Every test cluster runs with {@link #ADMIN_TOKEN}; a tenant's token (scope
 * {@code *}) is minted the first time a test asks for it, through whichever node {@link #use} last named.
 */
final class TestAuth {

    static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef-0123456789abcdef";
    static final String ADMIN = "Bearer " + ADMIN_TOKEN;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Map<String, String> BEARERS = new ConcurrentHashMap<>();
    private static volatile int port;

    private TestAuth() {
    }

    /** A new cluster: tokens are minted through this node from now on. */
    static void use(RunningNode node) {
        port = node.httpPort();
        BEARERS.clear();
    }

    /** {@code Bearer atly_…} for the tenant, creating the tenant and a token with every scope if needed. */
    static String bearer(String tenant) {
        return BEARERS.computeIfAbsent(tenant, TestAuth::mint);
    }

    private static String mint(String tenant) {
        try {
            HttpResponse<String> created = post("/v1/tenants", new JsonObject().put("name", tenant));
            if (created.statusCode() != 201 && created.statusCode() != 409) {
                throw new IllegalStateException("Creating tenant " + tenant + ": " + created.statusCode() + " " + created.body());
            }
            HttpResponse<String> token = post("/v1/tenants/" + tenant + "/tokens",
                    new JsonObject().put("name", "test").put("scopes", new JsonArray().add("*")));
            if (token.statusCode() != 201) {
                throw new IllegalStateException("Minting a token for " + tenant + ": " + token.statusCode() + " " + token.body());
            }
            return "Bearer " + new JsonObject(token.body()).getString("token");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> post(String path, JsonObject body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", ADMIN).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.encode())).build(), HttpResponse.BodyHandlers.ofString());
    }
}
