package automately.core.host;

import automately.core.runtime.HttpExchange;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * This node's internal HTTP endpoint: the ingress (on any node) proxies service requests here, streaming, and
 * this hands each one to the serving job's handler. Requests must carry the cluster secret, so the port is useless
 * to anyone outside the cluster even if it's reachable.
 */
public final class InternalHttpVerticle extends VerticleBase {

    public static final String SECRET_HEADER = "X-Automately-Cluster-Secret";
    public static final String JOB_HEADER = "X-Automately-Job";
    /** What the ingress forwards so a Node server sees the request as the client sent it (D-123): its own headers, as JSON. */
    public static final String RAW_HEADERS_HEADER = "X-Automately-Raw-Headers";
    /** The client's address and port, as the ingress's connection saw them: "address:port". */
    public static final String REMOTE_HEADER = "X-Automately-Remote";
    public static final String VERSION_HEADER = "X-Automately-Http-Version";
    /** The server's declared port (D-112), or absent for the service's main server. */
    public static final String PORT_HEADER = "X-Automately-Port";
    public static final int MAX_CONCURRENT_REQUESTS = 64;

    private final int port;
    private final byte[] secret;
    private final ServeRegistry registry;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private HttpServer server;

    private final io.vertx.core.http.HttpServerOptions options;

    public InternalHttpVerticle(int port, String secret, ServeRegistry registry) {
        this(port, secret, registry, new io.vertx.core.http.HttpServerOptions());
    }

    /**
     * @param secret  D-036's shared secret, or null when {@code options} require node certificates (D-066), which then
     *                are the only check: only another node of the cluster can complete the TLS handshake
     */
    public InternalHttpVerticle(int port, String secret, ServeRegistry registry, io.vertx.core.http.HttpServerOptions options) {
        this.port = port;
        this.secret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.registry = registry;
        this.options = options;
    }

    public int port() {
        return server.actualPort();
    }

    @Override
    public Future<?> start() {
        // The client's own headers come along as one more header (D-123), so allow twice the ingress's limit.
        options.setMaxHeaderSize(Math.max(options.getMaxHeaderSize(), 64 * 1024));
        return vertx.createHttpServer(options).requestHandler(this::handle).listen(port).onSuccess(bound -> server = bound);
    }

    private void handle(HttpServerRequest request) {
        request.pause();
        String presented = request.getHeader(SECRET_HEADER);
        if (secret != null && (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), secret))) {
            reject(request, 403, "Not a cluster request");
            return;
        }
        String jobId = request.getHeader(JOB_HEADER);
        Integer port = port(request.getHeader(PORT_HEADER));
        Consumer<HttpExchange> handler = jobId == null ? null : port == null ? registry.handler(jobId) : registry.handler(jobId, port);
        if (handler == null) {
            reject(request, 503, port == null ? "The service is not accepting requests right now"
                    : "Nothing is listening on port " + port + " in the service's current run");
            return;
        }
        AtomicInteger count = inFlight.computeIfAbsent(jobId, id -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_CONCURRENT_REQUESTS) {
            count.decrementAndGet();
            reject(request, 429, "Too many requests in flight for this service");
            return;
        }
        request.response().endHandler(v -> count.decrementAndGet());
        request.response().closeHandler(v -> {
            if (!request.response().ended()) {
                count.decrementAndGet();
            }
        });
        handler.accept(new ServerExchange(request, context));
    }

    private static Integer port(String header) {
        try {
            return header == null ? null : Integer.valueOf(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void reject(HttpServerRequest request, int status, String message) {
        request.response().setStatusCode(status).putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", new JsonObject().put("code", status).put("message", message)).encode());
    }
}
