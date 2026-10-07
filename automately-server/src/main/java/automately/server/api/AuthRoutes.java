package automately.server.api;

import automately.core.auth.ApiToken;
import automately.core.auth.AuthFailure;
import automately.core.auth.AuthStore;
import automately.core.auth.Authenticator;
import automately.core.auth.Principal;
import automately.core.auth.Scopes;
import automately.core.auth.Tenant;
import automately.core.auth.TokenFormat;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Function;

import static automately.server.api.ApiVerticle.error;

/** Tenants and tokens (D-044, D-045): the admin manages tenants and anyone's tokens; a tenant manages its own. */
final class AuthRoutes {

    static final int MAX_TOKEN_DAYS = 3650;

    private final AuthStore store;
    private final Authenticator authenticator;
    /** Whether a tenant has nothing left (no services, no files, no kv), so it can be deleted. */
    private final Function<String, Future<Boolean>> isEmpty;
    /** Removes what else a deleted tenant had (its storage policy, providers and routes). */
    private final Function<String, Future<Void>> forget;
    private final SecureRandom random = new SecureRandom();

    AuthRoutes(AuthStore store, Authenticator authenticator, Function<String, Future<Boolean>> isEmpty, Function<String, Future<Void>> forget) {
        this.forget = forget;
        this.store = store;
        this.authenticator = authenticator;
        this.isEmpty = isEmpty;
    }

    void mount(Router router) {
        router.get("/v1/whoami").handler(this::whoami);
        router.post("/v1/tenants").handler(this::createTenant);
        router.get("/v1/tenants").handler(ctx -> store.tenants()
                .onSuccess(all -> ctx.json(new JsonObject().put("tenants", new JsonArray(all.stream().map(Tenant::toJson).toList()))))
                .onFailure(ctx::fail));
        router.get("/v1/tenants/:tenant").handler(ctx -> withTenant(ctx, tenant -> ctx.json(tenant.toJson())));
        router.delete("/v1/tenants/:tenant").handler(this::deleteTenant);
        router.get("/v1/tenants/:tenant/tokens").handler(ctx -> withTenant(ctx, tenant -> listTokens(ctx, tenant.name())));
        router.post("/v1/tenants/:tenant/tokens").handler(ctx -> withTenant(ctx, tenant -> createToken(ctx, tenant.name(), null)));
        router.delete("/v1/tenants/:tenant/tokens/:id").handler(ctx -> withTenant(ctx, tenant -> revoke(ctx, tenant.name())));
        router.post("/v1/admin/rotate").handler(this::rotate);
        router.get("/v1/tokens").handler(ctx -> listTokens(ctx, ctx.get("tenant")));
        router.post("/v1/tokens").handler(ctx -> createToken(ctx, ctx.get("tenant"), ctx.<Principal>get("principal").scopes()));
        router.delete("/v1/tokens/:id").handler(ctx -> revoke(ctx, ctx.get("tenant")));
    }

    private void whoami(RoutingContext ctx) {
        Principal principal = ctx.get("principal");
        ctx.json(principal.admin()
                ? new JsonObject().put("admin", true)
                : new JsonObject().put("admin", false).put("tenant", principal.tenant()).put("token", principal.tokenId())
                        .put("scopes", new JsonArray(principal.scopes().stream().sorted().toList())));
    }

    private void createTenant(RoutingContext ctx) {
        JsonObject body = jsonBody(ctx);
        if (body == null) {
            return;
        }
        String name = body.getValue("name") instanceof String s ? s : null;
        if (!Names.isValid(name)) {
            error(ctx, 400, "invalid_request", "\"name\" must be " + Names.RULE);
            return;
        }
        Tenant tenant = new Tenant(name, System.currentTimeMillis());
        store.createTenant(tenant).onFailure(ctx::fail).onSuccess(created -> {
            if (created) {
                ctx.response().setStatusCode(201);
                ctx.json(tenant.toJson());
            } else {
                error(ctx, 409, "conflict", "Tenant " + name + " already exists");
            }
        });
    }

    private void deleteTenant(RoutingContext ctx) {
        withTenant(ctx, tenant -> isEmpty.apply(tenant.name()).onFailure(ctx::fail).onSuccess(empty -> {
            if (!empty) {
                error(ctx, 409, "conflict", "Tenant " + tenant.name() + " still has services, files or kv data; remove them first");
                return;
            }
            store.deleteTenant(tenant.name()).compose(v -> forget.apply(tenant.name()))
                    .onFailure(ctx::fail).onSuccess(v -> ctx.response().setStatusCode(204).end());
        }));
    }

    private void withTenant(RoutingContext ctx, java.util.function.Consumer<Tenant> then) {
        store.tenant(ctx.pathParam("tenant")).onFailure(ctx::fail).onSuccess(tenant -> {
            if (tenant == null) {
                error(ctx, 404, "not_found", "No such tenant");
            } else {
                then.accept(tenant);
            }
        });
    }

    private void listTokens(RoutingContext ctx, String tenant) {
        store.tokens(tenant).onFailure(ctx::fail)
                .onSuccess(all -> ctx.json(new JsonObject().put("tokens", new JsonArray(all.stream().map(ApiToken::toViewJson).toList()))));
    }

    /** {@code limit} is the minting token's scopes (a token can only mint narrower ones), or null for the admin. */
    private void createToken(RoutingContext ctx, String tenant, Set<String> limit) {
        JsonObject body = jsonBody(ctx);
        if (body == null) {
            return;
        }
        String name = body.getValue("name") instanceof String s ? s.trim() : "";
        if (name.isEmpty() || name.length() > 100) {
            error(ctx, 400, "invalid_request", "\"name\" must be 1 to 100 characters, saying what the token is for");
            return;
        }
        if (!(body.getValue("scopes") instanceof JsonArray array) || array.isEmpty() || !array.stream().allMatch(String.class::isInstance)) {
            error(ctx, 400, "invalid_request", "\"scopes\" must be a non-empty list, such as [\"jobs:read\"] or [\"*\"]; known: "
                    + Scopes.KNOWN);
            return;
        }
        Set<String> scopes = Scopes.of(array.stream().map(String.class::cast).toList());
        String unknown = Scopes.unknown(scopes);
        if (unknown != null) {
            error(ctx, 400, "invalid_request", "Unknown scope \"" + unknown + "\"; known: " + Scopes.KNOWN + " and \"*\"");
            return;
        }
        if (limit != null && !Scopes.covers(limit, scopes)) {
            error(ctx, 403, "forbidden", "A token can only create tokens with scopes it has itself");
            return;
        }
        Object days = body.getValue("expiresInDays");
        if (days != null && (!(days instanceof Number n) || n.longValue() < 1 || n.longValue() > MAX_TOKEN_DAYS)) {
            error(ctx, 400, "invalid_request", "\"expiresInDays\" must be between 1 and " + MAX_TOKEN_DAYS);
            return;
        }
        long now = System.currentTimeMillis();
        String secret = TokenFormat.generate(random);
        byte[] id = new byte[8];
        random.nextBytes(id);
        ApiToken token = new ApiToken("tok_" + HexFormat.of().formatHex(id), tenant, name, scopes, TokenFormat.hash(secret), now,
                days == null ? null : now + Duration.ofDays(((Number) days).longValue()).toMillis(), null);
        store.saveToken(token).onFailure(ctx::fail).onSuccess(v -> {
            ctx.response().setStatusCode(201).putHeader("Cache-Control", "no-store");
            // The secret is shown once, here, and never again.
            ctx.json(token.toViewJson().put("token", secret));
        });
    }

    private void revoke(RoutingContext ctx, String tenant) {
        store.revokeToken(tenant, ctx.pathParam("id")).onFailure(ctx::fail).onSuccess(revoked -> {
            if (revoked) {
                ctx.response().setStatusCode(204).end();
            } else {
                error(ctx, 404, "not_found", "No such token");
            }
        });
    }

    private void rotate(RoutingContext ctx) {
        authenticator.rotateAdminToken(random).onSuccess(token -> {
            ctx.response().putHeader("Cache-Control", "no-store");
            ctx.json(new JsonObject().put("token", token));
        }).onFailure(err -> {
            if (err instanceof AuthFailure failure) {
                error(ctx, failure.status(), failure.code(), failure.getMessage());
            } else {
                ctx.fail(err);
            }
        });
    }

    private static JsonObject jsonBody(RoutingContext ctx) {
        try {
            JsonObject body = ctx.body().asJsonObject();
            if (body != null) {
                return body;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        error(ctx, 400, "invalid_json", "The request body must be a JSON object");
        return null;
    }
}
