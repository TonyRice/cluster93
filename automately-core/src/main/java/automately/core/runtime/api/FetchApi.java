package automately.core.runtime.api;

import automately.core.runtime.FetchedResponse;
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
}
