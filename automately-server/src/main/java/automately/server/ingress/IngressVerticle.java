package automately.server.ingress;

import automately.core.host.HostRouter;
import automately.core.host.InternalHttpVerticle;
import automately.core.job.JobStatus;
import automately.core.job.JobStore;
import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import automately.core.service.ServiceStore;
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

    private final int port;
    private final HostRouter router;
    private final ServiceStore services;
    private final JobStore jobs;
    private final NodeRegistry nodes;
    private final String secret;
    private final Duration idleTimeout;
    private final long maxRequestBytes;
    private HttpServer server;
    private HttpClient client;

    private final java.util.function.UnaryOperator<HttpClientOptions> internalTls;

    /**
     * @param secret      D-036's shared secret for the internal endpoint, or null when node certificates secure it (D-066)
     * @param internalTls adds TLS with this node's certificate to the client for the internal endpoint (identity if none)
     */
    public IngressVerticle(int port, HostRouter router, ServiceStore services, JobStore jobs, NodeRegistry nodes, String secret,
                           Duration idleTimeout, long maxRequestBytes, java.util.function.UnaryOperator<HttpClientOptions> internalTls) {
        this.internalTls = internalTls;
        this.port = port;
        this.router = router;
        this.services = services;
        this.jobs = jobs;
        this.nodes = nodes;
        this.secret = secret;
        this.idleTimeout = idleTimeout;
        this.maxRequestBytes = maxRequestBytes;
    }

    public int port() {
        return server.actualPort();
    }

    @Override
    public Future<?> start() {
        client = vertx.createHttpClient(internalTls.apply(new HttpClientOptions().setKeepAlive(true)), new PoolOptions().setHttp1MaxSize(256));
        return vertx.createHttpServer().requestHandler(this::handle).listen(port).onSuccess(bound -> server = bound);
    }

    private void handle(HttpServerRequest request) {
        request.pause();
        String host = host(request);
        String length = request.getHeader("Content-Length");
        if (length != null && parseLong(length) > maxRequestBytes) {
            error(request, 413, "Request body is over " + maxRequestBytes + " bytes", false);
            return;
        }
        resolve(host).onComplete(ar -> {
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

    /** Where a request should go: a node endpoint and job, or an error to answer with. */
    private record Target(String endpoint, String jobId, int status, String error) {

        static Target of(String endpoint, String jobId) {
            return new Target(endpoint, jobId, 0, null);
        }

        static Target error(int status, String message) {
            return new Target(null, null, status, message);
        }
    }

    private Future<Target> resolve(String host) {
        return router.route(host).compose(route -> {
            if (route == null) {
                return Future.succeededFuture(Target.error(404, "No service is served at " + host));
            }
            return services.get(route.tenant(), route.service()).compose(service -> {
                if (service == null) {
                    return Future.succeededFuture(Target.error(404, "No service is served at " + host));
                }
                if (service.currentJobId() == null) {
                    return Future.succeededFuture(Target.error(503, "The service is not running"));
                }
                return jobs.get(service.currentJobId()).compose(job -> {
                    if (job == null || job.status() != JobStatus.RUNNING) {
                        return Future.succeededFuture(Target.error(503, "The service is starting"));
                    }
                    return nodes.liveNodes().map(live -> live.stream()
                            .filter(node -> node.instanceId().equals(job.nodeInstance()) && node.endpoint() != null)
                            .map(NodeInfo::endpoint)
                            .findFirst()
                            .map(endpoint -> Target.of(endpoint, job.id()))
                            .orElse(Target.error(503, "The service's node is unavailable")));
                });
            });
        });
    }

    private void proxy(HttpServerRequest request, String host, Target target) {
        int colon = target.endpoint().lastIndexOf(':');
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        request.headers().forEach(h -> {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(name) && !name.startsWith("x-automately-") && !name.equals("content-length")) {
                headers.add(h.getKey(), h.getValue());
            }
        });
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String remote = request.remoteAddress() == null ? "unknown" : request.remoteAddress().hostAddress();
        headers.set("X-Forwarded-For", forwardedFor == null ? remote : forwardedFor + ", " + remote);
        headers.set("X-Forwarded-Host", host);
        headers.set("X-Forwarded-Proto", request.isSSL() ? "https" : "http");
        if (secret != null) {
            headers.set(InternalHttpVerticle.SECRET_HEADER, secret);
        } else {
            headers.remove(InternalHttpVerticle.SECRET_HEADER);
        }
        headers.set(InternalHttpVerticle.JOB_HEADER, target.jobId());
        RequestOptions options = new RequestOptions()
                .setMethod(request.method())
                .setHost(target.endpoint().substring(0, colon))
                .setPort(Integer.parseInt(target.endpoint().substring(colon + 1)))
                .setURI(request.uri())
                .setHeaders(headers)
                .setConnectTimeout(5_000);

        HttpServerResponse response = request.response();
        client.request(options).onFailure(err -> {
            // The node is gone or unreachable; the cluster will move the service.
            error(request, 503, "The service's node is unavailable", true);
        }).onSuccess(upstream -> {
            Idle idle = new Idle(request, upstream);
            response.closeHandler(v -> {
                idle.stop();
                upstream.reset();
            });
            response.endHandler(v -> idle.stop());
            upstream.response().onFailure(err -> upstreamFailed(request, err)).onSuccess(answer -> {
                response.setStatusCode(answer.statusCode());
                answer.headers().forEach(h -> {
                    if (!HOP_BY_HOP.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                        response.headers().add(h.getKey(), h.getValue());
                    }
                });
                if (answer.statusCode() == 503 && !response.headers().contains("Retry-After")) {
                    response.putHeader("Retry-After", "1");
                }
                response.putHeader("Server", "automately");
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
                    response.end();
                });
                answer.exceptionHandler(err -> {
                    idle.stop();
                    error(request, 502, "The service's connection failed", false);
                });
            });
            streamBody(request, upstream, idle);
        });
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
        automately.core.http.EarlyAnswer.dropEntityHeaders(response);
        response.setStatusCode(status).putHeader("content-type", "application/json").putHeader("Server", "automately");
        if (retry) {
            response.putHeader("Retry-After", "1");
        }
        // Answering before the body was read (a 413, an unknown host): drain it so the client sees this answer, or
        // close the connection if it's too big to be worth it (D-039).
        boolean unread = automately.core.http.EarlyAnswer.prepare(request);
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
