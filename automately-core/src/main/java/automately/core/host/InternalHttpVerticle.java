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
    public static final int MAX_CONCURRENT_REQUESTS = 64;

    private final int port;
    private final byte[] secret;
    private final ServeRegistry registry;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private HttpServer server;

    public InternalHttpVerticle(int port, String secret, ServeRegistry registry) {
        this.port = port;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.registry = registry;
    }

    public int port() {
        return server.actualPort();
    }

    @Override
    public Future<?> start() {
        return vertx.createHttpServer().requestHandler(this::handle).listen(port).onSuccess(bound -> server = bound);
    }

    private void handle(HttpServerRequest request) {
        request.pause();
        String presented = request.getHeader(SECRET_HEADER);
        if (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), secret)) {
            reject(request, 403, "Not a cluster request");
            return;
        }
        String jobId = request.getHeader(JOB_HEADER);
        Consumer<HttpExchange> handler = jobId == null ? null : registry.handler(jobId);
        if (handler == null) {
            reject(request, 503, "The service is not accepting requests right now");
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

    private static void reject(HttpServerRequest request, int status, String message) {
        request.response().setStatusCode(status).putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", new JsonObject().put("code", status).put("message", message)).encode());
    }
}
