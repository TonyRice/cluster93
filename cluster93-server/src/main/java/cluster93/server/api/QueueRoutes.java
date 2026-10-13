package cluster93.server.api;

import cluster93.core.queue.QueueService;
import cluster93.core.runtime.HostException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import static cluster93.server.api.ApiVerticle.error;

/** {@code /v1/queues/{queue}}: push to a tenant's durable queues and look after their dead letters from outside (D-061). */
final class QueueRoutes {

    private final QueueService queues;

    QueueRoutes(QueueService queues) {
        this.queues = queues;
    }

    void mount(Router router) {
        router.post("/v1/queues/:queue").handler(this::push);
        router.get("/v1/queues/:queue").handler(this::stats);
        router.get("/v1/queues/:queue/dead").handler(this::dead);
        router.post("/v1/queues/:queue/dead/:id/retry").handler(this::retry);
        router.delete("/v1/queues/:queue/dead/:id").handler(this::discard);
    }

    /** Body: {@code {message, delayMs?, key?, maxAttempts?}}. */
    private void push(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null || !body.containsKey("message")) {
            error(ctx, 400, "invalid_request", "The body must be an object with a \"message\"");
            return;
        }
        Long delay = body.getValue("delayMs") instanceof Number n ? n.longValue() : null;
        Integer attempts = body.getValue("maxAttempts") instanceof Number n ? n.intValue() : null;
        queues.push(ctx.get("tenant"), ctx.pathParam("queue"), body.getValue("message"), delay, body.getString("key"), attempts)
                .onSuccess(id -> ctx.response().setStatusCode(202).putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("id", id).put("queue", ctx.pathParam("queue")).encode()))
                .onFailure(err -> FilesRoutes.fail(ctx, err));
    }

    private void stats(RoutingContext ctx) {
        String queue = ctx.pathParam("queue");
        queues.stats(ctx.get("tenant"), queue)
                .onSuccess(counts -> ctx.json(counts.copy().put("queue", queue)))
                .onFailure(err -> FilesRoutes.fail(ctx, err));
    }

    private void dead(RoutingContext ctx) {
        int limit;
        try {
            limit = Integer.parseInt(ctx.request().getParam("limit", "100"));
        } catch (NumberFormatException e) {
            error(ctx, 400, "invalid_request", "limit must be a number");
            return;
        }
        queues.dead(ctx.get("tenant"), ctx.pathParam("queue"), limit, ctx.request().getParam("cursor"))
                .onSuccess(ctx::json)
                .onFailure(err -> FilesRoutes.fail(ctx, err));
    }

    private void retry(RoutingContext ctx) {
        guarded(ctx, () -> queues.retry(ctx.get("tenant"), QueueService.name(ctx.pathParam("queue")), ctx.pathParam("id"))
                .onSuccess(found -> answer(ctx, found, ctx.pathParam("id")))
                .onFailure(err -> FilesRoutes.fail(ctx, err)));
    }

    private void discard(RoutingContext ctx) {
        guarded(ctx, () -> queues.discard(ctx.get("tenant"), QueueService.name(ctx.pathParam("queue")), ctx.pathParam("id"))
                .onSuccess(found -> {
                    if (found) {
                        ctx.response().setStatusCode(204).end();
                    } else {
                        notFound(ctx, ctx.pathParam("id"));
                    }
                })
                .onFailure(err -> FilesRoutes.fail(ctx, err)));
    }

    private static void answer(RoutingContext ctx, boolean found, String id) {
        if (found) {
            ctx.json(new JsonObject().put("id", id).put("status", "READY"));
        } else {
            notFound(ctx, id);
        }
    }

    private static void notFound(RoutingContext ctx, String id) {
        error(ctx, 404, "not_found", "No dead-lettered message " + id);
    }

    private static void guarded(RoutingContext ctx, Runnable call) {
        try {
            call.run();
        } catch (HostException e) {
            FilesRoutes.fail(ctx, e);
        }
    }
}
