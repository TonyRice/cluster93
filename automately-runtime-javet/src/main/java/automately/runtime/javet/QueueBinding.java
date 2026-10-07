package automately.runtime.javet;

import automately.core.runtime.api.HostArgs;
import automately.core.runtime.api.QueueApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;
import io.vertx.core.json.JsonObject;

/** {@code bus.queue(name)} in JavaScript (D-061); {@code bus.js} keeps the consume handlers and passes their ids. */
public final class QueueBinding {

    private final HostBridge bridge;
    private final JsValues js;
    private final QueueApi queues;

    QueueBinding(HostBridge bridge, QueueApi queues) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.queues = queues;
    }

    /** {@code options}: {@code {delayMs, key, maxAttempts}}. Resolves with the message's id. */
    @V8Function(name = "push")
    public V8ValuePromise push(V8Value queue, V8Value message, V8Value options) throws JavetException {
        return bridge.promise(() -> {
            Object body = js.toJava(message);
            JsonObject o = js.options(options);
            Object delay = o.getValue("delayMs");
            Object attempts = o.getValue("maxAttempts");
            return queues.push(js.string(queue), body, delay == null ? null : HostArgs.integer(delay, "delayMs"),
                    o.getValue("key") == null ? null : String.valueOf(o.getValue("key")),
                    attempts == null ? null : HostArgs.intBetween(attempts, "maxAttempts", 1, Integer.MAX_VALUE));
        });
    }

    /** {@code options}: {@code {concurrency = 1}}. Messages arrive at listener {@code listenerId}; its answer acknowledges them. */
    @V8Function(name = "consume")
    public V8ValuePromise consume(V8Value queue, int listenerId, V8Value options) throws JavetException {
        String name = js.string(queue);
        Object concurrency = js.options(options).getValue("concurrency", 1);
        return bridge.listen(listenerId, listener -> queues.consume(name, HostArgs.intBetween(concurrency, "concurrency", 1, QueueApi.MAX_CONCURRENCY),
                listener));
    }

    @V8Function(name = "stats")
    public V8ValuePromise stats(V8Value queue) throws JavetException {
        return bridge.promise(() -> queues.stats(js.string(queue)));
    }

    /** {@code options}: {@code {limit = 100, cursor}}. */
    @V8Function(name = "dead")
    public V8ValuePromise dead(V8Value queue, V8Value options) throws JavetException {
        return bridge.promise(() -> {
            JsonObject o = js.options(options);
            return queues.dead(js.string(queue), HostArgs.intBetween(o.getValue("limit", 100), "limit", 1, 1_000), HostArgs.string(o.getValue("cursor")));
        });
    }

    @V8Function(name = "retry")
    public V8ValuePromise retry(V8Value queue, V8Value id) throws JavetException {
        return bridge.promise(() -> queues.retry(js.string(queue), js.string(id)));
    }

    @V8Function(name = "discard")
    public V8ValuePromise discard(V8Value queue, V8Value id) throws JavetException {
        return bridge.promise(() -> queues.discard(js.string(queue), js.string(id)));
    }
}
