package automately.core.runtime;

import io.vertx.core.json.JsonObject;

/** A fetch response: {@code status, statusText, headers, url} and its body as a stream. */
public record FetchedResponse(JsonObject meta, BodyStream body) {
}
