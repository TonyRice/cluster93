package automately.server.api;

import automately.core.auth.Principal;
import automately.core.secrets.TenantSecrets;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import static automately.server.api.ApiVerticle.error;

/**
 * {@code /v1/secrets}: a tenant's named secrets (D-068). Values go in and never come out: the API lists names and
 * versions, and only scripts allowed to ({@code secrets:use}) read values, with {@code secrets.get}.
 */
final class SecretsRoutes {

    private final TenantSecrets secrets;

    SecretsRoutes(TenantSecrets secrets) {
        this.secrets = secrets;
    }

    void mount(Router router) {
        router.get("/v1/secrets").handler(this::list);
        router.get("/v1/secrets/:name").handler(this::get);
        router.put("/v1/secrets/:name").handler(this::put);
        router.delete("/v1/secrets/:name").handler(this::delete);
    }

    private void list(RoutingContext ctx) {
        secrets.list(ctx.get("tenant"))
                .onSuccess(all -> ctx.json(new JsonObject().put("secrets", new JsonArray(all.stream().map(TenantSecrets.Info::toJson).toList()))))
                .onFailure(err -> fail(ctx, err));
    }

    private void get(RoutingContext ctx) {
        String name = ctx.pathParam("name");
        secrets.info(ctx.get("tenant"), name)
                .onSuccess(info -> {
                    if (info == null) {
                        notFound(ctx, name);
                    } else {
                        ctx.json(info.toJson());
                    }
                })
                .onFailure(err -> fail(ctx, err));
    }

    /** Body: {@code {value}}. Creates or replaces the secret; answers with its details, not its value. */
    private void put(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null || !(body.getValue("value") instanceof String value)) {
            error(ctx, 400, "invalid_request", "The body must be an object with a string \"value\"");
            return;
        }
        Principal principal = ctx.get("principal");
        secrets.put(ctx.get("tenant"), ctx.pathParam("name"), value, principal.tokenId())
                .onSuccess(info -> ctx.response().setStatusCode(info.version() == 1 ? 201 : 200).putHeader("Content-Type", "application/json")
                        .end(info.toJson().encode()))
                .onFailure(err -> fail(ctx, err));
    }

    private void delete(RoutingContext ctx) {
        String name = ctx.pathParam("name");
        secrets.delete(ctx.get("tenant"), name)
                .onSuccess(found -> {
                    if (found) {
                        ctx.response().setStatusCode(204).end();
                    } else {
                        notFound(ctx, name);
                    }
                })
                .onFailure(err -> fail(ctx, err));
    }

    private static void notFound(RoutingContext ctx, String name) {
        error(ctx, 404, "not_found", "No secret named \"" + name + "\"");
    }

    private static void fail(RoutingContext ctx, Throwable err) {
        if (ctx.response().ended()) {
            return;
        }
        if (err instanceof TenantSecrets.Unavailable) {
            error(ctx, 409, "secrets_unavailable", err.getMessage());
        } else if (err instanceof IllegalArgumentException) {
            error(ctx, 400, "invalid_request", err.getMessage());
        } else if (err instanceof IllegalStateException && err.getMessage() != null && err.getMessage().contains("at most")) {
            error(ctx, 409, "limit_exceeded", err.getMessage());
        } else {
            ctx.fail(err);
        }
    }
}
