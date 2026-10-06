package automately.server.ingress;

import automately.core.host.Addresses;
import automately.core.host.HostRouter;
import automately.core.job.JobStatus;
import automately.core.job.JobStore;
import automately.core.service.ServiceStore;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Serves services' HTTP traffic (the successor to 2017's ClusteredHttpServer). Each request's Host is
 * routed to a service, the service's current run gives the node instance, and the request is forwarded
 * there over the event bus; the script's handler answers. Any node's ingress can serve any host.
 */
public final class IngressVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(IngressVerticle.class);

    public static final int MAX_BODY_BYTES = 1024 * 1024;
    static final long REQUEST_TIMEOUT_MS = 30_000;

    private final int port;
    private final HostRouter router;
    private final ServiceStore services;
    private final JobStore jobs;
    private HttpServer server;

    public IngressVerticle(int port, HostRouter router, ServiceStore services, JobStore jobs) {
        this.port = port;
        this.router = router;
        this.services = services;
        this.jobs = jobs;
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
        // authority() is the Host header on HTTP/1.1 and :authority on HTTP/2.
        String host = host(request);
        router.route(host).compose(route -> {
            if (route == null) {
                return Future.succeededFuture(Answer.error(404, "No service is served at " + host, false));
            }
            return services.get(route.tenant(), route.service()).compose(service -> {
                if (service == null) {
                    return Future.succeededFuture(Answer.error(404, "No service is served at " + host, false));
                }
                if (service.currentJobId() == null) {
                    return Future.succeededFuture(Answer.error(503, "The service is not running", true));
                }
                return jobs.get(service.currentJobId()).compose(job -> job == null || job.status() != JobStatus.RUNNING
                        ? Future.succeededFuture(Answer.error(503, "The service is starting", true))
                        : readBody(request).compose(body -> forward(job.nodeInstance(), job.id(), request, body)));
            });
        }).onComplete(ar -> {
            Answer answer = ar.succeeded() ? ar.result() : failure(ar.cause());
            if (!request.response().ended()) {
                answer.write(request);
            }
        });
    }

    private Future<String> readBody(HttpServerRequest request) {
        String length = request.getHeader("Content-Length");
        if (length != null && Long.parseLong(length) > MAX_BODY_BYTES) {
            return Future.failedFuture(new TooLarge());
        }
        Buffer body = Buffer.buffer();
        io.vertx.core.Promise<String> promise = io.vertx.core.Promise.promise();
        request.handler(chunk -> {
            if (body.length() + chunk.length() > MAX_BODY_BYTES) {
                promise.tryFail(new TooLarge());
                return;
            }
            body.appendBuffer(chunk);
        });
        request.endHandler(v -> promise.tryComplete(body.toString(StandardCharsets.UTF_8)));
        request.exceptionHandler(promise::tryFail);
        request.resume();
        return promise.future();
    }

    private Future<Answer> forward(String instanceId, String jobId, HttpServerRequest request, String body) {
        JsonObject headers = new JsonObject();
        request.headers().names().forEach(name -> headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", request.headers().getAll(name))));
        JsonObject query = new JsonObject();
        request.params().names().forEach(name -> query.put(name, request.params().get(name)));
        JsonObject forwarded = new JsonObject()
                .put("jobId", jobId)
                .put("request", new JsonObject()
                        .put("method", request.method().name())
                        .put("url", request.absoluteURI())
                        .put("path", request.path())
                        .put("host", host(request))
                        .put("query", query)
                        .put("headers", headers)
                        .put("body", body));
        return vertx.eventBus().<JsonObject>request(Addresses.nodeHttp(instanceId), forwarded, new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS))
                .map(reply -> Answer.of(reply.body()));
    }

    private static String host(HttpServerRequest request) {
        return request.authority() == null ? request.getHeader("Host") : request.authority().host();
    }

    private static Answer failure(Throwable error) {
        if (error instanceof TooLarge) {
            return Answer.error(413, "Request body is over " + MAX_BODY_BYTES + " bytes", false);
        }
        if (error instanceof ReplyException reply) {
            if (reply.failureType() == ReplyFailure.TIMEOUT) {
                return Answer.error(504, "The service did not answer in time", false);
            }
            if (reply.failureType() == ReplyFailure.NO_HANDLERS) {
                return Answer.error(503, "The service's node is unavailable", true);
            }
            int code = reply.failureCode();
            return Answer.error(code == 503 || code == 429 || (code >= 400 && code < 600) ? code : 502, reply.getMessage(), code == 503);
        }
        LOG.warn("Ingress request failed", error);
        return Answer.error(502, "Bad gateway", false);
    }

    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super(null, null, false, false);
        }
    }

    /** A response to write: from the script, or an error from the ingress itself. */
    private record Answer(int status, JsonObject headers, String body, boolean retry) {

        static Answer of(JsonObject response) {
            return new Answer(response.getInteger("status", 200), response.getJsonObject("headers", new JsonObject()),
                    response.getString("body", ""), false);
        }

        static Answer error(int status, String message, boolean retry) {
            return new Answer(status, new JsonObject().put("content-type", "application/json"),
                    new JsonObject().put("error", new JsonObject().put("code", status).put("message", message)).encode(), retry);
        }

        void write(HttpServerRequest request) {
            MultiMap out = request.response().headers();
            headers.forEach(h -> {
                String name = h.getKey().toLowerCase(Locale.ROOT);
                if (!name.equals("content-length") && !name.equals("transfer-encoding") && !name.equals("connection")) {
                    out.set(h.getKey(), String.valueOf(h.getValue()));
                }
            });
            if (retry) {
                out.set("Retry-After", "1");
            }
            out.set("Server", "automately");
            request.response().setStatusCode(status).end(body);
        }
    }
}
