package automately.server;

import automately.server.Automately.RunningNode;
import automately.server.api.Contract;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.contract.Operation;
import io.vertx.openapi.validation.ResponseValidator;
import io.vertx.openapi.validation.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI contract and the server agree (D-046): every route is in the spec and every operation has a route; one
 * real call per operation produces a response the spec allows; and bad input is refused by the contract.
 */
class ContractTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static OpenAPIContract contract;
    private static ResponseValidator responses;

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "contract"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_SECRETS_KEY", "contract-secrets-key-0123456789abcdef"),
                Map.entry("AUTOMATELY_DISK_ROOT", diskRoot().toString()),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        contract = await(Contract.load(clientVertx));
        responses = ResponseValidator.create(clientVertx, contract);
    }

    private static java.nio.file.Path diskRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("automately-contract-disk");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() throws Exception {
        await(clientVertx.close());
        await(node.stop());
    }

    @Test
    void everyRouteIsInTheContractAndEveryOperationHasARoute() {
        Set<String> routes = node.apiRoutes().stream()
                .map(route -> route.replaceAll(":([a-z]+)", "{$1}").replace("/v1/files/.+", "/v1/files/{path}").replace("/v1/dirs/.+", "/v1/dirs/{path}").replace("/v1/mounts/.+", "/v1/mounts/{path}").replace("/v1/shared/.+", "/v1/shared/{token}"))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> operations = contract.operations().stream()
                .map(operation -> operation.getHttpMethod().name() + " " + operation.getOpenAPIPath())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(routes).isEqualTo(operations);
    }

    @Test
    void everyOperationAnswersAsTheContractSays() throws Exception {
        Calls calls = new Calls();
        String admin = TestAuth.ADMIN;

        calls.check("getHealth", "GET", "/health", null, null);
        calls.check("getReady", "GET", "/ready", null, null);
        calls.check("getOpenApi", "GET", "/v1/openapi.json", null, null);
        calls.check("listNodes", "GET", "/v1/nodes", admin, null);
        calls.check("createTenant", "POST", "/v1/tenants", admin, new JsonObject().put("name", "contract"));
        calls.check("listTenants", "GET", "/v1/tenants", admin, null);
        calls.check("getTenant", "GET", "/v1/tenants/contract", admin, null);
        JsonObject minted = calls.check("createTenantToken", "POST", "/v1/tenants/contract/tokens", admin,
                new JsonObject().put("name", "contract").put("scopes", new JsonArray().add("*"))).bodyAsJsonObject();
        String tenant = "Bearer " + minted.getString("token");
        calls.check("listTenantTokens", "GET", "/v1/tenants/contract/tokens", admin, null);
        calls.check("rotateAdminToken", "POST", "/v1/admin/rotate", admin, null);
        calls.check("getWhoami", "GET", "/v1/whoami", tenant, null);

        JsonObject own = calls.check("createToken", "POST", "/v1/tokens", tenant,
                new JsonObject().put("name", "narrow").put("scopes", new JsonArray().add("jobs:read")).put("expiresInDays", 1)).bodyAsJsonObject();
        calls.check("listTokens", "GET", "/v1/tokens", tenant, null);
        calls.check("revokeToken", "DELETE", "/v1/tokens/" + own.getString("id"), tenant, null);
        JsonObject spare = calls.call("POST", "/v1/tenants/contract/tokens", admin,
                new JsonObject().put("name", "spare").put("scopes", new JsonArray().add("jobs:read"))).bodyAsJsonObject();
        calls.check("revokeTenantToken", "DELETE", "/v1/tenants/contract/tokens/" + spare.getString("id"), admin, null);

        String jobId = calls.check("submitJob", "POST", "/v1/jobs", tenant, new JsonObject()
                .put("script", "export default async (input, ctx) => { console.log('hi'); await ctx.step('one', async () => 1); return input; }")
                .put("input", new JsonObject().put("a", 1))
                .put("callback", new JsonObject().put("url", "https://receiver.invalid/hook").put("data", new JsonObject().put("order", 7))))
                .bodyAsJsonObject().getString("id");
        awaitStatus(tenant, jobId);
        calls.check("getJob", "GET", "/v1/jobs/" + jobId, tenant, null);
        calls.check("listJobs", "GET", "/v1/jobs?limit=10", tenant, null);
        calls.check("getJobSteps", "GET", "/v1/jobs/" + jobId + "/steps", tenant, null);
        calls.check("streamJobEvents", "GET", "/v1/jobs/" + jobId + "/events", tenant, null);
        calls.check("cancelJob", "POST", "/v1/jobs/" + jobId + "/cancel", tenant, null);

        String service = "export default async (input, ctx) => { await ctx.serve(() => 'ok'); return ctx.stopRequested; }";
        calls.check("putService", "PUT", "/v1/services/web", tenant, new JsonObject().put("script", service));
        calls.check("listServices", "GET", "/v1/services", tenant, null);
        calls.check("getService", "GET", "/v1/services/web", tenant, null);
        calls.check("stopService", "POST", "/v1/services/web/stop", tenant, null);
        calls.check("startService", "POST", "/v1/services/web/start", tenant, null);
        calls.check("restartService", "POST", "/v1/services/web/restart", tenant, null);
        calls.check("claimDomain", "POST", "/v1/services/web/domains", tenant, new JsonObject().put("domain", "shop.example.com"));
        calls.check("listDomains", "GET", "/v1/services/web/domains", tenant, null);
        calls.check("verifyDomain", "POST", "/v1/services/web/domains/shop.example.com/verify", tenant, null);
        calls.check("releaseDomain", "DELETE", "/v1/services/web/domains/shop.example.com", tenant, null);
        calls.check("deleteService", "DELETE", "/v1/services/web", tenant, null);

        HttpResponse<Buffer> stored = await(client.put(node.httpPort(), "localhost", "/v1/files/site/index.html")
                .putHeader("Authorization", tenant).putHeader("Content-Type", "text/html").sendBuffer(Buffer.buffer("<h1>hi</h1>")));
        calls.validate("putFile", stored);
        calls.check("listFiles", "GET", "/v1/files?prefix=site/&limit=5", tenant, null);
        calls.check("makeDir", "PUT", "/v1/dirs/site/assets", tenant, null);
        calls.check("listRootDir", "GET", "/v1/dirs?limit=10", tenant, null);
        calls.check("listDir", "GET", "/v1/dirs/site", tenant, null);
        JsonObject link = calls.check("shareFile", "POST", "/v1/fs/share", tenant, new JsonObject().put("path", "site/index.html")
                .put("expiresInSeconds", 60)).bodyAsJsonObject();
        calls.check("getSharedFile", "GET", "/v1/shared/" + link.getString("token"), null, null);
        calls.check("headSharedFile", "HEAD", "/v1/shared/" + link.getString("token"), null, null);
        calls.check("rotateFileLinks", "POST", "/v1/fs/share/rotate", tenant, null);
        calls.check("copyFiles", "POST", "/v1/fs/copy", tenant, new JsonObject().put("from", "site").put("to", "site-copy"));
        calls.check("moveFiles", "POST", "/v1/fs/move", tenant, new JsonObject().put("from", "site-copy/index.html").put("to", "moved.html"));
        calls.check("removeDir", "DELETE", "/v1/dirs/site-copy?recursive=true", tenant, null);
        calls.call("DELETE", "/v1/files/moved.html", tenant, null);
        calls.call("DELETE", "/v1/dirs/site/assets", tenant, null);
        calls.check("getFile", "GET", "/v1/files/site/index.html", tenant, null);
        calls.check("headFile", "HEAD", "/v1/files/site/index.html", tenant, null);
        calls.check("deleteFile", "DELETE", "/v1/files/site/index.html", tenant, null);

        calls.check("publish", "POST", "/v1/bus/news", tenant, new JsonObject().put("hello", true));
        calls.check("request", "POST", "/v1/bus/nobody-listens/request?timeoutMs=500", tenant, new JsonObject());

        calls.check("revokeNode", "POST", "/v1/cluster/nodes/node9/revoke", admin, null);
        calls.check("getCallbackSecret", "GET", "/v1/callbacks/secret", tenant, null);
        calls.check("rotateCallbackSecret", "POST", "/v1/callbacks/secret/rotate", tenant, null);
        calls.check("putSecret", "PUT", "/v1/secrets/db-url", tenant, new JsonObject().put("value", "postgres://u:p@db/app"));
        calls.check("getSecret", "GET", "/v1/secrets/db-url", tenant, null);
        calls.check("listSecrets", "GET", "/v1/secrets", tenant, null);
        calls.check("deleteSecret", "DELETE", "/v1/secrets/db-url", tenant, null);
        calls.check("pushQueueMessage", "POST", "/v1/queues/emails", tenant,
                new JsonObject().put("message", new JsonObject().put("to", "a@example.com")).put("key", "welcome-1").put("maxAttempts", 3));
        calls.check("getQueue", "GET", "/v1/queues/emails", tenant, null);
        calls.check("listDeadMessages", "GET", "/v1/queues/emails/dead?limit=10", tenant, null);
        calls.check("retryDeadMessage", "POST", "/v1/queues/emails/dead/nope/retry", tenant, null);
        calls.check("discardDeadMessage", "DELETE", "/v1/queues/emails/dead/nope", tenant, null);

        calls.check("mcp", "POST", "/mcp", tenant, new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"));
        calls.check("mcpStream", "GET", "/mcp", null, null);
        calls.check("mcpEndSession", "DELETE", "/mcp", null, null);

        calls.check("putStoragePolicy", "PUT", "/v1/tenants/contract/storage-policy", admin,
                new JsonObject().put("allow", new JsonArray().add("disk")).put("diskAllotmentMb", 10));
        calls.check("getStoragePolicy", "GET", "/v1/tenants/contract/storage-policy", admin, null);
        calls.check("putStorageProvider", "PUT", "/v1/storage/providers/local", tenant, new JsonObject().put("type", "disk"));
        calls.check("putMount", "PUT", "/v1/mounts/mnt/local", tenant, new JsonObject().put("provider", "local").put("readOnly", true));
        calls.check("listMounts", "GET", "/v1/mounts", tenant, null);
        calls.check("deleteMount", "DELETE", "/v1/mounts/mnt/local", tenant, null);
        calls.check("getStorageProvider", "GET", "/v1/storage/providers/local", tenant, null);
        calls.check("listStorageProviders", "GET", "/v1/storage/providers", tenant, null);
        calls.check("checkStorageProvider", "POST", "/v1/storage/providers/local/check", tenant, null);
        calls.check("putStorageRoutes", "PUT", "/v1/storage/routes", tenant,
                new JsonObject().put("default", "default").put("prefixes", new JsonObject().put("local/", "local")));
        calls.check("getStorageRoutes", "GET", "/v1/storage/routes", tenant, null);
        calls.check("getStorage", "GET", "/v1/storage", tenant, null);
        calls.call("PUT", "/v1/storage/routes", tenant, new JsonObject().put("default", "default"));
        calls.check("deleteStorageProvider", "DELETE", "/v1/storage/providers/local", tenant, null);

        // Identity (D-070) and the audit log (D-071).
        JsonObject ownerInvite = calls.check("createTenantInvite", "POST", "/v1/tenants/contract/invites", admin,
                new JsonObject().put("roles", new JsonArray().add("owner")).put("user", "casey")).bodyAsJsonObject();
        JsonObject redeemed = calls.check("redeemInvite", "POST", "/v1/invites/redeem", null,
                new JsonObject().put("code", ownerInvite.getString("code"))).bodyAsJsonObject();
        String person = "Bearer " + redeemed.getJsonObject("token").getString("token");
        calls.check("listTenantMembers", "GET", "/v1/tenants/contract/members", admin, null);
        calls.check("getMe", "GET", "/v1/me", person, null);
        calls.check("updateMe", "PATCH", "/v1/me", person, new JsonObject().put("displayName", "Casey").put("name", "casey-c"));
        JsonObject laptop = calls.check("createMyToken", "POST", "/v1/me/tokens", person, new JsonObject().put("name", "laptop")).bodyAsJsonObject();
        calls.check("listMyTokens", "GET", "/v1/me/tokens", person, null);
        calls.check("revokeMyToken", "DELETE", "/v1/me/tokens/" + laptop.getString("id"), person, null);
        calls.check("putRole", "PUT", "/v1/roles/reader", person,
                new JsonObject().put("scopes", new JsonArray().add("jobs:read")).put("description", "Reads jobs"));
        calls.check("listRoles", "GET", "/v1/roles", person, null);
        calls.check("putGroup", "PUT", "/v1/groups/readers", person, new JsonObject().put("roles", new JsonArray().add("reader")));
        calls.check("listGroups", "GET", "/v1/groups", person, null);
        JsonObject invite = calls.check("createInvite", "POST", "/v1/invites", person,
                new JsonObject().put("groups", new JsonArray().add("readers"))).bodyAsJsonObject();
        calls.check("listInvites", "GET", "/v1/invites", person, null);
        calls.call("POST", "/v1/invites/redeem", null, new JsonObject().put("code", invite.getString("code")).put("name", "sam"));
        calls.check("listMembers", "GET", "/v1/members", person, null);
        calls.check("getMember", "GET", "/v1/members/sam", person, null);
        calls.check("putMember", "PUT", "/v1/members/sam", person, new JsonObject().put("roles", new JsonArray().add("viewer")));
        calls.check("removeMember", "DELETE", "/v1/members/sam", person, null);
        calls.check("deleteGroup", "DELETE", "/v1/groups/readers", person, null);
        calls.check("deleteRole", "DELETE", "/v1/roles/reader", person, null);
        JsonObject spareInvite = calls.call("POST", "/v1/invites", person, new JsonObject().put("roles", new JsonArray().add("viewer"))).bodyAsJsonObject();
        calls.check("deleteInvite", "DELETE", "/v1/invites/" + spareInvite.getString("id"), person, null);
        calls.check("listAudit", "GET", "/v1/audit?limit=5", person, null);
        calls.check("createOperatorInvite", "POST", "/v1/admin/invites", admin, new JsonObject().put("note", "a second operator"));
        calls.check("listOperatorAudit", "GET", "/v1/admin/audit?limit=5", admin, null);
        calls.check("verifyAudit", "GET", "/v1/admin/audit/verify", admin, null);
        calls.check("listUsers", "GET", "/v1/users", admin, null);
        calls.check("getUser", "GET", "/v1/users/sam", admin, null);
        calls.check("updateUser", "PATCH", "/v1/users/sam", admin, new JsonObject().put("disabled", true));
        calls.check("deleteUser", "DELETE", "/v1/users/sam", admin, null);

        calls.check("deleteTenant", "DELETE", "/v1/tenants/contract", admin, null);

        Set<String> all = contract.operations().stream().map(Operation::getOperationId).collect(Collectors.toCollection(TreeSet::new));
        assertThat(calls.covered).as("every operation was called").isEqualTo(all);
    }

    @Test
    void badInputIsRefusedByTheContract() throws Exception {
        String bearer = TestAuth.bearer("strict");
        Calls calls = new Calls();

        HttpResponse<Buffer> notAString = calls.call("POST", "/v1/jobs", bearer, new JsonObject().put("script", 42));
        HttpResponse<Buffer> unknownPolicy = calls.call("POST", "/v1/jobs", bearer,
                new JsonObject().put("script", "export default () => 1").put("onOrphan", "sometimes"));
        HttpResponse<Buffer> limitTooBig = calls.call("GET", "/v1/jobs?limit=5000", bearer, null);
        HttpResponse<Buffer> badTopic = calls.call("POST", "/v1/bus/no%20spaces", bearer, new JsonObject());
        HttpResponse<Buffer> badScope = calls.call("POST", "/v1/tokens", bearer, new JsonObject().put("name", "x").put("scopes", new JsonArray().add("root")));

        for (HttpResponse<Buffer> refused : List.of(notAString, unknownPolicy, limitTooBig, badTopic, badScope)) {
            assertThat(refused.statusCode()).as(refused.bodyAsString()).isEqualTo(400);
            assertThat(refused.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("invalid_request");
        }

        // curl -d sends a form Content-Type; JSON routes still read it as JSON.
        HttpResponse<Buffer> formTyped = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer)
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .sendBuffer(new JsonObject().put("script", "export default () => 1").toBuffer()));
        assertThat(formTyped.statusCode()).as(formTyped.bodyAsString()).isEqualTo(202);
    }

    // --- helpers ---

    /** Makes calls and checks each response against its operation in the contract. */
    private static final class Calls {

        final Set<String> covered = new TreeSet<>();

        HttpResponse<Buffer> call(String method, String path, String bearer, JsonObject body) throws Exception {
            var request = client.request(HttpMethod.valueOf(method), node.httpPort(), "localhost", path);
            if (bearer != null) {
                request.putHeader("Authorization", bearer);
            }
            return await(body == null ? request.send() : request.sendJsonObject(body));
        }

        HttpResponse<Buffer> check(String operationId, String method, String path, String bearer, JsonObject body) throws Exception {
            return validate(operationId, call(method, path, bearer, body));
        }

        HttpResponse<Buffer> validate(String operationId, HttpResponse<Buffer> response) throws Exception {
            Map<String, String> headers = new HashMap<>();
            response.headers().forEach(h -> headers.put(h.getKey(), h.getValue()));
            String contentType = response.getHeader("Content-Type");
            if ((operationId.equals("getFile") || operationId.equals("getSharedFile")) && response.statusCode() < 300) {
                // A file comes back with its own type; the contract can only describe it as bytes.
                contentType = "application/octet-stream";
            }
            Buffer body = response.body();
            ValidatableResponse validatable = body == null || body.length() == 0 || contentType == null
                    ? ValidatableResponse.create(response.statusCode(), headers)
                    : ValidatableResponse.create(response.statusCode(), headers, body, contentType.split(";")[0].trim());
            try {
                await(responses.validate(validatable, operationId));
            } catch (Exception e) {
                throw new AssertionError(operationId + " answered " + response.statusCode() + " " + response.bodyAsString()
                        + ", which the contract doesn't allow: " + e.getCause(), e);
            }
            covered.add(operationId);
            return response;
        }
    }

    private static void awaitStatus(String bearer, String jobId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            String status = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + jobId).putHeader("Authorization", bearer).send())
                    .bodyAsJsonObject().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + jobId + " never succeeded");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
