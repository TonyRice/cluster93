package cluster93.core.host;

import cluster93.core.runtime.BodyStream;
import cluster93.core.runtime.HttpExchange;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An {@link HttpExchange} over a request the ingress proxied to this node's internal endpoint. Response writes
 * complete when the socket has drained, so a script producing faster than the client reads waits instead of
 * filling memory.
 */
public final class ServerExchange implements HttpExchange {

    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "transfer-encoding", "te", "upgrade",
            "proxy-authorization", "proxy-authenticate");

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final Context context;
    private final JsonObject requestJson;
    private final BodyStream body;
    private final Promise<Boolean> closedPromise = Promise.promise();
    private volatile boolean closed;
    private volatile io.vertx.core.json.JsonArray trailers;
    private final UpgradeSocket upgrade;

    public ServerExchange(HttpServerRequest request, Context context) {
        this(request, context, () -> { });
    }

    /**
     * @param done runs once, when the response has ended or the connection closed (an upgraded one included). Vert.x
     *             keeps one end and one close handler per response, so whoever counts exchanges counts through this.
     */
    public ServerExchange(HttpServerRequest request, Context context, Runnable done) {
        this.request = request;
        this.response = request.response();
        this.context = context;
        this.requestJson = describe(request);
        this.body = new VertxBodyStream(request, context, () -> { });
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable finished = () -> {
            if (once.compareAndSet(false, true)) {
                done.run();
            }
        };
        this.upgrade = UpgradeSocket.asks(request) ? new UpgradeSocket(request, context, new JsonObject()
                .put("remoteAddress", requestJson.getString("remoteAddress")).put("remotePort", requestJson.getInteger("remotePort")),
                finished) : null;
        response.closeHandler(v -> {
            closed = true;
            closedPromise.tryComplete(!response.ended());
            if (upgrade != null) {
                upgrade.closed();
            }
            finished.run();
        });
        response.endHandler(v -> {
            closedPromise.tryComplete(false);
            if (upgrade != null) {
                upgrade.closed();
            }
            finished.run();
        });
    }

    @Override
    public cluster93.core.runtime.OutgoingSocket upgrade() {
        return upgrade;
    }

    /** Whether a header is the cluster's own, which a script never sees. */
    private static boolean internal(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith("x-cluster93-");
    }

    private static JsonObject describe(HttpServerRequest request) {
        JsonObject headers = new JsonObject();
        request.headers().names().forEach(name -> {
            if (!internal(name)) {
                headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", request.headers().getAll(name)));
            }
        });
        JsonObject query = new JsonObject();
        request.params().names().forEach(name -> query.put(name, request.params().get(name)));
        String host = request.getHeader("X-Forwarded-Host") != null ? request.getHeader("X-Forwarded-Host") : request.getHeader("Host");
        String proto = request.getHeader("X-Forwarded-Proto") != null ? request.getHeader("X-Forwarded-Proto") : "http";
        // The client's own headers, in its order and spelling, then what the proxy added (D-123).
        io.vertx.core.json.JsonArray raw = new io.vertx.core.json.JsonArray();
        String forwarded = request.getHeader(InternalHttpVerticle.RAW_HEADERS_HEADER);
        if (forwarded != null) {
            try {
                for (Object item : new io.vertx.core.json.JsonArray(forwarded)) {
                    raw.add(String.valueOf(item));
                }
            } catch (io.vertx.core.json.DecodeException e) {
                raw.clear();
            }
            for (String added : List.of("X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Prefix")) {
                if (request.getHeader(added) != null) {
                    raw.add(added).add(request.getHeader(added));
                }
            }
        } else {
            request.headers().forEach(h -> {
                if (!internal(h.getKey())) {
                    raw.add(h.getKey()).add(h.getValue());
                }
            });
        }
        String remote = request.getHeader(InternalHttpVerticle.REMOTE_HEADER);
        String remoteAddress = request.remoteAddress() == null ? null : request.remoteAddress().hostAddress();
        Integer remotePort = request.remoteAddress() == null ? null : request.remoteAddress().port();
        if (remote != null && remote.lastIndexOf(':') > 0) {
            remoteAddress = remote.substring(0, remote.lastIndexOf(':'));
            try {
                remotePort = Integer.parseInt(remote.substring(remote.lastIndexOf(':') + 1));
            } catch (NumberFormatException e) {
                remotePort = null;
            }
        }
        String version = request.getHeader(InternalHttpVerticle.VERSION_HEADER);
        String port = request.getHeader(InternalHttpVerticle.PORT_HEADER);
        return new JsonObject()
                .put("method", request.method().name())
                .put("url", proto + "://" + host + request.uri())
                .put("path", request.path())
                .put("host", host)
                .put("query", query)
                .put("headers", headers)
                .put("uri", request.uri())
                .put("rawHeaders", raw)
                .put("httpVersion", version != null ? version : request.version() == io.vertx.core.http.HttpVersion.HTTP_1_0 ? "1.0"
                        : request.version() == io.vertx.core.http.HttpVersion.HTTP_2 ? "2.0" : "1.1")
                .put("remoteAddress", remoteAddress)
                .put("remotePort", remotePort)
                .put("encrypted", "https".equals(proto))
                // As llhttp's flag: the server's 'upgrade' listener, or its shouldUpgradeCallback, then decides (D-125).
                .put("upgrade", UpgradeSocket.asks(request))
                .put("port", port == null ? null : Integer.valueOf(port));
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
    public void respond(int status, String statusMessage, io.vertx.core.json.JsonArray rawHeaders) {
        context.runOnContext(v -> {
            if (response.headWritten() || closed) {
                return;
            }
            response.setStatusCode(status);
            if (statusMessage != null) {
                response.setStatusMessage(statusMessage);
            }
            boolean length = false;
            boolean chunked = false;
            for (int i = 0; i + 1 < rawHeaders.size(); i += 2) {
                String name = rawHeaders.getString(i);
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("content-length")) {
                    length = true;
                } else if (lower.equals("transfer-encoding")) {
                    chunked = true;
                }
                if (!HOP_BY_HOP.contains(lower)) {
                    response.headers().add(name, rawHeaders.getString(i + 1));
                }
            }
            // The script's own length is kept, as Node sends it; anything else streams chunked.
            if (chunked || !length) {
                response.headers().remove("content-length");
                response.setChunked(true);
            }
        });
    }

    @Override
    public void trailers(io.vertx.core.json.JsonArray rawTrailers) {
        trailers = rawTrailers;
    }

    @Override
    public Future<Boolean> closed() {
        return closedPromise.future();
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
                // Not a second closeHandler: Vert.x keeps one, and the constructor's counts the exchange as done.
                closedPromise.future().onSuccess(left -> written.tryFail("The client disconnected"));
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
                io.vertx.core.json.JsonArray last = trailers;
                if (last != null && response.isChunked()) {
                    for (int i = 0; i + 1 < last.size(); i += 2) {
                        response.trailers().add(last.getString(i), last.getString(i + 1));
                    }
                }
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
            cluster93.core.http.EarlyAnswer.dropEntityHeaders(response);
            response.setStatusCode(status).putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", new JsonObject().put("code", status).put("message", message)).encode());
        });
    }
}
