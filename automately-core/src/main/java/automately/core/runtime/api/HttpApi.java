package automately.core.runtime.api;

import automately.core.runtime.HttpExchange;
import io.vertx.core.Future;

import java.util.function.Consumer;

/** Serving HTTP from a service ({@code ctx.serve}). */
public interface HttpApi {

    /** Routes the service's requests to {@code handler}, each as a streaming exchange. Services only, once per run. */
    Future<Registration> serve(Consumer<HttpExchange> handler);
}
