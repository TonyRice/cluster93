package cluster93.core.host;

import cluster93.core.runtime.HostException;
import cluster93.core.runtime.HttpExchange;
import cluster93.core.runtime.api.HttpApi;
import cluster93.core.runtime.api.Registration;
import io.vertx.core.Future;

import java.util.function.Consumer;

/**
 * {@code ctx.serve} and {@code listen(port)}: registers the service's servers with the node's {@link ServeRegistry}, where
 * requests the ingress routes to this run find them (D-112, D-123).
 */
final class NodeHttpApi implements HttpApi {

    private final HostScope scope;
    private final ServeRegistry serve;

    NodeHttpApi(HostScope scope, ServeRegistry serve) {
        this.scope = scope;
        this.serve = serve;
    }

    @Override
    public Future<Registration> serve(Consumer<HttpExchange> handler) {
        if (!scope.service()) {
            return Future.failedFuture(new HostException("Error", "not_a_service", "Only services can serve HTTP"));
        }
        if (!serve.register(scope.jobId(), handler)) {
            return Future.failedFuture(new HostException("Error", "already_serving", "ctx.serve was already called in this run"));
        }
        return Future.succeededFuture(scope.track(() -> {
            serve.unregister(scope.jobId(), handler);
            return Future.succeededFuture();
        }));
    }

    @Override
    public Future<Registration> listen(int port, Consumer<HttpExchange> handler) {
        if (!scope.service()) {
            return Future.failedFuture(new HostException("Error", "not_a_service",
                    "Only services can listen: their servers are reached by hostname through the ingress, and a job has none"));
        }
        if (!serve.register(scope.jobId(), port, handler)) {
            return Future.failedFuture(new HostException("Error", "EADDRINUSE", "address already in use"));
        }
        return Future.succeededFuture(scope.track(() -> {
            serve.unregister(scope.jobId(), port, handler);
            return Future.succeededFuture();
        }));
    }
}
