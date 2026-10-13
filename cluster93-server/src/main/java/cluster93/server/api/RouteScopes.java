package cluster93.server.api;

import cluster93.core.auth.Scopes;
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

    /**
     * Who may call: anyone; the admin (the admin token or an operator, D-070); any valid token; a tenant scope; the admin
     * or a tenant scope; or a user, through their own personal token.
     */
    enum Who { PUBLIC, ADMIN, ANY, TENANT, ADMIN_OR_TENANT, USER }

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
    /** A secret's value, JSON-escaped at worst six bytes per byte, plus the object around it. */
    static final long SECRET_BODY = cluster93.core.secrets.TenantSecrets.MAX_VALUE_BYTES * 6L + 1024;
    /** A package manifest: a thousand files' paths and hashes, with room to spare. */
    static final long MANIFEST = 512 * 1024;
    /** A tenant's egress policy: a hundred rules with their notes (D-111). */
    static final long TENANT_EGRESS = 64 * 1024;
    /** The cluster's egress policy: 500 rules and 50 address sets of up to a thousand ranges each (D-111). */
    static final long CLUSTER_EGRESS = 4 * 1024 * 1024;
    private static final String SEG = "[^/]+";

    static final List<Rule> RULES = List.of(
            rule("GET", "/v1/openapi.json", "getOpenApi", Who.PUBLIC, null),
            rule("GET", "/v1/whoami", "getWhoami", Who.ANY, null),

            // Identity (D-070) and the audit log (D-071).
            json("POST", "/v1/invites/redeem", "redeemInvite", Who.PUBLIC, null, SMALL),
            json("POST", "/v1/auth/login", "login", Who.PUBLIC, null, SMALL),
            rule("GET", "/v1/auth/setup-status", "getSetupStatus", Who.PUBLIC, null),
            json("POST", "/v1/auth/setup", "initialSetup", Who.PUBLIC, null, SMALL),
            rule("GET", "/v1/me", "getMe", Who.USER, null),
            json("PATCH", "/v1/me", "updateMe", Who.USER, null, SMALL),
            json("PUT", "/v1/me/password", "updateMyPassword", Who.USER, null, SMALL),
            rule("GET", "/v1/me/tokens", "listMyTokens", Who.USER, null),
            json("POST", "/v1/me/tokens", "createMyToken", Who.USER, null, SMALL),
            rule("DELETE", "/v1/me/tokens/" + SEG, "revokeMyToken", Who.USER, null),
            rule("GET", "/v1/members", "listMembers", Who.TENANT, Scopes.MEMBERS_READ),
            rule("GET", "/v1/members/" + SEG, "getMember", Who.TENANT, Scopes.MEMBERS_READ),
            json("PUT", "/v1/members/" + SEG, "putMember", Who.TENANT, Scopes.MEMBERS_WRITE, SMALL),
            rule("DELETE", "/v1/members/" + SEG, "removeMember", Who.TENANT, Scopes.MEMBERS_WRITE),
            rule("GET", "/v1/roles", "listRoles", Who.TENANT, Scopes.MEMBERS_READ),
            json("PUT", "/v1/roles/" + SEG, "putRole", Who.TENANT, Scopes.MEMBERS_WRITE, SMALL),
            rule("DELETE", "/v1/roles/" + SEG, "deleteRole", Who.TENANT, Scopes.MEMBERS_WRITE),
            rule("GET", "/v1/groups", "listGroups", Who.TENANT, Scopes.MEMBERS_READ),
            json("PUT", "/v1/groups/" + SEG, "putGroup", Who.TENANT, Scopes.MEMBERS_WRITE, SMALL),
            rule("DELETE", "/v1/groups/" + SEG, "deleteGroup", Who.TENANT, Scopes.MEMBERS_WRITE),
            rule("GET", "/v1/invites", "listInvites", Who.TENANT, Scopes.MEMBERS_READ),
            json("POST", "/v1/invites", "createInvite", Who.TENANT, Scopes.MEMBERS_WRITE, SMALL),
            rule("DELETE", "/v1/invites/" + SEG, "deleteInvite", Who.TENANT, Scopes.MEMBERS_WRITE),
            rule("GET", "/v1/audit", "listAudit", Who.TENANT, Scopes.AUDIT_READ),
            rule("GET", "/v1/users", "listUsers", Who.ADMIN, null),
            rule("GET", "/v1/users/" + SEG, "getUser", Who.ADMIN, null),
            json("PATCH", "/v1/users/" + SEG, "updateUser", Who.ADMIN, null, SMALL),
            rule("DELETE", "/v1/users/" + SEG, "deleteUser", Who.ADMIN, null),
            rule("GET", "/v1/admin/invites", "listOperatorInvites", Who.ADMIN, null),
            json("POST", "/v1/admin/invites", "createOperatorInvite", Who.ADMIN, null, SMALL),
            rule("DELETE", "/v1/admin/invites/" + SEG, "revokeOperatorInvite", Who.ADMIN, null),
            rule("GET", "/v1/admin/audit", "listOperatorAudit", Who.ADMIN, null),
            rule("GET", "/v1/admin/audit/verify", "verifyAudit", Who.ADMIN, null),
            rule("GET", "/v1/tenants/" + SEG + "/members", "listTenantMembers", Who.ADMIN, null),
            json("POST", "/v1/tenants/" + SEG + "/invites", "createTenantInvite", Who.ADMIN, null, SMALL),

            rule("GET", "/v1/tenants", "listTenants", Who.ADMIN, null),
            json("POST", "/v1/tenants", "createTenant", Who.ADMIN, null, SMALL),
            rule("GET", "/v1/tenants/" + SEG, "getTenant", Who.ADMIN, null),
            rule("DELETE", "/v1/tenants/" + SEG, "deleteTenant", Who.ADMIN, null),
            rule("GET", "/v1/tenants/" + SEG + "/tokens", "listTenantTokens", Who.ADMIN, null),
            json("POST", "/v1/tenants/" + SEG + "/tokens", "createTenantToken", Who.ADMIN, null, SMALL),
            rule("DELETE", "/v1/tenants/" + SEG + "/tokens/" + SEG, "revokeTenantToken", Who.ADMIN, null),
            rule("POST", "/v1/admin/rotate", "rotateAdminToken", Who.ADMIN, null),
            rule("POST", "/v1/cluster/nodes/" + SEG + "/revoke", "revokeNode", Who.ADMIN, null),
            rule("GET", "/v1/tenants/" + SEG + "/storage-policy", "getStoragePolicy", Who.ADMIN, null),
            json("PUT", "/v1/tenants/" + SEG + "/storage-policy", "putStoragePolicy", Who.ADMIN, null, SMALL),
            rule("GET", "/v1/tenants/" + SEG + "/egress-policy", "getTenantEgressPolicy", Who.ADMIN, null),
            json("PUT", "/v1/tenants/" + SEG + "/egress-policy", "putTenantEgressPolicy", Who.ADMIN, null, TENANT_EGRESS),
            rule("GET", "/v1/egress-policy", "getEgressPolicy", Who.TENANT, Scopes.JOBS_READ),
            rule("GET", "/v1/egress/cluster-policy", "getClusterEgressPolicy", Who.ADMIN, null),
            json("PUT", "/v1/egress/cluster-policy", "putClusterEgressPolicy", Who.ADMIN, null, CLUSTER_EGRESS),

            rule("GET", "/v1/storage", "getStorage", Who.TENANT, Scopes.STORAGE_READ),
            rule("GET", "/v1/storage/providers", "listStorageProviders", Who.TENANT, Scopes.STORAGE_READ),
            rule("GET", "/v1/storage/providers/" + SEG, "getStorageProvider", Who.TENANT, Scopes.STORAGE_READ),
            json("PUT", "/v1/storage/providers/" + SEG, "putStorageProvider", Who.TENANT, Scopes.STORAGE_WRITE, SMALL),
            rule("DELETE", "/v1/storage/providers/" + SEG, "deleteStorageProvider", Who.TENANT, Scopes.STORAGE_WRITE),
            rule("POST", "/v1/storage/providers/" + SEG + "/check", "checkStorageProvider", Who.TENANT, Scopes.STORAGE_WRITE),
            rule("GET", "/v1/storage/routes", "getStorageRoutes", Who.TENANT, Scopes.STORAGE_READ),
            json("PUT", "/v1/storage/routes", "putStorageRoutes", Who.TENANT, Scopes.STORAGE_WRITE, SMALL),

            rule("GET", "/v1/mounts", "listMounts", Who.TENANT, Scopes.STORAGE_READ),
            json("PUT", "/v1/mounts/.+", "putMount", Who.TENANT, Scopes.STORAGE_WRITE, SMALL),
            rule("DELETE", "/v1/mounts/.+", "deleteMount", Who.TENANT, Scopes.STORAGE_WRITE),

            rule("GET", "/v1/nodes", "listNodes", Who.ADMIN_OR_TENANT, Scopes.NODES_READ),

            rule("GET", "/v1/tokens", "listTokens", Who.TENANT, Scopes.TOKENS_WRITE),
            json("POST", "/v1/tokens", "createToken", Who.TENANT, Scopes.TOKENS_WRITE, SMALL),
            rule("DELETE", "/v1/tokens/" + SEG, "revokeToken", Who.TENANT, Scopes.TOKENS_WRITE),
            rule("GET", "/v1/callbacks/secret", "getCallbackSecret", Who.TENANT, Scopes.TOKENS_WRITE),
            rule("POST", "/v1/callbacks/secret/rotate", "rotateCallbackSecret", Who.TENANT, Scopes.TOKENS_WRITE),

            rule("GET", "/v1/secrets", "listSecrets", Who.TENANT, Scopes.SECRETS_READ),
            rule("GET", "/v1/secrets/" + SEG, "getSecret", Who.TENANT, Scopes.SECRETS_READ),
            json("PUT", "/v1/secrets/" + SEG, "putSecret", Who.TENANT, Scopes.SECRETS_WRITE, SECRET_BODY),
            rule("DELETE", "/v1/secrets/" + SEG, "deleteSecret", Who.TENANT, Scopes.SECRETS_WRITE),

            rule("GET", "/v1/jobs", "listJobs", Who.TENANT, Scopes.JOBS_READ),
            json("POST", "/v1/jobs", "submitJob", Who.TENANT, Scopes.JOBS_WRITE, ApiVerticle.MAX_SCRIPT_BYTES + 4_096),
            rule("GET", "/v1/jobs/" + SEG, "getJob", Who.TENANT, Scopes.JOBS_READ),
            rule("GET", "/v1/jobs/" + SEG + "/events", "streamJobEvents", Who.TENANT, Scopes.JOBS_READ),
            rule("GET", "/v1/jobs/" + SEG + "/steps", "getJobSteps", Who.TENANT, Scopes.JOBS_READ),
            rule("POST", "/v1/jobs/" + SEG + "/cancel", "cancelJob", Who.TENANT, Scopes.JOBS_WRITE),

            // Apps and their deployments (docs/design/apps-and-console.md).
            rule("GET", "/v1/apps", "listApps", Who.TENANT, Scopes.APPS_READ),
            rule("GET", "/v1/apps/" + SEG, "getApp", Who.TENANT, Scopes.APPS_READ),
            rule("DELETE", "/v1/apps/" + SEG, "deleteApp", Who.TENANT, Scopes.APPS_WRITE),
            rule("GET", "/v1/apps/" + SEG + "/deployments", "listDeployments", Who.TENANT, Scopes.APPS_READ),
            json("POST", "/v1/apps/" + SEG + "/deployments", "createDeployment", Who.TENANT, Scopes.APPS_WRITE, MANIFEST),
            rule("GET", "/v1/apps/" + SEG + "/deployments/" + SEG, "getDeployment", Who.TENANT, Scopes.APPS_READ),
            json("POST", "/v1/apps/" + SEG + "/promote", "promoteApp", Who.TENANT, Scopes.APPS_WRITE, SMALL),
            rule("POST", "/v1/apps/" + SEG + "/rollback", "rollbackApp", Who.TENANT, Scopes.APPS_WRITE),

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

            rule("GET", "/v1/dirs", "listRootDir", Who.TENANT, Scopes.FILES_READ),
            rule("GET", "/v1/dirs/.+", "listDir", Who.TENANT, Scopes.FILES_READ),
            rule("PUT", "/v1/dirs/.+", "makeDir", Who.TENANT, Scopes.FILES_WRITE),
            rule("DELETE", "/v1/dirs/.+", "removeDir", Who.TENANT, Scopes.FILES_WRITE),
            json("POST", "/v1/fs/share", "shareFile", Who.TENANT, Scopes.FILES_WRITE, SMALL),
            rule("POST", "/v1/fs/share/rotate", "rotateFileLinks", Who.TENANT, Scopes.FILES_WRITE),
            rule("GET", "/v1/shared/.+", "getSharedFile", Who.PUBLIC, null),
            rule("HEAD", "/v1/shared/.+", "headSharedFile", Who.PUBLIC, null),
            json("POST", "/v1/fs/move", "moveFiles", Who.TENANT, Scopes.FILES_WRITE, SMALL),
            json("POST", "/v1/fs/copy", "copyFiles", Who.TENANT, Scopes.FILES_WRITE, SMALL),
            rule("GET", "/v1/files", "listFiles", Who.TENANT, Scopes.FILES_READ),
            rule("GET", "/v1/files/.+", "getFile", Who.TENANT, Scopes.FILES_READ),
            rule("HEAD", "/v1/files/.+", "headFile", Who.TENANT, Scopes.FILES_READ),
            new Rule(HttpMethod.PUT, Pattern.compile("/v1/files/.+"), "putFile", Who.TENANT, Scopes.FILES_WRITE, Body.STREAM, 0),
            rule("DELETE", "/v1/files/.+", "deleteFile", Who.TENANT, Scopes.FILES_WRITE),

            // Packages (D-083). Operators publish the cluster's, under @cluster/; a cluster package's slash is encoded (%2F).
            rule("GET", "/v1/packages", "listPackages", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_READ),
            json("POST", "/v1/packages", "publishPackage", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_WRITE, MANIFEST),
            json("POST", "/v1/packages/blobs/missing", "findMissingPackageFiles", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_WRITE, MANIFEST),
            new Rule(HttpMethod.PUT, Pattern.compile("/v1/packages/blobs/" + SEG), "putPackageFile", Who.ADMIN_OR_TENANT,
                    Scopes.PACKAGES_WRITE, Body.STREAM, 0),
            rule("GET", "/v1/packages/" + SEG, "getPackage", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_READ),
            rule("GET", "/v1/packages/" + SEG + "/versions/" + SEG, "getPackageVersion", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_READ),
            rule("POST", "/v1/packages/" + SEG + "/versions/" + SEG + "/yank", "yankPackageVersion", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_WRITE),
            rule("POST", "/v1/packages/" + SEG + "/versions/" + SEG + "/unyank", "unyankPackageVersion", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_WRITE),
            json("POST", "/v1/packages/" + SEG + "/versions/" + SEG + "/block", "blockPackageVersion", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_ADMIN, SMALL),
            rule("POST", "/v1/packages/" + SEG + "/versions/" + SEG + "/unblock", "unblockPackageVersion", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_ADMIN),
            json("POST", "/v1/packages/" + SEG + "/versions/" + SEG + "/purge", "purgePackageVersion", Who.ADMIN_OR_TENANT, Scopes.PACKAGES_ADMIN, SMALL),

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
