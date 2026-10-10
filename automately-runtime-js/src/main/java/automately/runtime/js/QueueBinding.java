package automately.runtime.js;

import automately.core.runtime.api.HostArgs;
import automately.core.runtime.api.QueueApi;
import io.vertx.core.json.JsonObject;

/** {@code bus.queue(name)} in JavaScript (D-061); {@code bus.js} keeps the consume handlers and passes their ids. */
public final class QueueBinding {

    private final HostBridge bridge;
    private final QueueApi queues;

    QueueBinding(HostBridge bridge, QueueApi queues) {
        this.bridge = bridge;
        this.queues = queues;
    }

    /** {@code options}: {@code {delayMs, key, maxAttempts}}. Resolves with the message's id. */
    @HostFunction(name = "push")
    public JsHandle push(JsArg queue, JsArg message, JsArg options) throws JsEngineException {
        return bridge.promise(() -> {
            Object body = message.json();
            JsonObject o = options.options();
            Object delay = o.getValue("delayMs");
            Object attempts = o.getValue("maxAttempts");
            return queues.push(queue.string(), body, delay == null ? null : HostArgs.integer(delay, "delayMs"),
                    o.getValue("key") == null ? null : String.valueOf(o.getValue("key")),
                    attempts == null ? null : HostArgs.intBetween(attempts, "maxAttempts", 1, Integer.MAX_VALUE));
        });
    }

    /** {@code options}: {@code {concurrency = 1}}. Messages arrive at listener {@code listenerId}; its answer acknowledges them. */
    @HostFunction(name = "consume")
    public JsHandle consume(JsArg queue, int listenerId, JsArg options) throws JsEngineException {
        String name = queue.string();
        Object concurrency = options.options().getValue("concurrency", 1);
        return bridge.listen(listenerId, listener -> queues.consume(name, HostArgs.intBetween(concurrency, "concurrency", 1, QueueApi.MAX_CONCURRENCY),
                listener));
    }

    @HostFunction(name = "stats")
    public JsHandle stats(JsArg queue) throws JsEngineException {
        return bridge.promise(() -> queues.stats(queue.string()));
    }

    /** {@code options}: {@code {limit = 100, cursor}}. */
    @HostFunction(name = "dead")
    public JsHandle dead(JsArg queue, JsArg options) throws JsEngineException {
        return bridge.promise(() -> {
            JsonObject o = options.options();
            return queues.dead(queue.string(), HostArgs.intBetween(o.getValue("limit", 100), "limit", 1, 1_000), HostArgs.string(o.getValue("cursor")));
        });
    }

    @HostFunction(name = "retry")
    public JsHandle retry(JsArg queue, JsArg id) throws JsEngineException {
        return bridge.promise(() -> queues.retry(queue.string(), id.string()));
    }

    @HostFunction(name = "discard")
    public JsHandle discard(JsArg queue, JsArg id) throws JsEngineException {
        return bridge.promise(() -> queues.discard(queue.string(), id.string()));
    }
}
