package automately.core.runtime.api;

import automately.core.runtime.FetchedResponse;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/** Outbound HTTP, through the egress policy (D-026). */
public interface FetchApi {

    /** Fetches whose bodies are still open, per execution. */
    int MAX_CONCURRENT = 16;

    /**
     * @param request {@code {url, method, headers, body, timeoutMs}}
     * @return the status and headers as meta, and the body as a paused stream
     */
    Future<FetchedResponse> fetch(JsonObject request);
}
