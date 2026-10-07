package automately.server.api;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import static automately.server.api.ApiVerticle.error;

/** {@code /v1/bus/{topic}}: publish to, or call, a tenant's topics from outside (docs/scripting/host-apis.md). */
final class BusRoutes {

    static final String TOPIC_PATTERN = "[a-zA-Z0-9._-]{1,128}";
    static final int MAX_MESSAGE_BYTES = 256 * 1024;
    static final long MAX_TIMEOUT_MS = 30_000;

    private final Vertx vertx;

    BusRoutes(Vertx vertx) {
        this.vertx = vertx;
    }

    void mount(Router router) {
        router.post("/v1/bus/:topic").handler(this::publish);
        router.post("/v1/bus/:topic/request").handler(this::request);
    }

    private void publish(RoutingContext ctx) {
        String topic = ctx.pathParam("topic");
        Object message = message(ctx, topic);
        if (message == INVALID) {
            return;
        }
        vertx.eventBus().publish(automately.core.host.Addresses.topic(ctx.get("tenant"), topic),
                new JsonObject().put("topic", topic).put("message", message));
        ctx.response().setStatusCode(202).end();
    }

    private void request(RoutingContext ctx) {
        String topic = ctx.pathParam("topic");
        Object message = message(ctx, topic);
        if (message == INVALID) {
            return;
        }
        long timeout;
        try {
            timeout = Math.min(Math.max(1, Long.parseLong(ctx.request().getParam("timeoutMs", "10000"))), MAX_TIMEOUT_MS);
        } catch (NumberFormatException e) {
            error(ctx, 400, "invalid_request", "timeoutMs must be a number");
            return;
        }
        vertx.eventBus().<JsonObject>request(automately.core.host.Addresses.handler(ctx.get("tenant"), topic),
                        new JsonObject().put("topic", topic).put("message", message), new DeliveryOptions().setSendTimeout(timeout))
                .onSuccess(reply -> ctx.json(new JsonObject().put("value", reply.body().getValue("value"))))
                .onFailure(err -> {
                    if (err instanceof ReplyException reply && reply.failureType() == ReplyFailure.NO_HANDLERS) {
                        error(ctx, 404, "no_handler", "Nothing handles topic \"" + topic + "\"");
                    } else if (err instanceof ReplyException reply && reply.failureType() == ReplyFailure.TIMEOUT) {
                        error(ctx, 504, "timeout", "No reply on topic \"" + topic + "\" in time");
                    } else {
                        error(ctx, 502, "handler_failed", err.getMessage());
                    }
                });
    }

    private static final Object INVALID = new Object();

    private Object message(RoutingContext ctx, String topic) {
        if (!topic.matches(TOPIC_PATTERN)) {
            error(ctx, 400, "invalid_request", "A topic must match " + TOPIC_PATTERN);
            return INVALID;
        }
        String body = ctx.body().asString();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.decodeValue(body);
        } catch (RuntimeException e) {
            error(ctx, 400, "invalid_json", "The body must be JSON");
            return INVALID;
        }
    }
}
