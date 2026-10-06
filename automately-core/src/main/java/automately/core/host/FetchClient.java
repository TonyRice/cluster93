package automately.core.host;

import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Outbound HTTP for scripts ({@code fetch}). Resolves the host, refuses addresses the {@link EgressPolicy}
 * forbids, then connects to that exact address (keeping the {@code Host} header and TLS SNI), so the
 * checked address is the one used. No redirects are followed; bodies are text, capped in size.
 */
public final class FetchClient {

    public static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    public static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "transfer-encoding", "connection");

    private final Vertx vertx;
    private final HttpClient client;
    private final EgressPolicy policy;

    public FetchClient(Vertx vertx, EgressPolicy policy) {
        this.vertx = vertx;
        this.policy = policy;
        this.client = vertx.createHttpClient(new HttpClientOptions().setForceSni(true).setVerifyHost(true).setKeepAlive(true),
                new PoolOptions().setHttp1MaxSize(64));
    }

    /** {@code args}: url, method, headers (object), body (string or null), timeoutMs (or null). */
    public Future<JsonObject> fetch(JsonObject args) {
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
        String body = args.getString("body");
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            return Future.failedFuture(HostException.limit("Request body is over " + MAX_REQUEST_BYTES + " bytes"));
        }
        long timeoutMs = Math.min(args.getLong("timeoutMs") == null ? DEFAULT_TIMEOUT_MS : args.getLong("timeoutMs"), DEFAULT_TIMEOUT_MS);
        String host = uri.getHost().startsWith("[") ? uri.getHost().substring(1, uri.getHost().length() - 1) : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

        return resolve(host).compose(address -> {
            RequestOptions options = new RequestOptions()
                    .setMethod(HttpMethod.valueOf(method))
                    .setServer(SocketAddress.inetSocketAddress(port, address.getHostAddress()))
                    .setHost(host)
                    .setPort(port)
                    .setSsl(scheme.equals("https"))
                    .setURI(path)
                    .setFollowRedirects(false)
                    .setTimeout(timeoutMs);
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            JsonObject requested = args.getJsonObject("headers", new JsonObject());
            requested.forEach(h -> {
                if (!FORBIDDEN_HEADERS.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                    headers.add(h.getKey(), String.valueOf(h.getValue()));
                }
            });
            headers.set("Host", uri.getPort() > 0 ? host + ":" + port : host);
            headers.add("User-Agent", "Automately/2");
            options.setHeaders(headers);
            return exchange(options, body, uri.toString());
        });
    }

    private Future<InetAddress> resolve(String host) {
        return vertx.<InetAddress>executeBlocking(() -> {
            InetAddress[] all;
            try {
                all = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new HostException("TypeError", "dns_failed", "Could not resolve " + host);
            }
            return Arrays.stream(all).filter(policy::allows).findFirst().orElseThrow(() ->
                    new HostException("TypeError", "egress_denied", "fetch to " + host + " is not allowed: it resolves to a private or reserved address"));
        }, false);
    }

    private Future<JsonObject> exchange(RequestOptions options, String body, String url) {
        Promise<JsonObject> result = Promise.promise();
        client.request(options).onFailure(err -> result.tryFail(networkError(err))).onSuccess(request -> {
            request.response().onFailure(err -> result.tryFail(networkError(err))).onSuccess(response -> {
                Buffer received = Buffer.buffer();
                response.handler(chunk -> {
                    if (received.length() + chunk.length() > MAX_RESPONSE_BYTES) {
                        // Fail first: resetting fires the exception handler synchronously, which would win otherwise.
                        result.tryFail(HostException.limit("Response body is over " + MAX_RESPONSE_BYTES + " bytes"));
                        request.reset();
                        return;
                    }
                    received.appendBuffer(chunk);
                });
                response.exceptionHandler(err -> result.tryFail(networkError(err)));
                response.endHandler(v -> {
                    JsonObject headers = new JsonObject();
                    response.headers().names().forEach(name ->
                            headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", response.headers().getAll(name))));
                    result.tryComplete(new JsonObject()
                            .put("status", response.statusCode())
                            .put("statusText", response.statusMessage())
                            .put("headers", headers)
                            .put("body", received.toString(StandardCharsets.UTF_8))
                            .put("url", url));
                });
            });
            if (body == null) {
                request.end();
            } else {
                request.end(body);
            }
        });
        return result.future();
    }

    private static Throwable networkError(Throwable err) {
        if (err instanceof HostException) {
            return err;
        }
        String message = err.getMessage() == null ? err.toString() : err.getMessage();
        if (err instanceof java.util.concurrent.TimeoutException || message.toLowerCase(Locale.ROOT).contains("timeout")) {
            return new HostException("TimeoutError", "timeout", "fetch timed out: " + message);
        }
        return new HostException("TypeError", "network_error", "fetch failed: " + message);
    }
}
