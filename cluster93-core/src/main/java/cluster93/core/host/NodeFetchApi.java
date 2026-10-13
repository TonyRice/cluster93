package cluster93.core.host;

import cluster93.core.runtime.FetchedResponse;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.api.FetchApi;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code fetch} through the node's {@link FetchClient}, as the job's tenant (so its egress policy applies, D-108); at
 * most {@value #MAX_CONCURRENT} bodies open at once.
 */
final class NodeFetchApi implements FetchApi {

    private final FetchClient client;
    private final String tenant;
    private final AtomicInteger inFlight = new AtomicInteger();

    NodeFetchApi(FetchClient client, String tenant) {
        this.client = client;
        this.tenant = tenant;
    }

    @Override
    public Future<FetchedResponse> fetch(JsonObject request, byte[] body) {
        Runnable release = take();
        if (release == null) {
            return Future.failedFuture(HostException.limit("Too many concurrent fetches (limit " + MAX_CONCURRENT + ")"));
        }
        try {
            return client.fetch(tenant, request, body == null ? null : io.vertx.core.buffer.Buffer.buffer(body), release)
                    .onFailure(err -> release.run());
        } catch (HostException e) {
            release.run();
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<cluster93.core.runtime.OutgoingRequest> open(JsonObject request) {
        Runnable release = take();
        if (release == null) {
            return Future.failedFuture(HostException.limit("Too many concurrent requests (limit " + MAX_CONCURRENT
                    + ", fetches and HTTP requests together)"));
        }
        try {
            return client.open(tenant, request, release);
        } catch (RuntimeException e) {
            release.run();
            return Future.failedFuture(e);
        }
    }

    /** One of the {@value #MAX_CONCURRENT} places, and how to give it back once; or null if none is free. HTTP/2 streams take them too. */
    Runnable take() {
        if (inFlight.incrementAndGet() > MAX_CONCURRENT) {
            inFlight.decrementAndGet();
            return null;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
            }
        };
    }
}
