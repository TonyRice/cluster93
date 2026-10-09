package automately.core.host;

import automately.core.host.egress.Egress;
import automately.core.host.egress.EgressProtocol;
import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingHttp2Session;
import automately.core.runtime.OutgoingHttp2Stream;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientConnection;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnectOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.SocketAddress;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Scripts' HTTP/2 sessions (D-117): one Vert.x HTTP/2 connection each, a Node {@code ClientHttp2Session}. Through the
 * egress gate as {@code http}, counted as a socket against the cluster policy's limits, and verified as sockets are
 * (the chain here, the host name by the script's {@code tls.checkServerIdentity}). Over TLS it's negotiated with ALPN
 * {@code h2}; for {@code http://} it speaks HTTP/2 from the first byte (prior knowledge), as Node's client does. Server
 * push is turned off.
 */
public final class Http2Client {

    /** Node's names for the error codes, by number, as its stream errors name them. */
    static final List<String> CODES = List.of("NGHTTP2_NO_ERROR", "NGHTTP2_PROTOCOL_ERROR", "NGHTTP2_INTERNAL_ERROR",
            "NGHTTP2_FLOW_CONTROL_ERROR", "NGHTTP2_SETTINGS_TIMEOUT", "NGHTTP2_STREAM_CLOSED", "NGHTTP2_FRAME_SIZE_ERROR",
            "NGHTTP2_REFUSED_STREAM", "NGHTTP2_CANCEL", "NGHTTP2_COMPRESSION_ERROR", "NGHTTP2_CONNECT_ERROR",
            "NGHTTP2_ENHANCE_YOUR_CALM", "NGHTTP2_INADEQUATE_SECURITY", "NGHTTP2_HTTP_1_1_REQUIRED");

    private final Vertx vertx;
    private final Egress egress;
    private final SocketClient sockets;
    private final HttpClientAgent secure;
    private final HttpClientAgent clear;

    Http2Client(Vertx vertx, Egress egress, SocketClient sockets) {
        this.vertx = vertx;
        this.egress = egress;
        this.sockets = sockets;
        Http2Settings initial = new Http2Settings().setPushEnabled(false);
        this.secure = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setUseAlpn(true)
                .setAlpnVersions(List.of(HttpVersion.HTTP_2)).setSsl(true).setInitialSettings(initial)
                .setMaxHeaderSize(FetchClient.MAX_HEADER_BYTES).setTcpKeepAlive(true));
        this.clear = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false).setInitialSettings(initial).setMaxHeaderSize(FetchClient.MAX_HEADER_BYTES)
                .setTcpKeepAlive(true));
    }

    /**
     * Opens {@code tenant}'s session. {@code executionOpen} counts the script's sockets (a session is one), and
     * {@code requestSlot} hands out one of its concurrent requests per stream (null when none is free).
     */
    public Future<OutgoingHttp2Session> connect(String tenant, JsonObject request, AtomicInteger executionOpen, Supplier<Runnable> requestSlot) {
        URI authority;
        try {
            authority = new URI(request.getString("authority", ""));
        } catch (Exception e) {
            return Future.failedFuture(new HostException("TypeError", "ERR_INVALID_URL", "Invalid URL"));
        }
        String scheme = authority.getScheme() == null ? "" : authority.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || authority.getHost() == null) {
            return Future.failedFuture(new HostException("TypeError", "ERR_HTTP2_UNSUPPORTED_PROTOCOL",
                    "protocol \"" + scheme + ":\" is unsupported."));
        }
        boolean tls = scheme.equals("https");
        String host = authority.getHost().startsWith("[") ? authority.getHost().substring(1, authority.getHost().length() - 1) : authority.getHost();
        int port = authority.getPort() > 0 ? authority.getPort() : tls ? 443 : 80;
        JsonObject tlsOptions = request.getJsonObject("tls", new JsonObject());
        ClientSSLOptions ssl;
        try {
            ssl = tls ? SocketClient.sslOptions(tlsOptions).setUseAlpn(true).setApplicationLayerProtocols(List.of("h2")) : null;
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(new HostException("TypeError", "ERR_INVALID_ARG_VALUE", "Invalid TLS options: " + e.getMessage()));
        }
        String what = "http2.connect";
        return sockets.reserve(tenant, executionOpen, what, host, port).compose(release -> egress.address(tenant, host, port, EgressProtocol.HTTP, what)
                .compose(address -> {
                    HttpConnectOptions options = new HttpConnectOptions()
                            .setServer(SocketAddress.inetSocketAddress(port, address.getHostAddress()))
                            .setHost(host).setPort(port).setSsl(tls).setProtocolVersion(HttpVersion.HTTP_2)
                            .setConnectTimeout(SocketClient.CONNECT_TIMEOUT_MS);
                    if (ssl != null) {
                        options.setSslOptions(ssl);
                    }
                    return (tls ? secure : clear).connect(options)
                            .recover(err -> Future.failedFuture(SocketClient.networkError(err, what, address.getHostAddress(), port)));
                })
                .compose(connection -> {
                    if (connection.protocolVersion() != HttpVersion.HTTP_2) {
                        connection.close();
                        return Future.failedFuture(new HostException("Error", "ERR_HTTP2_ERROR",
                                "The server at " + host + ":" + port + " didn't agree to HTTP/2"));
                    }
                    JsonObject info = ConnectionInfo.ofSession(connection, tls ? SocketClient.ca(tlsOptions) : null);
                    JsonObject described = info.getJsonObject("tls");
                    if (described != null && !described.getBoolean("authorized", false) && tlsOptions.getBoolean("rejectUnauthorized", true)) {
                        connection.close();
                        return Future.failedFuture(SocketClient.verifyFailure(described.getString("authorizationError")));
                    }
                    automately.core.metrics.Metrics.of(vertx).socketOpened(tenant == null ? "-" : tenant, tls);
                    return Future.<OutgoingHttp2Session>succeededFuture(new Session(connection, vertx.getOrCreateContext(), info, release,
                            requestSlot, tls, host, port));
                })
                .onFailure(err -> release.run()));
    }

    /** Node's settings object from the peer's SETTINGS. */
    static JsonObject nodeSettings(io.vertx.core.http.HttpSettings settings) {
        if (!(settings instanceof Http2Settings s)) {
            return new JsonObject();
        }
        Long connect = s.get(8);
        return new JsonObject().put("headerTableSize", s.getHeaderTableSize()).put("enablePush", s.isPushEnabled())
                .put("initialWindowSize", s.getInitialWindowSize()).put("maxFrameSize", s.getMaxFrameSize())
                .put("maxConcurrentStreams", s.getMaxConcurrentStreams()).put("maxHeaderListSize", s.getMaxHeaderListSize())
                .put("maxHeaderSize", s.getMaxHeaderListSize()).put("enableConnectProtocol", connect != null && connect == 1L);
    }

    /** SETTINGS from Node's names; unknown names are refused, as Node refuses them. */
    static Http2Settings fromNode(JsonObject json) {
        Http2Settings s = new Http2Settings();
        for (Map.Entry<String, Object> entry : json) {
            Object v = entry.getValue();
            switch (entry.getKey()) {
                case "headerTableSize" -> s.setHeaderTableSize(((Number) v).longValue());
                case "enablePush" -> s.setPushEnabled(false);
                case "initialWindowSize" -> s.setInitialWindowSize(((Number) v).intValue());
                case "maxFrameSize" -> s.setMaxFrameSize(((Number) v).intValue());
                case "maxConcurrentStreams" -> s.setMaxConcurrentStreams(((Number) v).longValue());
                case "maxHeaderListSize", "maxHeaderSize" -> s.setMaxHeaderListSize(((Number) v).longValue());
                case "enableConnectProtocol" -> s.set(8, Boolean.TRUE.equals(v) ? 1L : 0L);
                default -> throw new IllegalArgumentException("Unknown setting \"" + entry.getKey() + "\"");
            }
        }
        return s;
    }

    /** A reset stream as Node's error: {@code Stream closed with error code NGHTTP2_REFUSED_STREAM}. */
    static HostException streamError(long code) {
        String name = code >= 0 && code < CODES.size() ? CODES.get((int) code) : String.valueOf(code);
        return new HostException("Error", "ERR_HTTP2_STREAM_ERROR", "Stream closed with error code " + name);
    }

    /** A connection's events for the script: small JSON chunks, buffered while it isn't reading. */
    private static final class Events implements BodyStream {
        private final Context context;
        private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
        private Consumer<Buffer> chunk;
        private Runnable end;
        private boolean paused = true;
        private boolean ended;

        Events(Context context) {
            this.context = context;
        }

        void emit(JsonObject event) {
            context.runOnContext(v -> {
                queue.add(event.toBuffer());
                drain();
            });
        }

        void finish() {
            context.runOnContext(v -> {
                ended = true;
                drain();
            });
        }

        private void drain() {
            while (!paused && chunk != null && !queue.isEmpty()) {
                chunk.accept(queue.poll());
            }
            if (ended && queue.isEmpty() && end != null && !paused) {
                Runnable done = end;
                end = null;
                done.run();
            }
        }

        @Override
        public void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
            context.runOnContext(v -> {
                this.chunk = chunk;
                this.end = end;
                drain();
            });
        }

        @Override
        public void pause() {
            context.runOnContext(v -> paused = true);
        }

        @Override
        public void resume() {
            context.runOnContext(v -> {
                paused = false;
                drain();
            });
        }

        @Override
        public void cancel() {
            context.runOnContext(v -> {
                queue.clear();
                chunk = null;
                end = null;
            });
        }
    }

    private static final class Session implements OutgoingHttp2Session {
        private final HttpClientConnection connection;
        private final Context context;
        private final JsonObject info;
        private final Runnable release;
        private final Supplier<Runnable> requestSlot;
        private final boolean tls;
        private final String host;
        private final int port;
        private final Events events;
        private final AtomicBoolean closed = new AtomicBoolean();

        Session(HttpClientConnection connection, Context context, JsonObject info, Runnable release, Supplier<Runnable> requestSlot,
                boolean tls, String host, int port) {
            this.connection = connection;
            this.context = context;
            this.info = info;
            this.release = release;
            this.requestSlot = requestSlot;
            this.tls = tls;
            this.host = host;
            this.port = port;
            this.events = new Events(context);
            connection.remoteSettingsHandler(settings -> events.emit(new JsonObject().put("type", "remoteSettings").put("settings", nodeSettings(settings))));
            connection.goAwayHandler(goAway -> events.emit(new JsonObject().put("type", "goaway").put("errorCode", goAway.getErrorCode())
                    .put("lastStreamID", goAway.getLastStreamId())
                    .put("opaqueData", goAway.getDebugData() == null ? "" : Base64.getEncoder().encodeToString(goAway.getDebugData().getBytes()))));
            connection.exceptionHandler(err -> events.emit(new JsonObject().put("type", "error").put("message", String.valueOf(err.getMessage()))));
            connection.closeHandler(v -> {
                closed.set(true);
                release.run();
                events.finish();
            });
        }

        /**
         * Runs {@code work} on the connection's own event loop: Netty expects frames to be written there, and calling in
         * from the isolate's thread raced with the connection under load (a ping after a reset went unanswered).
         */
        private <T> Future<T> onContext(java.util.function.Supplier<Future<T>> work) {
            Promise<T> done = Promise.promise();
            context.runOnContext(v -> {
                try {
                    work.get().onComplete(done);
                } catch (RuntimeException e) {
                    done.fail(e);
                }
            });
            return done.future();
        }

        @Override
        public JsonObject info() {
            return info;
        }

        @Override
        public JsonObject remoteSettings() {
            return nodeSettings(connection.remoteSettings());
        }

        @Override
        public BodyStream events() {
            return events;
        }

        @Override
        public Future<OutgoingHttp2Stream> request(JsonObject headers, boolean endStream) {
            Runnable slot = requestSlot.get();
            if (slot == null) {
                return Future.failedFuture(HostException.limit("Too many concurrent requests (limit "
                        + automately.core.runtime.api.FetchApi.MAX_CONCURRENT + ", fetches, HTTP requests and HTTP/2 streams together)"));
            }
            RequestOptions options = new RequestOptions()
                    .setMethod(HttpMethod.valueOf(headers.getString(":method", "GET")))
                    .setURI(headers.getString(":path", "/"))
                    .setHost(host).setPort(port).setSsl(tls)
                    .setFollowRedirects(false);
            String authority = headers.getString(":authority");
            for (Map.Entry<String, Object> header : headers) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (name.startsWith(":")) {
                    continue;
                }
                List<Object> values = header.getValue() instanceof JsonArray list ? list.getList() : List.of(header.getValue());
                for (Object value : values) {
                    options.addHeader(name, String.valueOf(value));
                }
            }
            if (authority != null) {
                options.putHeader("host", authority);
            }
            return onContext(() -> connection.request(options)).compose(request -> {
                AtomicBoolean done = new AtomicBoolean();
                Runnable finished = () -> {
                    if (done.compareAndSet(false, true)) {
                        slot.run();
                    }
                };
                Stream stream = new Stream(request, context, finished);
                    Future<Void> sent = onContext(() -> endStream ? request.end() : request.sendHead());
                return sent.map(v -> (OutgoingHttp2Stream) stream).onFailure(err -> finished.run());
            }).onFailure(err -> slot.run()).recover(err -> Future.failedFuture(err instanceof HostException ? err
                    : new HostException("Error", "ERR_HTTP2_STREAM_ERROR", String.valueOf(err.getMessage()))));
        }

        @Override
        public Future<JsonObject> ping(Buffer payload) {
            long started = System.nanoTime();
            return onContext(() -> connection.ping(payload)).map(ack -> new JsonObject().put("duration", (System.nanoTime() - started) / 1e6)
                    .put("payload", Base64.getEncoder().encodeToString(ack.getBytes())));
        }

        @Override
        public Future<Void> settings(JsonObject settings) {
            Http2Settings update;
            try {
                update = fromNode(settings);
            } catch (IllegalArgumentException | ClassCastException e) {
                return Future.failedFuture(new HostException("TypeError", "ERR_HTTP2_INVALID_SETTING_VALUE", String.valueOf(e.getMessage())));
            }
            return onContext(() -> connection.updateSettings(update));
        }

        @Override
        public void goaway(long errorCode, int lastStreamId, Buffer opaqueData) {
            context.runOnContext(v -> connection.goAway(errorCode, lastStreamId, opaqueData));
        }

        @Override
        public Future<Void> close() {
            return onContext(connection::shutdown).onComplete(done -> release.run());
        }

        @Override
        public void destroy() {
            if (closed.compareAndSet(false, true)) {
                context.runOnContext(v -> connection.close());
                release.run();
            }
        }
    }

    private static final class Stream implements OutgoingHttp2Stream {
        private final HttpClientRequest request;
        private final Context context;
        private final Runnable finished;
        private final Promise<JsonObject> head = Promise.promise();
        private final Promise<JsonObject> trailers = Promise.promise();
        private volatile BodyStream body;

        Stream(HttpClientRequest request, Context context, Runnable finished) {
            this.request = request;
            this.context = context;
            this.finished = finished;
            request.exceptionHandler(err -> fail(err));
            request.response().onFailure(this::fail).onSuccess(response -> {
                response.pause();
                body = new VertxBodyStream(response, context, () -> reset(8));
                response.exceptionHandler(this::fail);
                JsonArray raw = new JsonArray().add(":status").add(String.valueOf(response.statusCode()));
                response.headers().forEach(h -> raw.add(h.getKey().toLowerCase(Locale.ROOT)).add(h.getValue()));
                head.tryComplete(new JsonObject().put("rawHeaders", raw).put("status", response.statusCode()));
                response.end().onComplete(ended -> {
                    JsonArray rawTrailers = new JsonArray();
                    response.trailers().forEach(t -> rawTrailers.add(t.getKey().toLowerCase(Locale.ROOT)).add(t.getValue()));
                    trailers.tryComplete(new JsonObject().put("rawTrailers", rawTrailers));
                    finished.run();
                });
            });
        }

        private void fail(Throwable err) {
            Throwable mapped = err instanceof StreamResetException reset ? streamError(reset.getCode())
                    : err instanceof HostException ? err : new HostException("Error", "ERR_HTTP2_STREAM_ERROR", String.valueOf(err.getMessage()));
            head.tryFail(mapped);
            trailers.tryFail(mapped);
            finished.run();
        }

        @Override
        public int id() {
            return (int) request.streamId();
        }

        @Override
        public Future<Void> write(Buffer chunk) {
            return onLoop(() -> request.write(chunk));
        }

        @Override
        public Future<Void> end() {
            return onLoop(request::end);
        }

        /** As the session's onContext: the stream's frames are written on the connection's event loop. */
        private <T> Future<T> onLoop(java.util.function.Supplier<Future<T>> work) {
            Promise<T> done = Promise.promise();
            context.runOnContext(v -> {
                try {
                    work.get().onComplete(done);
                } catch (RuntimeException e) {
                    done.fail(e);
                }
            });
            return done.future();
        }

        @Override
        public Future<JsonObject> response() {
            return head.future();
        }

        @Override
        public BodyStream body() {
            return body;
        }

        @Override
        public Future<JsonObject> trailers() {
            return trailers.future();
        }

        @Override
        public void reset(long code) {
            context.runOnContext(v -> request.reset(code));
            fail(streamError(code));
        }
    }
}
