package automately.core.host;

import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.api.HttpApi;
import automately.core.runtime.api.Registration;
import io.vertx.core.Future;

import java.util.function.Consumer;

/** {@code ctx.serve}: registers the service with the node's {@link ServeRegistry}, where the ingress finds it. */
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
}
