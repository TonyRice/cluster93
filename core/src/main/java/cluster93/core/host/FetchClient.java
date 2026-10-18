package cluster93.core.host;

import cluster93.core.runtime.BodyStream;
import cluster93.core.runtime.FetchedResponse;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.OutgoingRequest;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbound HTTP for scripts ({@code fetch}). The {@link cluster93.core.host.egress.Egress egress gate} resolves the
 * host and picks an address the tenant may reach (D-108), then this connects to that exact address (keeping the {@code Host} header and TLS SNI), so the
 * checked address is the one used. No redirects are followed. The response body is streamed; the script's
 * {@code text()} and {@code json()} cap what they buffer.
 */
public final class FetchClient {

    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    public static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "transfer-encoding", "connection");
    /** A streamed request may set its own Host and Content-Length, as Node's may; framing stays the host's. */
    private static final Set<String> STREAMED_FORBIDDEN_HEADERS = Set.of("transfer-encoding", "connection", "upgrade", "keep-alive");
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    /** A streamed request's longest wait to connect is {@link #DEFAULT_TIMEOUT_MS}; between chunks, this (D-109). */
    public static final long MAX_STREAMED_IDLE_MS = 300_000;
    public static final int MAX_HEADER_BYTES = 64 * 1024;

    private final Vertx vertx;
    private final HttpClient client;
    private volatile HttpClient insecure;
    private volatile SocketClient sockets;
    private volatile Http2Client http2;
    /** Connections requests have gone out on, so a script's socket can say when one was reused from the pool (D-114). */
    private final java.util.Set<io.vertx.core.http.HttpConnection> seen =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private final cluster93.core.host.egress.Gate egress;

    /** Through a gate that knows only this node and no tenant rules: for tests. */
    public FetchClient(Vertx vertx, EgressPolicy policy) {
        this(vertx, cluster93.core.host.egress.Egress.standalone(vertx, policy));
    }

    public FetchClient(Vertx vertx, cluster93.core.host.egress.Gate egress) {
        this.vertx = vertx;
        this.egress = egress;
        this.client = vertx.createHttpClient(clientOptions(), new PoolOptions().setHttp1MaxSize(64));
    }

    /**
     * {@code args}: url, method, headers (object), body (string or null), timeoutMs (or null). Completes with the
     * status and headers once they arrive; the body is a paused stream. {@code release} runs once the body has
     * ended, failed or been cancelled (or if the request fails).
     */
    public Future<FetchedResponse> fetch(JsonObject args, Runnable release) {
        return fetch(null, args, null, release);
    }

    /**
     * As {@link #fetch(JsonObject, Runnable)}, for {@code tenant}: its egress policy applies (D-108). {@code body}, when
     * given, is sent as is, in place of {@code args.body}.
     */
    public Future<FetchedResponse> fetch(String tenant, JsonObject args, Buffer body, Runnable release) {
        URI uri;
        try {
            uri = new URI(args.getString("url", ""));
        } catch (URISyntaxException e) {
            return Future.failedFuture(HostException.invalid("Invalid URL: " + e.getMessage()));
        }
        String scheme = Optional.ofNullable(uri.getScheme()).map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Future.failedFuture(HostException.invalid("fetch only supports http and https URLs"));
        }
        if (uri.getHost() == null) {
            return Future.failedFuture(HostException.invalid("The URL has no host"));
        }
        String method = args.getString("method", "GET");
        if (!METHODS.contains(method)) {
            return Future.failedFuture(HostException.invalid("Unsupported method " + method));
        }
        Buffer payload = body != null ? body
                : args.getString("body") == null ? null : Buffer.buffer(args.getString("body").getBytes(StandardCharsets.UTF_8));
        if (payload != null && payload.length() > MAX_REQUEST_BYTES) {
            return Future.failedFuture(HostException.limit("Request body is over " + MAX_REQUEST_BYTES + " bytes"));
        }
        // Applies to connecting and to any pause between chunks, not to the whole download, so long streams work.
        long timeoutMs = Math.min(args.getLong("timeoutMs") == null ? DEFAULT_TIMEOUT_MS : args.getLong("timeoutMs"), DEFAULT_TIMEOUT_MS);
        String host = uri.getHost().startsWith("[") ? uri.getHost().substring(1, uri.getHost().length() - 1) : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

        return egress.address(tenant, host, port, "fetch").compose(address -> {
            RequestOptions options = new RequestOptions()
                    .setMethod(HttpMethod.valueOf(method))
                    .setServer(SocketAddress.inetSocketAddress(port, address.getHostAddress()))
                    .setHost(host)
                    .setPort(port)
                    .setSsl(scheme.equals("https"))
                    .setURI(path)
                    .setFollowRedirects(false)
                    .setConnectTimeout(timeoutMs)
                    .setIdleTimeout(timeoutMs);
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            JsonObject requested = args.getJsonObject("headers", new JsonObject());
            requested.forEach(h -> {
                if (!FORBIDDEN_HEADERS.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                    headers.add(h.getKey(), String.valueOf(h.getValue()));
                }
            });
            headers.set("Host", uri.getPort() > 0 ? host + ":" + port : host);
            headers.add("User-Agent", "Cluster93/2");
            options.setHeaders(headers);
            return exchange(options, payload, uri.toString(), release);
        });
    }

    /**
     * Opens a request whose body is written in chunks ({@code node:http}, D-109), through the gate as {@code tenant}.
     * {@code args}: url, method, headers (an object whose values may be lists), chunked, timeoutMs, and tls
     * ({@code rejectUnauthorized}, and PEM {@code ca}, {@code cert} and {@code key}). {@code release} runs once, when
     * the response body ends, fails or is cancelled, or the request fails or is aborted.
     */
    public Future<OutgoingRequest> open(String tenant, JsonObject args, Runnable release) {
        URI uri;
        try {
            uri = new URI(args.getString("url", ""));
        } catch (URISyntaxException e) {
            return Future.failedFuture(HostException.invalid("Invalid URL: " + e.getMessage()));
        }
        String scheme = Optional.ofNullable(uri.getScheme()).map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Future.failedFuture(HostException.invalid("Only http and https URLs are supported"));
        }
        if (uri.getHost() == null) {
            return Future.failedFuture(HostException.invalid("The URL has no host"));
        }
        String method = args.getString("method", "GET");
        if (!TOKEN.matcher(method).matches() || method.length() > 32 || method.equals("CONNECT") || method.equals("TRACE")) {
            return Future.failedFuture(HostException.invalid("Unsupported method " + method));
        }
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        String contentLength = null;
        JsonObject requested = args.getJsonObject("headers", new JsonObject());
        for (var header : requested) {
            String name = header.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!TOKEN.matcher(name).matches()) {
                return Future.failedFuture(HostException.invalid("Invalid header name \"" + name + "\""));
            }
            java.util.List<Object> values = header.getValue() instanceof io.vertx.core.json.JsonArray list ? list.getList()
                    : java.util.List.of(header.getValue());
            for (Object value : values) {
                String text = String.valueOf(value);
                if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
                    return Future.failedFuture(HostException.invalid("Invalid value for header \"" + name + "\""));
                }
                if (lower.equals("content-length")) {
                    if (!text.matches("\\d{1,15}")) {
                        return Future.failedFuture(HostException.invalid("Invalid content-length \"" + text + "\""));
                    }
                    contentLength = text;
                } else if (!STREAMED_FORBIDDEN_HEADERS.contains(lower)) {
                    headers.add(name, text);
                }
            }
        }
        long idleMs = Math.max(1, Math.min(args.getLong("timeoutMs") == null ? MAX_STREAMED_IDLE_MS : args.getLong("timeoutMs"), MAX_STREAMED_IDLE_MS));
        JsonObject tls = args.getJsonObject("tls", new JsonObject());
        String host = uri.getHost().startsWith("[") ? uri.getHost().substring(1, uri.getHost().length() - 1) : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        boolean chunked = args.getBoolean("chunked", false) && contentLength == null;
        String length = contentLength;
        if (!headers.contains("Host")) {
            headers.set("Host", uri.getPort() > 0 ? host + ":" + port : host);
        }
        if (!headers.contains("User-Agent")) {
            headers.set("User-Agent", "Cluster93/2");
        }
        AtomicBoolean released = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<HttpClient> dedicated = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable done = () -> {
            if (released.compareAndSet(false, true)) {
                release.run();
                HttpClient own = dedicated.get();
                if (own != null) {
                    own.close();
                }
            }
        };

        return egress.address(tenant, host, port, "http.request").compose(address -> {
            HttpClient client;
            try {
                client = clientFor(scheme.equals("https") ? tls : new JsonObject(), dedicated);
            } catch (RuntimeException e) {
                done.run();
                return Future.failedFuture(HostException.invalid("Invalid TLS options: " + e.getMessage()));
            }
            RequestOptions options = new RequestOptions()
                    .setMethod(HttpMethod.valueOf(method))
                    .setServer(SocketAddress.inetSocketAddress(port, address.getHostAddress()))
                    .setHost(host)
                    .setPort(port)
                    .setSsl(scheme.equals("https"))
                    .setURI(path)
                    .setFollowRedirects(false)
                    .setConnectTimeout(Math.min(idleMs, DEFAULT_TIMEOUT_MS))
                    .setIdleTimeout(idleMs)
                    .setHeaders(headers);
            boolean verified = !scheme.equals("https") || tls.getBoolean("rejectUnauthorized", true);
            return client.request(options).<OutgoingRequest>map(request -> {
                Context context = vertx.getOrCreateContext();
                io.vertx.core.http.HttpConnection connection = request.connection();
                JsonObject described = connection == null ? new JsonObject()
                        : ConnectionInfo.of(connection, !seen.add(connection), verified);
                if (length != null) {
                    request.putHeader("Content-Length", length);
                } else if (chunked) {
                    request.setChunked(true);
                }
                Promise<FetchedResponse> response = Promise.promise();
                request.response().onFailure(err -> {
                    done.run();
                    response.tryFail(networkError(err, "http.request"));
                }).onSuccess(r -> {
                    r.pause();
                    response.tryComplete(fetched(r, uri.toString(), context, request::reset, done));
                });
                return new OutgoingRequest() {
                    @Override
                    public Future<Void> write(Buffer chunk) {
                        return request.write(chunk).recover(err -> Future.failedFuture(networkError(err, "http.request")));
                    }

                    @Override
                    public Future<Void> end() {
                        return request.end().recover(err -> Future.failedFuture(networkError(err, "http.request")));
                    }

                    @Override
                    public Future<FetchedResponse> response() {
                        return response.future();
                    }

                    @Override
                    public JsonObject connection() {
                        return described;
                    }

                    @Override
                    public void abort() {
                        context.runOnContext(v -> {
                            request.reset();
                            response.tryFail(new HostException("Error", "aborted", "The request was aborted"));
                            done.run();
                        });
                    }
                };
            }).recover(err -> Future.failedFuture(networkError(err, "http.request")));
        }).onFailure(err -> done.run());
    }

    /** The egress gate, and through it the resolver, scripts' connections and {@code dns} share (D-122). */
    public cluster93.core.host.egress.Gate egress() {
        return egress;
    }

    /** Scripts' TCP and TLS sockets (D-116), through the same gate as their HTTP requests. Made when first asked for. */
    public SocketClient sockets() {
        SocketClient made = sockets;
        if (made == null) {
            synchronized (this) {
                if (sockets == null) {
                    sockets = new SocketClient(vertx, egress);
                }
                made = sockets;
            }
        }
        return made;
    }

    /** Scripts' HTTP/2 sessions (D-117), counted with their sockets. Made when first asked for. */
    public Http2Client http2() {
        Http2Client made = http2;
        if (made == null) {
            synchronized (this) {
                if (http2 == null) {
                    http2 = new Http2Client(vertx, egress, sockets());
                }
                made = http2;
            }
        }
        return made;
    }

    /**
     * The shared client, or the shared one that doesn't verify certificates ({@code rejectUnauthorized: false}), or, for
     * a request with its own CA or client certificate, a client of its own, closed with the request (D-109).
     */
    private HttpClient clientFor(JsonObject tls, java.util.concurrent.atomic.AtomicReference<HttpClient> dedicated) {
        boolean verify = tls.getBoolean("rejectUnauthorized", true);
        Object ca = tls.getValue("ca");
        String cert = tls.getString("cert");
        String key = tls.getString("key");
        if (ca == null && cert == null && key == null) {
            return verify ? client : insecure();
        }
        HttpClientOptions options = clientOptions().setTrustAll(!verify).setVerifyHost(verify);
        if (ca != null) {
            io.vertx.core.net.PemTrustOptions trust = new io.vertx.core.net.PemTrustOptions();
            for (Object pem : ca instanceof io.vertx.core.json.JsonArray list ? list.getList() : java.util.List.of(ca)) {
                trust.addCertValue(Buffer.buffer(String.valueOf(pem)));
            }
            options.setTrustOptions(trust);
        }
        if (cert != null || key != null) {
            if (cert == null || key == null) {
                throw new IllegalArgumentException("a client certificate needs both cert and key");
            }
            options.setKeyCertOptions(new io.vertx.core.net.PemKeyCertOptions().setCertValue(Buffer.buffer(cert)).setKeyValue(Buffer.buffer(key)));
        }
        HttpClient own = vertx.createHttpClient(options, new PoolOptions().setHttp1MaxSize(1));
        dedicated.set(own);
        return own;
    }

    private HttpClient insecure() {
        HttpClient made = insecure;
        if (made == null) {
            synchronized (this) {
                if (insecure == null) {
                    insecure = vertx.createHttpClient(clientOptions().setTrustAll(true).setVerifyHost(false), new PoolOptions().setHttp1MaxSize(64));
                }
                made = insecure;
            }
        }
        return made;
    }

    private static HttpClientOptions clientOptions() {
        return new HttpClientOptions().setForceSni(true).setVerifyHost(true).setKeepAlive(true).setMaxHeaderSize(MAX_HEADER_BYTES);
    }

    private Future<FetchedResponse> exchange(RequestOptions options, Buffer body, String url, Runnable release) {
        Context context = vertx.getOrCreateContext();
        Promise<FetchedResponse> result = Promise.promise();
        client.request(options).onFailure(err -> result.tryFail(networkError(err))).onSuccess(request -> {
            request.response().onFailure(err -> result.tryFail(networkError(err))).onSuccess(response -> {
                response.pause();
                result.tryComplete(fetched(response, url, context, request::reset, release));
            });
            if (body == null) {
                request.end();
            } else {
                request.end(body);
            }
        });
        return result.future();
    }

    /**
     * The response's status and headers, and its body as a paused stream. {@code headers} joins repeated headers with
     * ", "; {@code rawHeaders} keeps each as sent, as Node's {@code [name, value, name, value, ...]} (set-cookie needs it).
     */
    private static FetchedResponse fetched(HttpClientResponse response, String url, Context context, Runnable reset, Runnable release) {
        JsonObject headers = new JsonObject();
        response.headers().names().forEach(name ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", response.headers().getAll(name))));
        io.vertx.core.json.JsonArray raw = new io.vertx.core.json.JsonArray();
        response.headers().forEach(header -> raw.add(header.getKey()).add(header.getValue()));
        JsonObject meta = new JsonObject()
                .put("status", response.statusCode())
                .put("statusText", response.statusMessage())
                .put("headers", headers)
                .put("rawHeaders", raw)
                .put("httpVersion", response.version() == io.vertx.core.http.HttpVersion.HTTP_1_0 ? "1.0" : "1.1")
                .put("url", url);
        BodyStream stream = new VertxBodyStream(response, context, reset);
        return new FetchedResponse(meta, new BodyStream() {
            @Override
            public void handler(java.util.function.Consumer<Buffer> chunk, Runnable end, java.util.function.Consumer<Throwable> error) {
                stream.handler(chunk, () -> {
                    release.run();
                    end.run();
                }, err -> {
                    release.run();
                    error.accept(networkError(err));
                });
            }

            @Override
            public void pause() {
                stream.pause();
            }

            @Override
            public void resume() {
                stream.resume();
            }

            @Override
            public void cancel() {
                release.run();
                stream.cancel();
            }
        });
    }

    private static Throwable networkError(Throwable err) {
        return networkError(err, "fetch");
    }

    private static Throwable networkError(Throwable err, String what) {
        if (err instanceof HostException) {
            return err;
        }
        String message = err.getMessage() == null ? err.toString() : err.getMessage();
        if (err instanceof java.util.concurrent.TimeoutException || message.toLowerCase(Locale.ROOT).contains("timeout")) {
            return new HostException("TimeoutError", "timeout", what + " timed out: " + message);
        }
        return new HostException("TypeError", "network_error", what + " failed: " + message);
    }
}
