package automately.core.host;

import automately.core.runtime.HostException;
import automately.core.runtime.Inbound;
import automately.core.runtime.api.BusApi;
import automately.core.runtime.api.HostArgs;
import automately.core.runtime.api.Registration;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonObject;

import java.util.function.Consumer;

import static automately.core.host.Names.guarded;

/** {@code bus} on the Vert.x event bus, on addresses scoped to the tenant ({@link Addresses}). */
final class NodeBusApi implements BusApi {

    private final HostScope scope;

    NodeBusApi(HostScope scope) {
        this.scope = scope;
    }

    @Override
    public Future<Void> publish(String topic, Object message) {
        return guarded(() -> {
            String t = topic(topic);
            scope.vertx().eventBus().publish(Addresses.topic(scope.tenant(), t), envelope(t, HostArgs.json(message, MAX_MESSAGE_BYTES)));
            return Future.succeededFuture();
        });
    }

    @Override
    public Future<Object> request(String topic, Object message, long timeoutMs) {
        return guarded(() -> {
            String t = topic(topic);
            long timeout = Math.min(Math.max(1, timeoutMs), MAX_REQUEST_TIMEOUT_MS);
            return scope.vertx().eventBus().<JsonObject>request(Addresses.handler(scope.tenant(), t),
                            envelope(t, HostArgs.json(message, MAX_MESSAGE_BYTES)), new DeliveryOptions().setSendTimeout(timeout))
                    .map(reply -> reply.body().getValue("value"))
                    .recover(err -> Future.failedFuture(replyError(t, err)));
        });
    }

    @Override
    public Future<Registration> subscribe(String topic, Consumer<Inbound> listener) {
        return guarded(() -> consume(Addresses.topic(scope.tenant(), topic(topic)), false, listener));
    }

    @Override
    public Future<Registration> handle(String topic, Consumer<Inbound> listener) {
        return guarded(() -> consume(Addresses.handler(scope.tenant(), topic(topic)), true, listener));
    }

    static JsonObject envelope(String topic, Object message) {
        return new JsonObject().put("topic", topic).put("message", message);
    }

    static HostException replyError(String topic, Throwable err) {
        if (err instanceof ReplyException reply) {
            return switch (reply.failureType()) {
                case NO_HANDLERS -> new HostException("Error", "no_handler", "Nothing handles topic \"" + topic + "\"");
                case TIMEOUT -> new HostException("TimeoutError", "timeout", "No reply on topic \"" + topic + "\" in time");
                default -> new HostException("Error", "handler_failed", reply.getMessage());
            };
        }
        return new HostException("Error", "bus_error", String.valueOf(err.getMessage()));
    }

    private static String topic(String topic) {
        if (topic == null || !topic.matches(TOPIC_PATTERN)) {
            throw HostException.invalid("A topic must match " + TOPIC_PATTERN);
        }
        return topic;
    }

    private Future<Registration> consume(String address, boolean replies, Consumer<Inbound> listener) {
        MessageConsumer<JsonObject> consumer = scope.vertx().eventBus().consumer(address, message -> listener.accept(inbound(message, replies)));
        Registration registration = scope.track(consumer::unregister);
        return consumer.completion().map(registration);
    }

    private static Inbound inbound(Message<JsonObject> message, boolean replies) {
        return new Inbound() {
            @Override
            public Object payload() {
                return message.body();
            }

            @Override
            public boolean expectsReply() {
                return replies;
            }

            @Override
            public void reply(Object value) {
                message.reply(new JsonObject().put("value", value));
            }

            @Override
            public void fail(int code, String reason) {
                message.fail(code, reason);
            }
        };
    }
}
