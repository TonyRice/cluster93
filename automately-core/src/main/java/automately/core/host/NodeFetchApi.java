package automately.core.host;

import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HostException;
import automately.core.runtime.api.FetchApi;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** {@code fetch} through the node's {@link FetchClient}; at most {@value #MAX_CONCURRENT} bodies open at once. */
final class NodeFetchApi implements FetchApi {

    private final FetchClient client;
    private final AtomicInteger inFlight = new AtomicInteger();

    NodeFetchApi(FetchClient client) {
        this.client = client;
    }

    @Override
    public Future<FetchedResponse> fetch(JsonObject request) {
        if (inFlight.incrementAndGet() > MAX_CONCURRENT) {
            inFlight.decrementAndGet();
            return Future.failedFuture(HostException.limit("Too many concurrent fetches (limit " + MAX_CONCURRENT + ")"));
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
            }
        };
        try {
            return client.fetch(request, release).onFailure(err -> release.run());
        } catch (HostException e) {
            release.run();
            return Future.failedFuture(e);
        }
    }
}
