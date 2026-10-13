package cluster93.server.api;

import cluster93.core.audit.AuditEntry;
import cluster93.core.audit.AuditLog;
import cluster93.core.auth.ApiToken;
import cluster93.core.auth.AuthFailure;
import cluster93.core.auth.AuthStore;
import cluster93.core.auth.Authenticator;
import cluster93.core.auth.Principal;
import cluster93.core.auth.Scopes;
import cluster93.core.identity.Access;
import cluster93.core.identity.Identity;
import cluster93.core.identity.Invite;
import cluster93.core.identity.User;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static cluster93.server.api.ApiVerticle.error;

/**
 * Users, members, roles, groups, invites (D-070) and the audit log (D-071). {@link RouteScopes} has already checked
 * who may call each route; {@link Identity} checks the rest (nobody grants more than they hold).
 */
final class IdentityRoutes {

    static final int DEFAULT_PAGE = 100;

    /** How long a personal session token minted by signing in lasts (D-133). */
    static final long SESSION_DAYS = 30;

    private final Identity identity;
    private final AuditLog audit;
    private final AuthStore authStore;
    private final Authenticator authenticator;
    private final SecureRandom random;
    private final String secretsKey;

    IdentityRoutes(Identity identity, AuditLog audit, AuthStore authStore, Authenticator authenticator, SecureRandom random, String secretsKey) {
        this.secretsKey = secretsKey;
        this.identity = identity;
        this.audit = audit;
        this.authStore = authStore;
        this.authenticator = authenticator;
        this.random = random;
    }

    void mount(Router router) {
        router.post("/v1/auth/login").handler(this::login);
        router.post("/v1/invites/redeem").handler(this::redeem);

        router.get("/v1/me").handler(ctx -> respond(ctx, identity.me(principal(ctx).user()), 200));
        router.patch("/v1/me").handler(ctx -> withBody(ctx, body -> respond(ctx, identity.updateProfile(principal(ctx).user(),
                body.getString("name"), body.getString("displayName"), body.getString("email")).map(User::toJson), 200)));
        router.put("/v1/me/password").handler(this::updateMyPassword);
        router.get("/v1/me/tokens").handler(ctx -> respond(ctx, identity.tokensOf(principal(ctx).user())
                .map(all -> new JsonObject().put("tokens", new JsonArray(all.stream().map(ApiToken::toViewJson).toList()))), 200));
        router.post("/v1/me/tokens").handler(this::createMyToken);
        router.delete("/v1/me/tokens/:id").handler(ctx -> removed(ctx, identity.revokePersonalToken(principal(ctx).user(), ctx.pathParam("id")),
                "No such token of yours"));

        router.get("/v1/members").handler(ctx -> respond(ctx, identity.members(tenant(ctx)).map(all -> list("members", all)), 200));
        router.get("/v1/members/:user").handler(ctx -> respond(ctx, identity.member(tenant(ctx), ctx.pathParam("user")), 200));
        router.put("/v1/members/:user").handler(ctx -> withBody(ctx, body -> respond(ctx, identity.setMember(principal(ctx), tenant(ctx),
                ctx.pathParam("user"), strings(body, "roles"), strings(body, "groups")), 200)));
        router.delete("/v1/members/:user").handler(ctx -> respond(ctx, identity.removeMember(principal(ctx), tenant(ctx), ctx.pathParam("user"))
                .map(tokens -> left(ctx.pathParam("user"), tokens)), 200));

        router.get("/v1/roles").handler(ctx -> respond(ctx, identity.roles(tenant(ctx))
                .map(all -> list("roles", all.stream().map(Access.Role::toJson).toList())), 200));
        router.put("/v1/roles/:name").handler(ctx -> withBody(ctx, body -> respond(ctx, identity.putRole(principal(ctx), tenant(ctx),
                ctx.pathParam("name"), Set.copyOf(strings(body, "scopes")), body.getString("description")).map(Access.Role::toJson), 200)));
        router.delete("/v1/roles/:name").handler(ctx -> done(ctx, identity.deleteRole(principal(ctx), tenant(ctx), ctx.pathParam("name"))));

        router.get("/v1/groups").handler(ctx -> respond(ctx, identity.groups(tenant(ctx)).map(all -> list("groups", all)), 200));
        router.put("/v1/groups/:name").handler(ctx -> withBody(ctx, body -> respond(ctx, identity.putGroup(principal(ctx), tenant(ctx),
                ctx.pathParam("name"), strings(body, "roles"), body.getString("description")).map(Access.Group::toJson), 200)));
        router.delete("/v1/groups/:name").handler(ctx -> done(ctx, identity.deleteGroup(principal(ctx), tenant(ctx), ctx.pathParam("name"))));

        router.get("/v1/invites").handler(ctx -> respond(ctx, identity.invites(tenant(ctx))
                .map(all -> list("invites", all.stream().map(Invite::toJson).toList())), 200));
        router.post("/v1/invites").handler(ctx -> invite(ctx, tenant(ctx)));
        router.delete("/v1/invites/:id").handler(ctx -> removed(ctx, identity.deleteInvite(tenant(ctx), ctx.pathParam("id")), "No such invite"));

        // Only what happened since this tenant was created: an earlier tenant of the same name keeps its own history.
        router.get("/v1/audit").handler(ctx -> authStore.tenant(tenant(ctx)).onFailure(ctx::fail).onSuccess(found ->
                page(ctx, (before, limit) -> audit.tenant(tenant(ctx), found == null ? Long.MAX_VALUE : found.createdAt(), before, limit))));

        router.get("/v1/users").handler(ctx -> respond(ctx, identity.users().compose(users -> {
            List<Future<JsonObject>> views = users.stream().map(this::userView).toList();
            return Future.all(views).map(all -> list("users", views.stream().map(Future::result).toList()));
        }), 200));
        router.get("/v1/users/:name").handler(ctx -> respond(ctx, identity.user(ctx.pathParam("name")).compose(user -> user == null
                ? Future.failedFuture(new AuthFailure(404, "not_found", "No user named " + ctx.pathParam("name")))
                : userView(user)), 200));
        router.patch("/v1/users/:name").handler(ctx -> withBody(ctx, body -> respond(ctx, identity.updateUser(ctx.pathParam("name"),
                body.getString("name"), body.getBoolean("disabled"), body.getBoolean("operator")).map(User::toJson), 200)));
        router.delete("/v1/users/:name").handler(ctx -> respond(ctx, identity.deleteUser(ctx.pathParam("name"))
                .map(tokens -> left(ctx.pathParam("name"), tokens)), 200));
        router.get("/v1/admin/audit/verify").handler(ctx -> respond(ctx, cluster93.core.audit.AuditVerifier.verify(audit, secretsKey), 200));
        router.get("/v1/admin/invites").handler(ctx -> respond(ctx, identity.invites(Identity.OPERATORS)
                .map(all -> list("invites", all.stream().map(Invite::toJson).toList())), 200));
        router.post("/v1/admin/invites").handler(ctx -> invite(ctx, Identity.OPERATORS));
        router.delete("/v1/admin/invites/:id").handler(ctx -> removed(ctx, identity.deleteInvite(Identity.OPERATORS, ctx.pathParam("id")),
                "No such invite"));
        router.get("/v1/admin/audit").handler(ctx -> page(ctx, audit::operators));
        router.get("/v1/tenants/:tenant/members").handler(ctx -> withTenant(ctx, tenant ->
                respond(ctx, identity.members(tenant).map(all -> list("members", all)), 200)));
        router.post("/v1/tenants/:tenant/invites").handler(ctx -> withTenant(ctx, tenant -> invite(ctx, tenant)));
    }

    /** Public: anyone holding a code. With a personal token, the invite goes to that user; without, a new user is made. */
    private void redeem(RoutingContext ctx) {
        withBody(ctx, body -> {
            String authorization = ctx.request().getHeader("Authorization");
            Future<Principal> caller = authorization == null ? Future.succeededFuture() : authenticator.authenticate(authorization);
            Future<JsonObject> redeemed = caller.compose(principal -> identity.redeem(body.getString("code"), principal, body.getString("name"),
                    body.getString("displayName"), body.getString("email"), body.getString("tokenName"), body.getString("password")));
            redeemed.onSuccess(result -> {
                JsonObject token = result.getJsonObject("token");
                // Public routes aren't audited on the way in, since nobody is known yet; this records who it became.
                JsonObject user = result.getJsonObject("user");
                audit.record(AuditEntry.of(System.currentTimeMillis(), random, result.getString("tenant"), user.getString("name"),
                        user.getString("id"), token.getString("id"), result.getBoolean("operator", false), "redeemInvite", "POST", ctx.request().path(), 201,
                        ctx.request().remoteAddress() == null ? null : ctx.request().remoteAddress().hostAddress(),
                        ctx.request().getHeader("X-Forwarded-For")));
            });
            respond(ctx, redeemed, 201);
        });
    }

    /** Public: a user signs in with their username and password and is handed a personal session token (D-133). */
    private void login(RoutingContext ctx) {
        withBody(ctx, body -> {
            ctx.response().putHeader("Cache-Control", "no-store");
            respond(ctx, identity.authenticate(body.getString("username"), body.getString("password"))
                    .compose(user -> session(user, body.getString("tenant"))), 200);
        });
    }

    /**
     * A fresh personal session token for an authenticated user, scoped to one of their tenants, or, for an operator,
     * acting as the admin (the named tenant merely scopes the console's view).
     */
    private Future<JsonObject> session(User user, String requested) {
        String wanted = requested == null || requested.isBlank() ? null : requested.trim();
        if (user.operator()) {
            Principal self = Principal.operator(user.id(), user.name(), null);
            return identity.createPersonalToken(self, Identity.OPERATORS, "session", Set.of(Scopes.ALL), SESSION_DAYS)
                    .map(minted -> sessionJson(minted, user, wanted));
        }
        Future<String> target = wanted != null
                ? Future.succeededFuture(wanted)
                : identity.me(user.id()).compose(me -> {
                    JsonArray memberships = me.getJsonArray("memberships");
                    return memberships == null || memberships.isEmpty()
                            ? Future.failedFuture(AuthFailure.forbidden("You aren't a member of any tenant yet; ask an operator for an invite"))
                            : Future.succeededFuture(memberships.getJsonObject(0).getString("tenant"));
                });
        return target.compose(tenant -> {
            Principal self = new Principal(false, tenant, Set.of(Scopes.ALL), null, user.id(), user.name(), false);
            return identity.createPersonalToken(self, tenant, "session", Set.of(Scopes.ALL), SESSION_DAYS)
                    .map(minted -> sessionJson(minted, user, tenant));
        });
    }

    private static JsonObject sessionJson(Identity.Minted minted, User user, String tenant) {
        return new JsonObject().put("token", minted.secret()).put("admin", user.operator()).put("tenant", tenant)
                .put("user", user.toJson());
    }

    /** A user sets or changes their own password (D-133): {@code currentPassword} is needed only once one is set. */
    private void updateMyPassword(RoutingContext ctx) {
        withBody(ctx, body -> respond(ctx, identity.updatePassword(principal(ctx).user(),
                body.getString("currentPassword"), body.getString("newPassword"))
                .map(v -> new JsonObject().put("status", "ok")), 200));
    }

    /** A user as the API shows them, with whether they have a password configured (D-133). */
    private Future<JsonObject> userView(User user) {
        return identity.hasPassword(user.id()).map(has -> user.toJson().put("hasPassword", has));
    }

    private void createMyToken(RoutingContext ctx) {
        withBody(ctx, body -> {
            Principal principal = principal(ctx);
            String tenant = body.getBoolean("operator", false) ? Identity.OPERATORS
                    : body.getString("tenant") != null ? body.getString("tenant")
                    : principal.admin() ? Identity.OPERATORS : principal.tenant();
            Number days = body.getNumber("expiresInDays");
            List<String> scopes = body.containsKey("scopes") ? strings(body, "scopes") : List.of(cluster93.core.auth.Scopes.ALL);
            Future<Identity.Minted> minted;
            try {
                minted = identity.createPersonalToken(principal, tenant, body.getString("name"), Set.copyOf(scopes),
                        days == null ? null : days.longValue());
            } catch (AuthFailure e) {
                minted = Future.failedFuture(e);
            }
            ctx.response().putHeader("Cache-Control", "no-store");
            respond(ctx, minted.map(Identity.Minted::toJson), 201);
        });
    }

    private void invite(RoutingContext ctx, String tenant) {
        withBody(ctx, body -> {
            Number days = body.getNumber("expiresInDays");
            ctx.response().putHeader("Cache-Control", "no-store");
            respond(ctx, identity.invite(principal(ctx), tenant, strings(body, "roles"), strings(body, "groups"), body.getString("user"),
                    days == null ? null : days.intValue(), body.getString("note")).map(Identity.Created::toJson), 201);
        });
    }

    private interface Pager {
        Future<List<AuditEntry>> page(String before, int limit);
    }

    /** Newest first; {@code next} is the cursor for the following page, or null at the end. */
    private void page(RoutingContext ctx, Pager pager) {
        int limit;
        try {
            String raw = ctx.queryParams().get("limit");
            limit = raw == null ? DEFAULT_PAGE : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > AuditLog.MAX_PAGE) {
            error(ctx, 400, "invalid_request", "\"limit\" must be between 1 and " + AuditLog.MAX_PAGE);
            return;
        }
        int size = limit;
        respond(ctx, pager.page(ctx.queryParams().get("before"), limit).map(entries -> new JsonObject()
                .put("entries", new JsonArray(entries.stream().map(AuditEntry::toJson).toList()))
                .put("next", entries.size() == size ? entries.getLast().id() : null)), 200);
    }

    private void withTenant(RoutingContext ctx, Consumer<String> then) {
        authStore.tenant(ctx.pathParam("tenant")).onFailure(ctx::fail).onSuccess(tenant -> {
            if (tenant == null) {
                error(ctx, 404, "not_found", "No such tenant");
            } else {
                then.accept(tenant.name());
            }
        });
    }

    /**
     * Who was removed, and the service tokens they made: those keep working, since they belong to the tenant, so the
     * answer lists them for the caller to keep or revoke (D-070).
     */
    private static JsonObject left(String user, List<JsonObject> serviceTokens) {
        return new JsonObject().put("user", user).put("serviceTokens", new JsonArray(serviceTokens));
    }

    private static Principal principal(RoutingContext ctx) {
        return ctx.get("principal");
    }

    private static String tenant(RoutingContext ctx) {
        return ctx.get("tenant");
    }

    private static JsonObject list(String field, List<JsonObject> items) {
        return new JsonObject().put(field, new JsonArray(items));
    }

    /** A list of strings from the body, or empty; the contract has already checked the types. */
    private static List<String> strings(JsonObject body, String field) {
        JsonArray array = body.getJsonArray(field);
        return array == null ? List.of() : array.stream().map(String::valueOf).toList();
    }

    private static void withBody(RoutingContext ctx, Consumer<JsonObject> then) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        if (body == null) {
            error(ctx, 400, "invalid_json", "The request body must be a JSON object");
            return;
        }
        try {
            then.accept(body);
        } catch (AuthFailure e) {
            error(ctx, e.status(), e.code(), e.getMessage());
        }
    }

    private static void respond(RoutingContext ctx, Future<JsonObject> result, int status) {
        result.onSuccess(json -> {
            if (!ctx.response().ended()) {
                ctx.response().setStatusCode(status);
                ctx.json(json);
            }
        }).onFailure(err -> fail(ctx, err));
    }

    private static void done(RoutingContext ctx, Future<Void> result) {
        result.onSuccess(v -> ctx.response().setStatusCode(204).end()).onFailure(err -> fail(ctx, err));
    }

    private static void removed(RoutingContext ctx, Future<Boolean> result, String missing) {
        result.onSuccess(found -> {
            if (found) {
                ctx.response().setStatusCode(204).end();
            } else {
                error(ctx, 404, "not_found", missing);
            }
        }).onFailure(err -> fail(ctx, err));
    }

    private static void fail(RoutingContext ctx, Throwable err) {
        if (ctx.response().ended()) {
            return;
        }
        if (err instanceof AuthFailure failure) {
            error(ctx, failure.status(), failure.code(), failure.getMessage());
        } else {
            ctx.fail(err);
        }
    }
}
