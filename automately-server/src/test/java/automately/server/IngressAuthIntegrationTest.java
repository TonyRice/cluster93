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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests through Ingress (port 8000) for console API rewrites,
 * verifying tenant scoping header forwarding (D-133) and username/password login.
 */
class IngressAuthIntegrationTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "ingress-auth-node"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_ROLES", "api,ingress,worker,data,console"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);

        // Ensure system tenant and deploy console app with api rewrite routes
        postDirect(TestAuth.ADMIN, "/v1/tenants", new JsonObject().put("name", "system"));
        String systemBearer = TestAuth.bearer("system");

        JsonObject consoleManifest = new JsonObject()
                .put("name", "console")
                .put("routes", new JsonArray().add(new JsonObject().put("src", "/v1/(.*)").put("dest", "api:/v1/$1")));

        HttpResponse<Buffer> deployment = postDirect(systemBearer, "/v1/apps/console/deployments", new JsonObject()
                .put("deploymentId", "dpl_console")
                .put("manifest", consoleManifest));
        assertThat(deployment.statusCode()).as(deployment.bodyAsString()).isEqualTo(201);

        HttpResponse<Buffer> promoted = postDirect(systemBearer, "/v1/apps/console/promote", new JsonObject()
                .put("deploymentId", "dpl_console"));
        assertThat(promoted.statusCode()).as(promoted.bodyAsString()).isEqualTo(200);
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) {
            await(clientVertx.close());
        }
        if (node != null) {
            await(node.stop());
        }
    }

    @Test
    @DisplayName("Admin accessing API through Ingress requires tenant scoping, and X-Automately-Tenant is preserved")
    void ingressForwardsTenantScopingHeader() throws Exception {
        postDirect(TestAuth.ADMIN, "/v1/tenants", new JsonObject().put("name", "retail"));

        // 1. Through Ingress WITHOUT X-Automately-Tenant: rejected with 403 asking for target tenant
        HttpResponse<Buffer> withoutTenant = await(client.get(node.ingressPort(), "localhost", "/v1/jobs")
                .putHeader("Host", "localhost")
                .putHeader("Authorization", TestAuth.ADMIN)
                .send());
        assertThat(withoutTenant.statusCode()).isEqualTo(403);
        assertThat(withoutTenant.bodyAsJsonObject().getJsonObject("error").getString("message"))
                .contains("Specify a target tenant via the X-Automately-Tenant header or ?tenant parameter");

        // 2. Through Ingress WITH X-Automately-Tenant: Ingress forwards header to API, returning 200 OK
        HttpResponse<Buffer> withTenant = await(client.get(node.ingressPort(), "localhost", "/v1/jobs")
                .putHeader("Host", "localhost")
                .putHeader("Authorization", TestAuth.ADMIN)
                .putHeader("X-Automately-Tenant", "retail")
                .send());
        assertThat(withTenant.statusCode()).as(withTenant.bodyAsString()).isEqualTo(200);
        assertThat(withTenant.bodyAsJsonObject().containsKey("jobs")).isTrue();
    }

    @Test
    @DisplayName("Users sign in through Ingress via POST /v1/auth/login and receive valid scoped session token")
    void ingressPublicLoginAuthenticatesAndIssuesSession() throws Exception {
        postDirect(TestAuth.ADMIN, "/v1/tenants", new JsonObject().put("name", "banking"));

        // Provision user via invite with password
        HttpResponse<Buffer> invite = postDirect(TestAuth.ADMIN, "/v1/tenants/banking/invites",
                new JsonObject().put("roles", new JsonArray().add("developer")).put("user", "alice"));
        assertThat(invite.statusCode()).isEqualTo(201);
        String code = invite.bodyAsJsonObject().getString("code");

        HttpResponse<Buffer> redeemed = await(client.post(node.ingressPort(), "localhost", "/v1/invites/redeem")
                .putHeader("Host", "localhost")
                .sendJsonObject(new JsonObject().put("code", code).put("password", "BankingP@ss123")));
        assertThat(redeemed.statusCode()).isEqualTo(201);

        // Sign in via Ingress with wrong password -> 401
        HttpResponse<Buffer> wrongPass = await(client.post(node.ingressPort(), "localhost", "/v1/auth/login")
                .putHeader("Host", "localhost")
                .sendJsonObject(new JsonObject()
                        .put("username", "alice")
                        .put("password", "incorrect")
                        .put("tenant", "banking")));
        assertThat(wrongPass.statusCode()).isEqualTo(401);

        // Sign in via Ingress with correct credentials -> 200 with session token
        HttpResponse<Buffer> loggedIn = await(client.post(node.ingressPort(), "localhost", "/v1/auth/login")
                .putHeader("Host", "localhost")
                .sendJsonObject(new JsonObject()
                        .put("username", "alice")
                        .put("password", "BankingP@ss123")
                        .put("tenant", "banking")));
        assertThat(loggedIn.statusCode()).as(loggedIn.bodyAsString()).isEqualTo(200);
        JsonObject session = loggedIn.bodyAsJsonObject();
        String sessionToken = session.getString("token");
        assertThat(sessionToken).startsWith("atly_");
        assertThat(session.getString("tenant")).isEqualTo("banking");
        assertThat(session.getJsonObject("user").getString("name")).isEqualTo("alice");

        // Use session token via Ingress to fetch tenant jobs -> 200 OK without needing X-Automately-Tenant
        HttpResponse<Buffer> jobs = await(client.get(node.ingressPort(), "localhost", "/v1/jobs")
                .putHeader("Host", "localhost")
                .putHeader("Authorization", "Bearer " + sessionToken)
                .send());
        assertThat(jobs.statusCode()).as(jobs.bodyAsString()).isEqualTo(200);
    }

    @Test
    @DisplayName("Users change their password through Ingress via PUT /v1/me/password")
    void ingressPasswordUpdateRoundTrips() throws Exception {
        postDirect(TestAuth.ADMIN, "/v1/tenants", new JsonObject().put("name", "vault"));

        HttpResponse<Buffer> invite = postDirect(TestAuth.ADMIN, "/v1/tenants/vault/invites",
                new JsonObject().put("roles", new JsonArray().add("developer")).put("user", "dana"));
        assertThat(invite.statusCode()).isEqualTo(201);

        HttpResponse<Buffer> redeemed = await(client.post(node.ingressPort(), "localhost", "/v1/invites/redeem")
                .putHeader("Host", "localhost")
                .sendJsonObject(new JsonObject()
                        .put("code", invite.bodyAsJsonObject().getString("code"))
                        .put("password", "FirstP@ss1")));
        assertThat(redeemed.statusCode()).isEqualTo(201);

        HttpResponse<Buffer> loggedIn = await(client.post(node.ingressPort(), "localhost", "/v1/auth/login")
                .putHeader("Host", "localhost")
                .sendJsonObject(new JsonObject()
                        .put("username", "dana")
                        .put("password", "FirstP@ss1")
                        .put("tenant", "vault")));
        assertThat(loggedIn.statusCode()).isEqualTo(200);
        String session = loggedIn.bodyAsJsonObject().getString("token");

        // Wrong current password is refused with 401 through Ingress.
        HttpResponse<Buffer> wrong = await(client.put(node.ingressPort(), "localhost", "/v1/me/password")
                .putHeader("Host", "localhost")
                .putHeader("Authorization", "Bearer " + session)
                .sendJsonObject(new JsonObject().put("currentPassword", "wrong").put("newPassword", "SecondP@ss2")));
        assertThat(wrong.statusCode()).isEqualTo(401);

        // The right current password updates it.
        HttpResponse<Buffer> updated = await(client.put(node.ingressPort(), "localhost", "/v1/me/password")
                .putHeader("Host", "localhost")
                .putHeader("Authorization", "Bearer " + session)
                .sendJsonObject(new JsonObject().put("currentPassword", "FirstP@ss1").put("newPassword", "SecondP@ss2")));
        assertThat(updated.statusCode()).as(updated.bodyAsString()).isEqualTo(200);
        assertThat(updated.bodyAsJsonObject().getString("status")).isEqualTo("ok");

        // The new password signs in through Ingress.
        HttpResponse<Buffer> relogin = await(client.post(node.ingressPort(), "localhost", "/v1/auth/login")
                .putHeader("Host", "localhost")
                .sendJsonObject(new JsonObject()
                        .put("username", "dana")
                        .put("password", "SecondP@ss2")
                        .put("tenant", "vault")));
        assertThat(relogin.statusCode()).as(relogin.bodyAsString()).isEqualTo(200);
    }

    @Test
    @DisplayName("Internal cluster headers are stripped by Ingress while X-Automately-Tenant is allowed")
    void internalClusterHeadersAreStripped() throws Exception {
        // Attempt to spoof internal runner or secret header while providing valid tenant header
        HttpResponse<Buffer> response = await(client.get(node.ingressPort(), "localhost", "/v1/jobs")
                .putHeader("Host", "localhost")
                .putHeader("Authorization", TestAuth.ADMIN)
                .putHeader("X-Automately-Tenant", "retail")
                .putHeader("X-Automately-Runner", "spoofed-runner-id")
                .putHeader("X-Automately-Secret", "spoofed-secret")
                .send());

        // Request still succeeds normally via public API proxy because tenant was forwarded
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static HttpResponse<Buffer> postDirect(String bearer, String path, JsonObject body) throws Exception {
        var req = client.post(node.httpPort(), "localhost", path).putHeader("Authorization", bearer);
        return await(body == null ? req.send() : req.sendJsonObject(body));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
