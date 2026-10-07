package automately.server.api;

import automately.core.auth.Scopes;
import io.vertx.core.http.HttpMethod;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Every API operation: who may call it (D-045), its operation in the OpenAPI contract (D-046), and how its body is
 * read. It fails closed: a {@code /v1} request matching no rule is answered 404 before any handler runs, and the
 * node won't start if a rule names an operation the contract doesn't have.
 */
final class RouteScopes {

    /** Who may call: anyone, the admin only, any valid token, a tenant scope, or the admin or a tenant scope. */
    enum Who { PUBLIC, ADMIN, ANY, TENANT, ADMIN_OR_TENANT }

    /** No body; a JSON body read in full (up to {@link Rule#bodyLimit}); or a body the handler streams itself. */
    enum Body { NONE, JSON, STREAM }

    record Rule(HttpMethod method, Pattern path, String operationId, Who who, String scope, Body body, long bodyLimit) {

        boolean matches(HttpMethod requestMethod, String requestPath) {
            return method.equals(requestMethod) && path.matcher(requestPath).matches();
        }
    }

    static final long SMALL = 16 * 1024;
    static final long SCRIPT = ApiVerticle.MAX_SCRIPT_BYTES + 64 * 1024;
    static final long MESSAGE = BusRoutes.MAX_MESSAGE_BYTES;
    private static final String SEG = "[^/]+";

    static final List<Rule> RULES = List.of(
            rule("GET", "/v1/openapi.json", "getOpenApi", Who.PUBLIC, null),
            rule("GET", "/v1/whoami", "getWhoami", Who.ANY, null),

            rule("GET", "/v1/tenants", "listTenants", Who.ADMIN, null),
            json("POST", "/v1/tenants", "createTenant", Who.ADMIN, null, SMALL),
            rule("GET", "/v1/tenants/" + SEG, "getTenant", Who.ADMIN, null),
            rule("DELETE", "/v1/tenants/" + SEG, "deleteTenant", Who.ADMIN, null),
            rule("GET", "/v1/tenants/" + SEG + "/tokens", "listTenantTokens", Who.ADMIN, null),
            json("POST", "/v1/tenants/" + SEG + "/tokens", "createTenantToken", Who.ADMIN, null, SMALL),
            rule("DELETE", "/v1/tenants/" + SEG + "/tokens/" + SEG, "revokeTenantToken", Who.ADMIN, null),
            rule("POST", "/v1/admin/rotate", "rotateAdminToken", Who.ADMIN, null),
            rule("GET", "/v1/tenants/" + SEG + "/storage-policy", "getStoragePolicy", Who.ADMIN, null),
            json("PUT", "/v1/tenants/" + SEG + "/storage-policy", "putStoragePolicy", Who.ADMIN, null, SMALL),

            rule("GET", "/v1/storage", "getStorage", Who.TENANT, Scopes.STORAGE_READ),
            rule("GET", "/v1/storage/providers", "listStorageProviders", Who.TENANT, Scopes.STORAGE_READ),
            rule("GET", "/v1/storage/providers/" + SEG, "getStorageProvider", Who.TENANT, Scopes.STORAGE_READ),
            json("PUT", "/v1/storage/providers/" + SEG, "putStorageProvider", Who.TENANT, Scopes.STORAGE_WRITE, SMALL),
            rule("DELETE", "/v1/storage/providers/" + SEG, "deleteStorageProvider", Who.TENANT, Scopes.STORAGE_WRITE),
            rule("POST", "/v1/storage/providers/" + SEG + "/check", "checkStorageProvider", Who.TENANT, Scopes.STORAGE_WRITE),
            rule("GET", "/v1/storage/routes", "getStorageRoutes", Who.TENANT, Scopes.STORAGE_READ),
            json("PUT", "/v1/storage/routes", "putStorageRoutes", Who.TENANT, Scopes.STORAGE_WRITE, SMALL),

            rule("GET", "/v1/nodes", "listNodes", Who.ADMIN_OR_TENANT, Scopes.NODES_READ),

            rule("GET", "/v1/tokens", "listTokens", Who.TENANT, Scopes.TOKENS_WRITE),
            json("POST", "/v1/tokens", "createToken", Who.TENANT, Scopes.TOKENS_WRITE, SMALL),
            rule("DELETE", "/v1/tokens/" + SEG, "revokeToken", Who.TENANT, Scopes.TOKENS_WRITE),

            rule("GET", "/v1/jobs", "listJobs", Who.TENANT, Scopes.JOBS_READ),
            json("POST", "/v1/jobs", "submitJob", Who.TENANT, Scopes.JOBS_WRITE, ApiVerticle.MAX_SCRIPT_BYTES + 4_096),
            rule("GET", "/v1/jobs/" + SEG, "getJob", Who.TENANT, Scopes.JOBS_READ),
            rule("GET", "/v1/jobs/" + SEG + "/events", "streamJobEvents", Who.TENANT, Scopes.JOBS_READ),
            rule("GET", "/v1/jobs/" + SEG + "/steps", "getJobSteps", Who.TENANT, Scopes.JOBS_READ),
            rule("POST", "/v1/jobs/" + SEG + "/cancel", "cancelJob", Who.TENANT, Scopes.JOBS_WRITE),

            rule("GET", "/v1/services", "listServices", Who.TENANT, Scopes.SERVICES_READ),
            rule("GET", "/v1/services/" + SEG, "getService", Who.TENANT, Scopes.SERVICES_READ),
            json("PUT", "/v1/services/" + SEG, "putService", Who.TENANT, Scopes.SERVICES_WRITE, SCRIPT),
            rule("DELETE", "/v1/services/" + SEG, "deleteService", Who.TENANT, Scopes.SERVICES_WRITE),
            rule("POST", "/v1/services/" + SEG + "/start", "startService", Who.TENANT, Scopes.SERVICES_WRITE),
            rule("POST", "/v1/services/" + SEG + "/stop", "stopService", Who.TENANT, Scopes.SERVICES_WRITE),
            rule("POST", "/v1/services/" + SEG + "/restart", "restartService", Who.TENANT, Scopes.SERVICES_WRITE),
            rule("GET", "/v1/services/" + SEG + "/domains", "listDomains", Who.TENANT, Scopes.SERVICES_READ),
            json("POST", "/v1/services/" + SEG + "/domains", "claimDomain", Who.TENANT, Scopes.SERVICES_WRITE, SMALL),
            rule("POST", "/v1/services/" + SEG + "/domains/" + SEG + "/verify", "verifyDomain", Who.TENANT, Scopes.SERVICES_WRITE),
            rule("DELETE", "/v1/services/" + SEG + "/domains/" + SEG, "releaseDomain", Who.TENANT, Scopes.SERVICES_WRITE),

            rule("GET", "/v1/files", "listFiles", Who.TENANT, Scopes.FILES_READ),
            rule("GET", "/v1/files/.+", "getFile", Who.TENANT, Scopes.FILES_READ),
            rule("HEAD", "/v1/files/.+", "headFile", Who.TENANT, Scopes.FILES_READ),
            new Rule(HttpMethod.PUT, Pattern.compile("/v1/files/.+"), "putFile", Who.TENANT, Scopes.FILES_WRITE, Body.STREAM, 0),
            rule("DELETE", "/v1/files/.+", "deleteFile", Who.TENANT, Scopes.FILES_WRITE),

            json("POST", "/v1/bus/" + SEG + "/request", "request", Who.TENANT, Scopes.BUS_REQUEST, MESSAGE),
            json("POST", "/v1/bus/" + SEG, "publish", Who.TENANT, Scopes.BUS_PUBLISH, MESSAGE),

            json("POST", "/v1/queues/" + SEG, "pushQueueMessage", Who.TENANT, Scopes.QUEUES_WRITE, MESSAGE + SMALL),
            rule("GET", "/v1/queues/" + SEG, "getQueue", Who.TENANT, Scopes.QUEUES_READ),
            rule("GET", "/v1/queues/" + SEG + "/dead", "listDeadMessages", Who.TENANT, Scopes.QUEUES_READ),
            rule("POST", "/v1/queues/" + SEG + "/dead/" + SEG + "/retry", "retryDeadMessage", Who.TENANT, Scopes.QUEUES_WRITE),
            rule("DELETE", "/v1/queues/" + SEG + "/dead/" + SEG, "discardDeadMessage", Who.TENANT, Scopes.QUEUES_WRITE));

    private RouteScopes() {
    }

    private static Rule rule(String method, String path, String operationId, Who who, String scope) {
        return new Rule(HttpMethod.valueOf(method), Pattern.compile(path), operationId, who, scope, Body.NONE, 0);
    }

    private static Rule json(String method, String path, String operationId, Who who, String scope, long limit) {
        return new Rule(HttpMethod.valueOf(method), Pattern.compile(path), operationId, who, scope, Body.JSON, limit);
    }

    static Optional<Rule> find(HttpMethod method, String path) {
        String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return RULES.stream().filter(rule -> rule.matches(method, normalized)).findFirst();
    }
}
