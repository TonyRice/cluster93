package cluster93.core.runtime.api;

import cluster93.core.runtime.FetchedResponse;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/** Outbound HTTP, through the egress gate (D-026, D-108). */
public interface FetchApi {

    /** Fetches whose bodies are still open, per execution. */
    int MAX_CONCURRENT = 16;

    /**
     * @param request {@code {url, method, headers, body, timeoutMs}}
     * @return the status and headers as meta, and the body as a paused stream
     */
    default Future<FetchedResponse> fetch(JsonObject request) {
        return fetch(request, null);
    }

    /** As {@link #fetch(JsonObject)}, with the body as bytes (in place of {@code request.body}) when not null. */
    Future<FetchedResponse> fetch(JsonObject request, byte[] body);

    /**
     * Opens a request whose body is written in chunks ({@code node:http}, D-109). It counts against
     * {@link #MAX_CONCURRENT} with fetches, until its response body is done or it's aborted.
     *
     * @param request {@code {url, method, headers, chunked, timeoutMs, tls}}
     */
    default Future<cluster93.core.runtime.OutgoingRequest> open(JsonObject request) {
        return Future.failedFuture(new cluster93.core.runtime.HostException("Error", "unavailable", "HTTP requests aren't available here"));
    }
}
