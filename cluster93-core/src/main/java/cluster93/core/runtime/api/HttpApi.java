package cluster93.core.runtime.api;

import cluster93.core.runtime.HttpExchange;
import io.vertx.core.Future;

import java.util.function.Consumer;

/** Serving HTTP from a service ({@code ctx.serve}). */
public interface HttpApi {

    /** Routes the service's requests to {@code handler}, each as a streaming exchange. Services only, once per run. */
    Future<Registration> serve(Consumer<HttpExchange> handler);

    /**
     * A server on {@code port} (D-112, D-123): nothing opens on the node; the ingress routes the port's host to it. Services
     * only. Fails with {@code EADDRINUSE} if the run already listens there.
     */
    default Future<Registration> listen(int port, Consumer<HttpExchange> handler) {
        return Future.failedFuture(new cluster93.core.runtime.HostException("Error", "not_a_service", "Only services can listen"));
    }
}
