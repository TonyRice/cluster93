package cluster93.server.ingress;

import cluster93.core.files.FileResponder;
import cluster93.core.files.FileStore;
import cluster93.core.host.HostRouter;
import cluster93.core.host.InternalHttpVerticle;
import cluster93.core.host.ServerExchange;
import cluster93.core.job.JobStatus;
import cluster93.core.job.JobStore;
import cluster93.core.node.NodeInfo;
import cluster93.core.node.NodeRegistry;
import cluster93.core.service.ServiceStore;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Serves services' HTTP traffic (the successor to 2017's ClusteredHttpServer). Each request's host is routed to a
 * service; the service's current run gives the node; the request is then **proxied as a stream** to that node's
 * internal endpoint, request body and response body both, with backpressure. Any node's ingress can serve any host.
 * There's no size limit on responses and no total time limit, only an idle timeout between chunks, so large files and
 * server-sent events work.
 */
public final class IngressVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(IngressVerticle.class);

    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
            "proxy-authorization", "proxy-authenticate", "host");
    /** A response's own headers that don't pass through: hop-by-hop, but its Trailer announcement does (D-123). */
    static final Set<String> RESPONSE_HOP_BY_HOP = Set.of("connection", "keep-alive", "transfer-encoding", "te", "upgrade",
            "proxy-authorization", "proxy-authenticate");

    private final int port;
    private final String bindHost;
    private final HostRouter router;
    private final ServiceStore services;
    private final JobStore jobs;
    private final NodeRegistry nodes;
    private final String secret;
    private final Duration idleTimeout;
    private final Duration upgradedIdleTimeout;
    private final long maxRequestBytes;
    private final AppRouter appRouter;
    private final FileStore files;
    /** The local API's port, resolved at request time because the API is deployed after the ingress is built. */
    private final java.util.function.IntSupplier apiPort;
    private HttpServer server;
    private HttpClient client;
    private HttpClient apiClient;

    private final java.util.function.UnaryOperator<HttpClientOptions> internalTls;

    /**
     * @param secret      D-036's shared secret for the internal endpoint, or null when node certificates secure it (D-066)
     * @param internalTls adds TLS with this node's certificate to the client for the internal endpoint (identity if none)
     * @param upgradedIdleTimeout how long an upgraded connection (a WebSocket) may go without a byte either way (D-125)
     * @param appRouter   apps' host and route resolution, tried before ordinary service routing
     * @param files       the VFS, for serving an app's static assets directly
     * @param apiPort     this node's API port, for the same-origin {@code api:} rewrite
     */
    public IngressVerticle(int port, String bindHost, HostRouter router, ServiceStore services, JobStore jobs, NodeRegistry nodes, String secret,
                           Duration idleTimeout, Duration upgradedIdleTimeout, long maxRequestBytes,
                           java.util.function.UnaryOperator<HttpClientOptions> internalTls, AppRouter appRouter, FileStore files,
                           java.util.function.IntSupplier apiPort) {
        this.upgradedIdleTimeout = upgradedIdleTimeout;
        this.internalTls = internalTls;
        this.port = port;
        this.bindHost = bindHost;
        this.router = router;
        this.services = services;
        this.jobs = jobs;
        this.nodes = nodes;
        this.secret = secret;
        this.idleTimeout = idleTimeout;
        this.maxRequestBytes = maxRequestBytes;
        this.appRouter = appRouter;
        this.files = files;
        this.apiPort = apiPort;
    }

    public int port() {
        return server.actualPort();
    }

    @Override
    public Future<?> start() {
        client = vertx.createHttpClient(internalTls.apply(new HttpClientOptions().setKeepAlive(true)), new PoolOptions().setHttp1MaxSize(256));
        // The API rewrite talks to this node's own API in plain HTTP; it never carries the internal secret (D-066).
        apiClient = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true), new PoolOptions().setHttp1MaxSize(64));
        // Expect: 100-continue is answered at once, as Node's server does without a 'checkContinue' listener (D-123).
        return vertx.createHttpServer(new io.vertx.core.http.HttpServerOptions().setHandle100ContinueAutomatically(true))
                .requestHandler(this::handle).listen(port, bindHost).onSuccess(bound -> server = bound);
    }

    private void handle(HttpServerRequest request) {
        request.pause();
        String host = host(request);
        String length = request.getHeader("Content-Length");
        if (length != null && parseLong(length) > maxRequestBytes) {
            error(request, 413, "Request body is over " + maxRequestBytes + " bytes", false);
            return;
        }
        appRouter.route(appRequest(request, host)).onComplete(ar -> {
            if (ar.failed()) {
                LOG.warn("Ingress routing failed for {}", host, ar.cause());
                error(request, 502, "Bad gateway", false);
                return;
            }
            dispatch(request, host, ar.result());
        });
    }

    /** Acts on what {@link AppRouter} decided, falling back to ordinary service routing for {@code NotAnApp}. */
    private void dispatch(HttpServerRequest request, String host, AppRouter.Decision decision) {
        switch (decision) {
            case AppRouter.Decision.Denied denied -> error(request, 403, denied.message(), false);
            case AppRouter.Decision.Error failure -> error(request, failure.status(), failure.message(), false);
            case AppRouter.Decision.Proxy proxy -> proxied(request, host,
                    resolveService(proxy.tenant(), proxy.service(), proxy.uri(), null, host));
            case AppRouter.Decision.Api api -> proxyApi(request, host, api.uri());
            case AppRouter.Decision.Static asset -> serveStatic(request, asset);
            case AppRouter.Decision.Redirect redirect -> redirect(request, redirect);
            case AppRouter.Decision.NotAnApp ignored -> proxied(request, host, resolve(host, request.uri()));
        }
    }

    /** Proxies to a resolved target, or answers the routing error it carries. */
    private void proxied(HttpServerRequest request, String host, Future<Target> resolved) {
        resolved.onComplete(ar -> {
            if (ar.failed()) {
                LOG.warn("Ingress routing failed for {}", host, ar.cause());
                error(request, 502, "Bad gateway", false);
                return;
            }
            Target target = ar.result();
            if (target.error() != null) {
                error(request, target.status(), target.error(), target.status() == 503);
                return;
            }
            proxy(request, host, target);
        });
    }

    /** The request as {@link AppRouter} sees it: lower-cased headers, the client address, path and query apart. */
    private static AppRouter.Request appRequest(HttpServerRequest request, String host) {
        JsonObject headers = new JsonObject();
        request.headers().forEach(h -> headers.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue()));
        String remote = request.remoteAddress() == null ? null : request.remoteAddress().hostAddress();
        return new AppRouter.Request(host, request.method().name(), request.path(), request.query(), remote, headers);
    }

    /**
     * Where a request should go: a node endpoint, job and server port (null for the main server), the URI the server
     * sees and the path prefix taken off it; or an error to answer with.
     */
    private record Target(String endpoint, String jobId, Integer port, String uri, String prefix, int status, String error) {

        Target at(String endpoint, String jobId) {
            return new Target(endpoint, jobId, port, uri, prefix, 0, null);
        }

        static Target error(int status, String message) {
            return new Target(null, null, null, null, null, status, message);
        }
    }

    private Future<Target> resolve(String host, String uri) {
        return router.route(host).compose(route -> route == null
                ? Future.succeededFuture(Target.error(404, "No service is served at " + host))
                : resolveService(route.tenant(), route.service(), uri, route.port(), host));
    }

    /** Where a named service's request goes: its current run's node, and the port its host or path selects. */
    private Future<Target> resolveService(String tenant, String service, String uri, Integer declaredPort, String host) {
        return services.get(tenant, service).compose(found -> {
            if (found == null) {
                return Future.succeededFuture(Target.error(404, "No service is served at " + host));
            }
            // Which server (D-112, D-123): the host's or domain's port, else a declared path prefix, else the main one.
            java.util.List<cluster93.core.service.ServicePorts.Port> ports = cluster93.core.service.ServicePorts.of(found.ports());
            Integer port = declaredPort;
            String forwardUri = uri;
            String prefix = null;
            if (port == null) {
                cluster93.core.service.ServicePorts.Match match = cluster93.core.service.ServicePorts.match(ports, uri);
                if (match != null) {
                    port = match.port();
                    prefix = match.prefix();
                    forwardUri = match.uri();
                }
            }
            cluster93.core.service.ServicePorts.Port declared = port == null ? null : cluster93.core.service.ServicePorts.declared(ports, port);
            if (declared != null && !declared.expose()) {
                return Future.succeededFuture(Target.error(404, "No service is served at " + host));
            }
            Target routed = new Target(null, null, port, forwardUri, prefix, 0, null);
            if (found.currentJobId() == null) {
                return Future.succeededFuture(Target.error(503, "The service is not running"));
            }
            return jobs.get(found.currentJobId()).compose(job -> {
                if (job == null || job.status() != JobStatus.RUNNING) {
                    return Future.succeededFuture(Target.error(503, "The service is starting"));
                }
                return nodes.liveNodes().map(live -> live.stream()
                        .filter(node -> node.instanceId().equals(job.nodeInstance()) && node.endpoint() != null)
                        .map(NodeInfo::endpoint)
                        .findFirst()
                        .map(endpoint -> routed.at(endpoint, job.id()))
                        .orElse(Target.error(503, "The service's node is unavailable")));
            });
        });
    }

    private void proxy(HttpServerRequest request, String host, Target target) {
        proxy(request, host, target, client, true);
    }

    /** Proxies to this node's own API for an app's {@code api:} rewrite; the API is a plain, public endpoint. */
    private void proxyApi(HttpServerRequest request, String host, String uri) {
        int port = apiPort.getAsInt();
        if (port <= 0) {
            error(request, 503, "This node doesn't serve the API", false);
            return;
        }
        proxy(request, host, new Target("127.0.0.1:" + port, null, null, uri, null, 0, null), apiClient, false);
    }

    private void proxy(HttpServerRequest request, String host, Target target, HttpClient httpClient, boolean internal) {
        int colon = target.endpoint().lastIndexOf(':');
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        request.headers().forEach(h -> {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            // The public API rewrite forwards the tenant selector so the API can scope the request; every other
            // x-cluster93-* header is the cluster's own routing state and never crosses that boundary.
            boolean tenantSelector = !internal && name.equals("x-cluster93-tenant");
            if (!HOP_BY_HOP.contains(name) && (!name.startsWith("x-cluster93-") || tenantSelector)
                    && !name.equals("content-length") && !name.equals("expect")) {
                headers.add(h.getKey(), h.getValue());
            }
        });
        if (internal) {
            // The request as the client sent it, for Node servers (D-123): its own headers in order and spelling, hop-by-hop
            // ones included, its address and its HTTP version. A client can't set these: x-cluster93-* are dropped above.
            // The client's own X-Forwarded-* are left out: only the ingress's reach the service, appended on the node (its
            // X-Forwarded-For carries the client's chain, then the address the ingress saw), so none can be spoofed.
            io.vertx.core.json.JsonArray raw = new io.vertx.core.json.JsonArray();
            request.headers().forEach(h -> {
                String lower = h.getKey().toLowerCase(Locale.ROOT);
                if (!lower.startsWith("x-cluster93-") && !lower.startsWith("x-forwarded-")) {
                    raw.add(h.getKey()).add(h.getValue());
                }
            });
            headers.set(InternalHttpVerticle.RAW_HEADERS_HEADER, raw.encode());
            if (request.remoteAddress() != null) {
                headers.set(InternalHttpVerticle.REMOTE_HEADER, request.remoteAddress().hostAddress() + ":" + request.remoteAddress().port());
            }
            headers.set(InternalHttpVerticle.VERSION_HEADER, switch (request.version()) {
                case HTTP_1_0 -> "1.0";
                case HTTP_2 -> "2.0";
                default -> "1.1";
            });
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String remote = request.remoteAddress() == null ? "unknown" : request.remoteAddress().hostAddress();
        headers.set("X-Forwarded-For", forwardedFor == null ? remote : forwardedFor + ", " + remote);
        headers.set("X-Forwarded-Host", host);
        headers.set("X-Forwarded-Proto", request.isSSL() ? "https" : "http");
        if (internal) {
            if (secret != null) {
                headers.set(InternalHttpVerticle.SECRET_HEADER, secret);
            } else {
                headers.remove(InternalHttpVerticle.SECRET_HEADER);
            }
            headers.set(InternalHttpVerticle.JOB_HEADER, target.jobId());
            if (target.port() != null) {
                headers.set(InternalHttpVerticle.PORT_HEADER, String.valueOf(target.port()));
            }
        }
        // A request asking to upgrade (a WebSocket) keeps its Connection and Upgrade, so the service can answer 101 (D-125).
        boolean upgrade = asksToUpgrade(request);
        if (upgrade) {
            headers.set("Connection", "Upgrade");
            headers.set("Upgrade", request.getHeader("Upgrade"));
        }
        if (internal) {
            // Only the ingress says what prefix it took off; a client's own claim is dropped.
            headers.remove("X-Forwarded-Prefix");
            if (target.prefix() != null) {
                headers.set("X-Forwarded-Prefix", target.prefix());
            }
        }
        RequestOptions options = new RequestOptions()
                .setMethod(request.method())
                .setHost(target.endpoint().substring(0, colon))
                .setPort(Integer.parseInt(target.endpoint().substring(colon + 1)))
                .setURI(target.uri())
                .setHeaders(headers)
                .setConnectTimeout(5_000);

        HttpServerResponse response = request.response();
        httpClient.request(options).onFailure(err -> {
            // The node is gone or unreachable; the cluster will move the service.
            error(request, 503, "The service's node is unavailable", true);
        }).onSuccess(upstream -> {
            Idle idle = new Idle(request, upstream);
            response.closeHandler(v -> {
                idle.stop();
                upstream.reset();
            });
            response.endHandler(v -> idle.stop());
            if (upgrade) {
                // The head goes alone; a 101 turns both connections into one byte tunnel.
                upstream.connect().onFailure(err -> {
                    // A service that closed without answering is a closed connection to the client too, as from Node.
                    if (err instanceof io.vertx.core.http.HttpClosedException) {
                        request.connection().close();
                    } else {
                        upstreamFailed(request, err);
                    }
                }).onSuccess(answer -> {
                    if (answer.statusCode() == 101) {
                        idle.stop();
                        tunnel(request, answer);
                    } else {
                        // Not switching, the exchange is an ordinary one Vert.x never saw ended: the connection is
                        // let go once the answer has been relayed, rather than reused.
                        relay(request, answer, idle);
                        answer.end().onComplete(done -> upstream.reset());
                    }
                });
                request.resume();
                return;
            }
            upstream.response().onFailure(err -> upstreamFailed(request, err)).onSuccess(answer -> relay(request, answer, idle));
            streamBody(request, upstream, idle);
        });
    }

    /** Streams an app's static asset from the VFS, with ETag, Range and the manifest's headers (D-...). */
    private void serveStatic(HttpServerRequest request, AppRouter.Decision.Static asset) {
        ServerExchange exchange = new ServerExchange(request, vertx.getOrCreateContext());
        JsonObject requestHeaders = new JsonObject();
        request.headers().forEach(h -> requestHeaders.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue()));
        FileResponder.respond(files, asset.tenant(), asset.path(), request.method().name(), requestHeaders, asset.headers(), exchange)
                .onFailure(err -> {
                    if (!request.response().headWritten()) {
                        LOG.warn("Serving app asset {} failed", asset.path(), err);
                        error(request, 500, "Could not serve the app asset", false);
                    }
                });
    }

    /** An app route's 3xx: the client is sent to the destination. */
    private static void redirect(HttpServerRequest request, AppRouter.Decision.Redirect redirect) {
        HttpServerResponse response = request.response();
        cluster93.core.http.EarlyAnswer.dropEntityHeaders(response);
        for (java.util.Map.Entry<String, Object> header : redirect.headers()) {
            response.putHeader(header.getKey(), String.valueOf(header.getValue()));
        }
        response.setStatusCode(redirect.status()).putHeader("Location", redirect.location()).putHeader("Server", "c93");
        response.end();
    }

    /** Sends the service's answer back to the client as it streams in. */
    private void relay(HttpServerRequest request, io.vertx.core.http.HttpClientResponse answer, Idle idle) {
        HttpServerResponse response = request.response();
        response.setStatusCode(answer.statusCode());
        // The service's own reason phrase, as Node's res.writeHead(201, 'Made It') sends it (D-123).
        if (answer.statusMessage() != null && !answer.statusMessage().isEmpty()) {
            response.setStatusMessage(answer.statusMessage());
        }
        answer.headers().forEach(h -> {
            if (!RESPONSE_HOP_BY_HOP.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                response.headers().add(h.getKey(), h.getValue());
            }
        });
        if (answer.statusCode() == 503 && !response.headers().contains("Retry-After")) {
            response.putHeader("Retry-After", "1");
        }
        response.putHeader("Server", "c93");
        if (answer.getHeader("Content-Length") == null) {
            response.setChunked(true);
        }
        answer.handler(chunk -> {
            idle.touch();
            response.write(chunk);
            if (response.writeQueueFull()) {
                answer.pause();
                response.drainHandler(v -> answer.resume());
            }
        });
        answer.endHandler(v -> {
            idle.stop();
            if (response.isChunked()) {
                response.trailers().addAll(answer.trailers());
            }
            response.end();
        });
        answer.exceptionHandler(err -> {
            idle.stop();
            error(request, 502, "The service's connection failed", false);
        });
    }

    /**
     * Whether a request asks to upgrade its connection (a WebSocket): HTTP/1.1, an {@code Upgrade} header, the
     * {@code upgrade} token in {@code Connection}, a GET with no body (D-125).
     */
    static boolean asksToUpgrade(HttpServerRequest request) {
        // GET only: what WebSocket clients send, and the only method Vert.x's client switches a connection for.
        if (request.version() != io.vertx.core.http.HttpVersion.HTTP_1_1 || request.method() != io.vertx.core.http.HttpMethod.GET
                || request.getHeader("Upgrade") == null || request.getHeader("Transfer-Encoding") != null) {
            return false;
        }
        String length = request.getHeader("Content-Length");
        if (length != null && parseLong(length) != 0) {
            return false;
        }
        for (String value : request.headers().getAll("Connection")) {
            for (String token : value.split(",")) {
                if (token.trim().equalsIgnoreCase("upgrade")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The service answered 101: the client gets the same head, and from then on bytes pass both ways untouched, with
     * backpressure, until either side closes or nothing moves for the upgraded idle timeout (D-125).
     */
    private void tunnel(HttpServerRequest request, io.vertx.core.http.HttpClientResponse answer) {
        HttpServerResponse response = request.response();
        answer.headers().forEach(h -> response.headers().add(h.getKey(), h.getValue()));
        response.putHeader("Server", "c93");
        io.vertx.core.net.NetSocket service = answer.netSocket();
        service.pause();
        request.toNetSocket().onFailure(err -> service.close()).onSuccess(client -> {
            long[] last = {System.currentTimeMillis()};
            long period = Math.max(50, Math.min(10_000, upgradedIdleTimeout.toMillis() / 4));
            long timer = vertx.setPeriodic(period, id -> {
                if (System.currentTimeMillis() - last[0] >= upgradedIdleTimeout.toMillis()) {
                    vertx.cancelTimer(id);
                    client.close();
                    service.close();
                }
            });
            pipe(client, service, last);
            pipe(service, client, last);
            client.closeHandler(v -> {
                vertx.cancelTimer(timer);
                service.close();
            });
            service.closeHandler(v -> {
                vertx.cancelTimer(timer);
                client.close();
            });
            service.resume();
        });
    }

    /** Bytes a tunnel direction may have written but not yet on the wire before it stops reading. */
    private static final long TUNNEL_HIGH_WATER = 256 * 1024;

    /**
     * One direction of a tunnel, its backpressure from each write's own completion: the reader pauses with more than
     * {@link #TUNNEL_HIGH_WATER} written but not yet on the wire, and resumes once half of it has gone.
     */
    private static void pipe(io.vertx.core.net.NetSocket from, io.vertx.core.net.NetSocket to, long[] last) {
        long[] inFlight = {0};
        boolean[] paused = {false};
        from.handler(chunk -> {
            last[0] = System.currentTimeMillis();
            int length = chunk.length();
            inFlight[0] += length;
            to.write(chunk).onComplete(done -> {
                inFlight[0] -= length;
                if (paused[0] && inFlight[0] <= TUNNEL_HIGH_WATER / 2) {
                    paused[0] = false;
                    from.resume();
                }
            });
            if (!paused[0] && inFlight[0] > TUNNEL_HIGH_WATER) {
                paused[0] = true;
                from.pause();
            }
        });
        // A half-close passes on as a close: the WebSocket closing handshake has already happened by then.
        from.endHandler(v -> to.end());
    }

    /**
     * Closes a proxied request that has gone {@code idleTimeout} without a byte moving in **either** direction.
     * Vert.x's own idle timeout only watches the response, so it fired during long uploads the service was
     * still reading.
     */
    private final class Idle {

        private final HttpServerRequest request;
        private final HttpClientRequest upstream;
        private final long timer;
        private volatile long lastActivity = System.currentTimeMillis();

        Idle(HttpServerRequest request, HttpClientRequest upstream) {
            this.request = request;
            this.upstream = upstream;
            long period = Math.max(50, Math.min(1_000, idleTimeout.toMillis() / 4));
            this.timer = vertx.setPeriodic(period, id -> check());
        }

        void touch() {
            lastActivity = System.currentTimeMillis();
        }

        void stop() {
            vertx.cancelTimer(timer);
        }

        private void check() {
            if (System.currentTimeMillis() - lastActivity < idleTimeout.toMillis()) {
                return;
            }
            stop();
            // Answer first: resetting the upstream fires its failure path (a 502) synchronously.
            error(request, 504, "The service did not answer in time", false);
            upstream.reset();
        }
    }

    /** Forwards the request body chunk by chunk, pausing the client when the node can't keep up, and enforcing the size limit. */
    private void streamBody(HttpServerRequest request, HttpClientRequest upstream, Idle idle) {
        String length = request.getHeader("Content-Length");
        if (length != null) {
            upstream.putHeader("Content-Length", length);
        } else {
            upstream.setChunked(true);
        }
        long[] received = {0};
        request.handler(chunk -> {
            idle.touch();
            received[0] += chunk.length();
            if (received[0] > maxRequestBytes) {
                request.handler(null);
                upstream.reset();
                error(request, 413, "Request body is over " + maxRequestBytes + " bytes", false);
                return;
            }
            upstream.write(chunk);
            if (upstream.writeQueueFull()) {
                request.pause();
                upstream.drainHandler(v -> {
                    idle.touch();
                    request.resume();
                });
            }
        });
        request.endHandler(v -> upstream.end());
        request.exceptionHandler(err -> upstream.reset());
        request.resume();
    }

    private static void upstreamFailed(HttpServerRequest request, Throwable err) {
        String message = String.valueOf(err.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out")) {
            error(request, 504, "The service did not answer in time", false);
        } else {
            error(request, 502, "The service's connection failed", false);
        }
    }

    private static String host(HttpServerRequest request) {
        // authority() is the Host header on HTTP/1.1 and :authority on HTTP/2.
        return request.authority() == null ? request.getHeader("Host") : request.authority().host();
    }

    private static void error(HttpServerRequest request, int status, String message, boolean retry) {
        HttpServerResponse response = request.response();
        if (response.ended()) {
            return;
        }
        if (response.headWritten()) {
            response.reset();
            return;
        }
        cluster93.core.http.EarlyAnswer.dropEntityHeaders(response);
        response.setStatusCode(status).putHeader("content-type", "application/json").putHeader("Server", "c93");
        if (retry) {
            response.putHeader("Retry-After", "1");
        }
        // Answering before the body was read (a 413, an unknown host): drain it so the client sees this answer, or
        // close the connection if it's too big to be worth it (D-039).
        boolean unread = cluster93.core.http.EarlyAnswer.prepare(request);
        if (unread) {
            response.putHeader("Connection", "close");
        }
        response.end(new JsonObject().put("error", new JsonObject().put("code", status).put("message", message)).encode());
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
