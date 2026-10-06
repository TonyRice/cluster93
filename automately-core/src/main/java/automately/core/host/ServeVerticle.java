package automately.core.host;

import automately.core.runtime.Inbound;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Receives HTTP requests routed to this node instance by the ingress and hands each to the serving
 * job's handler. Replies with {@code {status, headers, body}}; fails with 503 if the job isn't serving
 * and with 429 if it already has too many requests in flight.
 */
public final class ServeVerticle extends VerticleBase {

    public static final int MAX_CONCURRENT_REQUESTS = 64;

    private final String instanceId;
    private final ServeRegistry registry;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public ServeVerticle(String instanceId, ServeRegistry registry) {
        this.instanceId = instanceId;
        this.registry = registry;
    }

    @Override
    public Future<?> start() {
        return vertx.eventBus().<JsonObject>consumer(Addresses.nodeHttp(instanceId), this::onRequest).completion();
    }

    private void onRequest(Message<JsonObject> message) {
        String jobId = message.body().getString("jobId");
        Consumer<Inbound> handler = registry.handler(jobId);
        if (handler == null) {
            message.fail(503, "The service is not accepting requests right now");
            return;
        }
        AtomicInteger count = inFlight.computeIfAbsent(jobId, id -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_CONCURRENT_REQUESTS) {
            count.decrementAndGet();
            message.fail(429, "Too many requests in flight for this service");
            return;
        }
        JsonObject request = message.body().getJsonObject("request");
        handler.accept(new Inbound() {
            @Override
            public Object payload() {
                return request;
            }

            @Override
            public boolean expectsReply() {
                return true;
            }

            @Override
            public void reply(Object value) {
                count.decrementAndGet();
                message.reply(value);
            }

            @Override
            public void fail(int code, String reason) {
                count.decrementAndGet();
                message.fail(code, reason);
            }
        });
    }
}
