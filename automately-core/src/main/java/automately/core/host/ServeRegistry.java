package automately.core.host;

import automately.core.runtime.HttpExchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** On one node: which running jobs have called {@code ctx.serve}, and where their requests go. */
public final class ServeRegistry {

    private final Map<String, Consumer<HttpExchange>> handlers = new ConcurrentHashMap<>();

    /** Returns false if the job already serves. */
    boolean register(String jobId, Consumer<HttpExchange> handler) {
        return handlers.putIfAbsent(jobId, handler) == null;
    }

    void unregister(String jobId, Consumer<HttpExchange> handler) {
        handlers.remove(jobId, handler);
    }

    /** The job's request handler, or null if it isn't serving. */
    public Consumer<HttpExchange> handler(String jobId) {
        return handlers.get(jobId);
    }
}
