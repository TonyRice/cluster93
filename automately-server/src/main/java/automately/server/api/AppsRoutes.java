package automately.server.api;

import automately.core.apps.AppDeployment;
import automately.core.apps.AppManifest;
import automately.core.apps.AppRecord;
import automately.core.apps.AppStore;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.UUID;
import java.util.regex.Pattern;

import static automately.server.api.ApiVerticle.error;

/**
 * {@code /v1/apps}: list and delete apps, register immutable deployments, promote and roll back, and inspect a
 * deployment (docs/design/apps-and-console.md). The ingress serves what is promoted; this is how it is managed.
 */
final class AppsRoutes {

    /** An app name is a host label, so no {@code --} (the app/tenant separator). */
    private static final Pattern APP_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?");
    private static final int MAX_DEPLOYMENT_ID = 128;

    private final AppStore apps;

    AppsRoutes(AppStore apps) {
        this.apps = apps;
    }

    void mount(Router router) {
        router.get("/v1/apps").handler(this::list);
        router.get("/v1/apps/:app/deployments").handler(this::listDeployments);
        router.post("/v1/apps/:app/deployments").handler(this::createDeployment);
        router.get("/v1/apps/:app/deployments/:id").handler(this::getDeployment);
        router.post("/v1/apps/:app/promote").handler(this::promote);
        router.post("/v1/apps/:app/rollback").handler(this::rollback);
        router.get("/v1/apps/:app").handler(this::get);
        router.delete("/v1/apps/:app").handler(this::delete);
    }

    /** The app named in the path, or null once a 400 has been answered. */
    private static String app(RoutingContext ctx) {
        String name = ctx.pathParam("app");
        if (name == null || !APP_NAME.matcher(name).matches() || name.contains("--")) {
            error(ctx, 400, "invalid_request", "An app name must be lowercase letters, digits and inner hyphens, without \"--\"");
            return null;
        }
        return name;
    }

    private void list(RoutingContext ctx) {
        apps.listApps(ctx.get("tenant"))
                .onSuccess(all -> ctx.json(new JsonObject().put("apps", new JsonArray(all.stream().map(AppRecord::toJson).toList()))))
                .onFailure(ctx::fail);
    }

    private void get(RoutingContext ctx) {
        String app = app(ctx);
        if (app == null) {
            return;
        }
        apps.getApp(ctx.get("tenant"), app).onSuccess(record -> {
            if (record == null) {
                error(ctx, 404, "not_found", "No such app");
            } else {
                ctx.json(record.toJson());
            }
        }).onFailure(ctx::fail);
    }

    private void delete(RoutingContext ctx) {
        String app = app(ctx);
        if (app == null) {
            return;
        }
        apps.deleteApp(ctx.get("tenant"), app).onSuccess(deleted -> {
            if (deleted) {
                ctx.response().setStatusCode(204).end();
            } else {
                error(ctx, 404, "not_found", "No such app");
            }
        }).onFailure(ctx::fail);
    }

    private void listDeployments(RoutingContext ctx) {
        String app = app(ctx);
        if (app == null) {
            return;
        }
        apps.listDeployments(ctx.get("tenant"), app)
                .onSuccess(all -> ctx.json(new JsonObject().put("deployments",
                        new JsonArray(all.stream().map(AppDeployment::toJson).toList()))))
                .onFailure(ctx::fail);
    }

    private void getDeployment(RoutingContext ctx) {
        String app = app(ctx);
        if (app == null) {
            return;
        }
        apps.getDeployment(ctx.get("tenant"), app, ctx.pathParam("id")).onSuccess(deployment -> {
            if (deployment == null) {
                error(ctx, 404, "not_found", "No such deployment");
            } else {
                ctx.json(deployment.toJson());
            }
        }).onFailure(ctx::fail);
    }

    private void createDeployment(RoutingContext ctx) {
        String app = app(ctx);
        if (app == null) {
            return;
        }
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
        JsonObject manifestJson = body.getJsonObject("manifest");
        if (manifestJson == null) {
            error(ctx, 400, "invalid_request", "\"manifest\" must be an object (the app's automately.json)");
            return;
        }
        AppManifest manifest;
        try {
            manifest = AppManifest.parse(manifestJson);
        } catch (IllegalArgumentException e) {
            error(ctx, 400, "invalid_request", e.getMessage());
            return;
        }
        if (!manifest.name().equals(app)) {
            error(ctx, 400, "invalid_request", "The manifest's name (\"" + manifest.name() + "\") must match the app (" + app + ")");
            return;
        }
        String deploymentId = body.getString("deploymentId");
        if (deploymentId == null || deploymentId.isBlank()) {
            deploymentId = "dpl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        } else {
            deploymentId = deploymentId.trim();
            if (deploymentId.length() > MAX_DEPLOYMENT_ID) {
                error(ctx, 400, "invalid_request", "\"deploymentId\" is limited to " + MAX_DEPLOYMENT_ID + " characters");
                return;
            }
        }
        Object revision = body.getValue("serviceRevision");
        if (revision != null && !(revision instanceof Number)) {
            error(ctx, 400, "invalid_request", "\"serviceRevision\" must be a number");
            return;
        }
        Integer serviceRevision = revision == null ? null : ((Number) revision).intValue();
        String id = deploymentId;
        AppDeployment deployment = new AppDeployment(ctx.get("tenant"), app, id, System.currentTimeMillis(),
                body.getString("commitHash"), manifest, body.getString("vfsPrefix"), serviceRevision);
        apps.saveDeployment(deployment).onSuccess(saved -> ctx.response().setStatusCode(201)
                        .putHeader("Location", "/v1/apps/" + app + "/deployments/" + id)
                        .putHeader("Content-Type", "application/json").end(saved.toJson().encode()))
                .onFailure(err -> {
                    if (err instanceof IllegalStateException) {
                        error(ctx, 409, "conflict", err.getMessage());
                    } else {
                        ctx.fail(err);
                    }
                });
    }

    private void promote(RoutingContext ctx) {
        String app = app(ctx);
        if (app == null) {
            return;
        }
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        String deploymentId = body == null ? null : body.getString("deploymentId");
        if (deploymentId == null || deploymentId.isBlank()) {
            error(ctx, 400, "invalid_request", "\"deploymentId\" is required");
            return;
        }
        apps.promote(ctx.get("tenant"), app, deploymentId.trim()).onSuccess(record -> ctx.json(record.toJson()))
                .onFailure(err -> {
                    if (err instanceof IllegalArgumentException) {
                        error(ctx, 404, "not_found", err.getMessage());
                    } else if (err instanceof IllegalStateException) {
                        error(ctx, 409, "conflict", err.getMessage());
                    } else {
                        ctx.fail(err);
                    }
                });
    }

    private void rollback(RoutingContext ctx) {
        String app = app(ctx);
        if (app == null) {
            return;
        }
        apps.rollback(ctx.get("tenant"), app).onSuccess(record -> ctx.json(record.toJson()))
                .onFailure(err -> {
                    if (err instanceof IllegalStateException) {
                        error(ctx, 409, "conflict", err.getMessage());
                    } else {
                        ctx.fail(err);
                    }
                });
    }
}
