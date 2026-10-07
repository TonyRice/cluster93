package automately.core.host;

import automately.core.runtime.BodyStream;
import automately.core.runtime.HttpExchange;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;

import java.util.Locale;
import java.util.Set;

/**
 * An {@link HttpExchange} over a request the ingress proxied to this node's internal endpoint. Response writes
 * complete when the socket has drained, so a script producing faster than the client reads waits instead of
 * filling memory.
 */
public final class ServerExchange implements HttpExchange {

    static final Set<String> INTERNAL_HEADERS = Set.of(InternalHttpVerticle.SECRET_HEADER.toLowerCase(Locale.ROOT),
            InternalHttpVerticle.JOB_HEADER.toLowerCase(Locale.ROOT));
    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
            "proxy-authorization", "proxy-authenticate");

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final Context context;
    private final JsonObject requestJson;
    private final BodyStream body;
    private volatile boolean closed;

    public ServerExchange(HttpServerRequest request, Context context) {
        this.request = request;
        this.response = request.response();
        this.context = context;
        this.requestJson = describe(request);
        this.body = new VertxBodyStream(request, context, () -> { });
        response.closeHandler(v -> closed = true);
    }

    private static JsonObject describe(HttpServerRequest request) {
        JsonObject headers = new JsonObject();
        request.headers().names().forEach(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!INTERNAL_HEADERS.contains(lower)) {
                headers.put(lower, String.join(", ", request.headers().getAll(name)));
            }
        });
        JsonObject query = new JsonObject();
        request.params().names().forEach(name -> query.put(name, request.params().get(name)));
        String host = request.getHeader("X-Forwarded-Host") != null ? request.getHeader("X-Forwarded-Host") : request.getHeader("Host");
        String proto = request.getHeader("X-Forwarded-Proto") != null ? request.getHeader("X-Forwarded-Proto") : "http";
        return new JsonObject()
                .put("method", request.method().name())
                .put("url", proto + "://" + host + request.uri())
                .put("path", request.path())
                .put("host", host)
                .put("query", query)
                .put("headers", headers);
    }

    @Override
    public JsonObject request() {
        return requestJson;
    }

    @Override
    public BodyStream body() {
        return body;
    }

    @Override
    public void respond(int status, JsonObject headers) {
        context.runOnContext(v -> {
            if (response.headWritten() || closed) {
                return;
            }
            response.setStatusCode(status);
            headers.forEach(h -> {
                if (!HOP_BY_HOP.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                    response.putHeader(h.getKey(), String.valueOf(h.getValue()));
                }
            });
            // An exact length (set by FileResponder) is kept so players can seek; anything else is chunked.
            if (!headers.containsKey("content-length")) {
                response.setChunked(true);
            }
        });
    }

    @Override
    public Future<Void> write(Buffer chunk) {
        Promise<Void> written = Promise.promise();
        context.runOnContext(v -> {
            if (closed || response.ended()) {
                written.fail("The client disconnected");
                return;
            }
            response.write(chunk).onFailure(written::tryFail);
            if (response.writeQueueFull()) {
                response.drainHandler(d -> written.tryComplete());
                response.closeHandler(c -> {
                    closed = true;
                    written.tryFail("The client disconnected");
                });
            } else {
                written.tryComplete();
            }
        });
        return written.future();
    }

    @Override
    public void end() {
        context.runOnContext(v -> {
            if (!response.ended() && !closed) {
                response.end();
            }
        });
    }

    @Override
    public void fail(int status, String message) {
        context.runOnContext(v -> {
            if (response.ended() || closed) {
                return;
            }
            if (response.headWritten()) {
                response.reset();
                return;
            }
            automately.core.http.EarlyAnswer.dropEntityHeaders(response);
            response.setStatusCode(status).putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", new JsonObject().put("code", status).put("message", message)).encode());
        });
    }
}
