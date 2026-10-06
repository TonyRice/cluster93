package automately.server.api;

import automately.core.job.Attempt;
import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.job.OrphanPolicy;
import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import automately.core.scheduler.Addresses;
import automately.core.scheduler.JobScheduler;
import automately.core.service.ServiceStore;
import automately.core.host.DomainStore;
import automately.core.host.HostRouter;
import automately.core.host.TxtResolver;
import automately.core.state.JobStateStore;
import automately.core.state.StepRecord;
import automately.server.ServerConfig;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The REST API. Every node serves it, and any node can answer for any job because jobs live in
 * cluster-wide shared data.
 *
 * <p>Tenancy comes from the {@code X-Automately-Tenant} header for now. This is a stand-in until
 * scoped API tokens replace it, and must not be exposed publicly as is.
 */
public final class ApiVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(ApiVerticle.class);

    static final String TENANT_HEADER = "X-Automately-Tenant";
    static final int MAX_SCRIPT_BYTES = 256 * 1024;

    private final ServerConfig config;
    private final JobStore jobs;
    private final JobStateStore state;
    private final NodeRegistry nodes;
    private final JobScheduler scheduler;
    private final ServiceStore services;
    private final DomainStore domains;
    private final TxtResolver txt;
    private final HostRouter hostRouter;

    private HttpServer server;

    public ApiVerticle(ServerConfig config, JobStore jobs, JobStateStore state, NodeRegistry nodes, JobScheduler scheduler,
                       ServiceStore services, DomainStore domains, TxtResolver txt, HostRouter hostRouter) {
        this.services = services;
        this.domains = domains;
        this.txt = txt;
        this.hostRouter = hostRouter;
        this.config = config;
        this.jobs = jobs;
        this.state = state;
        this.nodes = nodes;
        this.scheduler = scheduler;
    }

    /** The bound port, which differs from the configured one when that was 0. */
    public int port() {
        return server.actualPort();
    }

    @Override
    public Future<?> start() {
        Router router = Router.router(vertx);
        router.get("/health").handler(this::health);
        router.get("/v1/nodes").handler(this::listNodes);
        router.route("/v1/jobs*").handler(this::requireTenant);
        router.route("/v1/services*").handler(this::requireTenant);
        router.route("/v1/bus*").handler(this::requireTenant);
        new BusRoutes(vertx).mount(router);
        router.post("/v1/jobs").handler(BodyHandler.create(false).setBodyLimit(MAX_SCRIPT_BYTES + 4_096)).handler(this::submitJob);
        router.get("/v1/jobs").handler(this::listJobs);
        router.get("/v1/jobs/:id").handler(this::getJob);
        router.get("/v1/jobs/:id/events").handler(this::streamEvents);
        router.get("/v1/jobs/:id/steps").handler(this::getSteps);
        router.post("/v1/jobs/:id/cancel").handler(this::cancelJob);
        new ServiceRoutes(config, services, jobs, scheduler, domains, txt, hostRouter)
                .mount(router, BodyHandler.create(false).setBodyLimit(MAX_SCRIPT_BYTES + 64 * 1024));
        router.route().last().handler(ctx -> error(ctx, 404, "not_found", "No such route"));
        router.route().failureHandler(this::failure);

        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(config.httpPort())
                .onSuccess(bound -> server = bound);
    }

    private void health(RoutingContext ctx) {
        ctx.json(new JsonObject().put("status", "UP").put("node", config.nodeId()));
    }

    private void listNodes(RoutingContext ctx) {
        nodes.liveNodes()
                .onSuccess(live -> ctx.json(new JsonObject().put("nodes", new JsonArray(live.stream()
                        .sorted(Comparator.comparing(NodeInfo::id))
                        .map(NodeInfo::toJson)
                        .toList()))))
                .onFailure(ctx::fail);
    }

    private void requireTenant(RoutingContext ctx) {
        String tenant = ctx.request().getHeader(TENANT_HEADER);
        if (!Names.isValid(tenant)) {
            error(ctx, 401, "unauthorized", "Set the " + TENANT_HEADER + " header to a tenant id: " + Names.RULE);
            return;
        }
        ctx.put("tenant", tenant);
        ctx.next();
    }

    private void submitJob(RoutingContext ctx) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            error(ctx, 400, "invalid_json", "The request body must be a JSON object");
            return;
        }
        if (body == null || !(body.getValue("script") instanceof String script) || script.isBlank()) {
            error(ctx, 400, "invalid_request", "\"script\" must be a non-empty string");
            return;
        }
        if (script.length() > MAX_SCRIPT_BYTES) {
            error(ctx, 413, "script_too_large", "\"script\" is limited to " + MAX_SCRIPT_BYTES + " bytes");
            return;
        }
        Object input = body.getValue("input");
        if (input != null && !(input instanceof JsonObject)) {
            error(ctx, 400, "invalid_request", "\"input\" must be a JSON object");
            return;
        }
        Duration timeout = config.defaultTimeout();
        if (body.containsKey("timeoutMs")) {
            if (!(body.getValue("timeoutMs") instanceof Number ms) || ms.longValue() < 1 || ms.longValue() > config.maxTimeout().toMillis()) {
                error(ctx, 400, "invalid_request", "\"timeoutMs\" must be between 1 and " + config.maxTimeout().toMillis());
                return;
            }
            timeout = Duration.ofMillis(((Number) body.getValue("timeoutMs")).longValue());
        }

        Object policyValue = body.getValue("onOrphan", "fail");
        OrphanPolicy onOrphan = policyValue instanceof String name ? OrphanPolicy.fromApi(name) : null;
        if (onOrphan == null) {
            error(ctx, 400, "invalid_request", "\"onOrphan\" must be \"fail\", \"retry\" or \"resume\"");
            return;
        }
        int maxAttempts = onOrphan == OrphanPolicy.FAIL ? 1 : OrphanPolicy.DEFAULT_MAX_ATTEMPTS;
        if (body.containsKey("maxAttempts")) {
            if (!(body.getValue("maxAttempts") instanceof Integer requested) || requested < 1 || requested > OrphanPolicy.MAX_ATTEMPTS_LIMIT) {
                error(ctx, 400, "invalid_request", "\"maxAttempts\" must be an integer between 1 and " + OrphanPolicy.MAX_ATTEMPTS_LIMIT);
                return;
            }
            if (onOrphan == OrphanPolicy.FAIL && requested != 1) {
                error(ctx, 400, "invalid_request", "\"maxAttempts\" must be 1 when \"onOrphan\" is \"fail\"");
                return;
            }
            maxAttempts = requested;
        }

        scheduler.submit(ctx.get("tenant"), script, (JsonObject) input, timeout, onOrphan, maxAttempts)
                .onSuccess(job -> ctx.response()
                        .setStatusCode(202)
                        .putHeader("Location", "/v1/jobs/" + job.id())
                        .putHeader("Content-Type", "application/json")
                        .end(job.toSummaryJson().encode()))
                .onFailure(ctx::fail);
    }

    private void listJobs(RoutingContext ctx) {
        int limit;
        try {
            limit = Math.min(Math.max(1, Integer.parseInt(ctx.request().getParam("limit", "100"))), 1000);
        } catch (NumberFormatException e) {
            error(ctx, 400, "invalid_request", "limit must be a number");
            return;
        }
        jobs.list(ctx.get("tenant"), limit)
                .onSuccess(list -> ctx.json(new JsonObject().put("jobs",
                        new JsonArray(list.stream().map(Job::toSummaryJson).toList()))))
                .onFailure(ctx::fail);
    }

    private void getJob(RoutingContext ctx) {
        findOwnJob(ctx).onSuccess(job -> {
            if (job != null) {
                ctx.json(job.toSummaryJson());
            }
        });
    }

    private void cancelJob(RoutingContext ctx) {
        findOwnJob(ctx).onSuccess(job -> {
            if (job == null) {
                return;
            }
            if (job.status().isTerminal()) {
                error(ctx, 409, "already_finished", "The job already ended " + job.status());
                return;
            }
            scheduler.cancel(job.id(), "cancelled through the API")
                    .onSuccess(updated -> ctx.response().setStatusCode(202).putHeader("Content-Type", "application/json")
                            .end(updated.toSummaryJson().encode()))
                    .onFailure(ctx::fail);
        });
    }

    /** The current attempt's checkpoint and completed steps (docs/scripting/job-context.md). */
    private void getSteps(RoutingContext ctx) {
        findOwnJob(ctx).onSuccess(job -> {
            if (job == null) {
                return;
            }
            state.load(job.id(), job.attempt())
                    .onSuccess(current -> ctx.json(new JsonObject()
                            .put("jobId", job.id())
                            .put("attempt", job.attempt())
                            .put("checkpoint", current.checkpoint())
                            .put("steps", new JsonArray(current.steps().stream().map(StepRecord::toJson).toList()))))
                    .onFailure(ctx::fail);
        });
    }

    /**
     * Server-sent events for one job: {@code log} events while it runs, an {@code attempt} event whenever a
     * new attempt starts, and a final {@code end} event once the job is finished. A client that connects
     * after the job finished gets its attempts and the last attempt's log replayed instead.
     */
    private void streamEvents(RoutingContext ctx) {
        findOwnJob(ctx).onSuccess(found -> {
            if (found == null) {
                return;
            }
            HttpServerResponse response = ctx.response()
                    .setChunked(true)
                    .putHeader("Content-Type", "text/event-stream")
                    .putHeader("Cache-Control", "no-cache");
            EventStream stream = new EventStream(response);
            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(Addresses.jobEvents(found.id()), msg -> stream.live(msg.body()));
            stream.onEnd(consumer::unregister);
            response.closeHandler(v -> consumer.unregister());

            // Subscribe first, then look again: a job that ended in between is replayed from the store.
            consumer.completion()
                    .compose(v -> jobs.get(found.id()))
                    .onSuccess(stream::start)
                    .onFailure(err -> stream.abort());
        });
    }

    private Future<Job> findOwnJob(RoutingContext ctx) {
        return jobs.get(ctx.pathParam("id"))
                .map(job -> {
                    if (job == null || !job.tenant().equals(ctx.get("tenant"))) {
                        error(ctx, 404, "not_found", "No such job");
                        return null;
                    }
                    return job;
                })
                .onFailure(ctx::fail);
    }

    private void failure(RoutingContext ctx) {
        if (ctx.response().headWritten()) {
            ctx.response().reset();
            return;
        }
        if (ctx.statusCode() == 413) {
            error(ctx, 413, "payload_too_large", "The request body is too large");
            return;
        }
        LOG.error("{} {} failed", ctx.request().method(), ctx.request().path(), ctx.failure());
        error(ctx, 500, "internal", "Internal error");
    }

    static void error(RoutingContext ctx, int status, String code, String message) {
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", new JsonObject().put("code", code).put("message", message)).encode());
    }

    /** Buffers live events until it knows whether the job is still running or must be replayed. */
    private static final class EventStream {

        private final HttpServerResponse response;
        private final List<JsonObject> pending = new ArrayList<>();
        private Runnable onEnd = () -> {
        };
        private boolean started;
        private boolean ended;

        EventStream(HttpServerResponse response) {
            this.response = response;
        }

        void onEnd(Runnable onEnd) {
            this.onEnd = onEnd;
        }

        void live(JsonObject event) {
            if (ended) {
                return;
            }
            if (!started) {
                pending.add(event);
                return;
            }
            write(event);
        }

        void start(Job job) {
            started = true;
            if (job != null && job.status().isTerminal()) {
                pending.clear();
                List<Attempt> attempts = job.attempts();
                for (int i = 1; i < attempts.size(); i++) {
                    write(new JsonObject().put("type", "attempt").put("attempt", attempts.get(i).attempt())
                            .put("node", attempts.get(i).node()).put("previousOutcome", attempts.get(i - 1).outcome().name()));
                }
                job.logs().forEach(line -> write(new JsonObject().put("type", "log").put("level", "info").put("line", line)));
                write(new JsonObject().put("type", "end").put("status", job.status().name()));
                return;
            }
            List<JsonObject> buffered = List.copyOf(pending);
            pending.clear();
            buffered.forEach(this::write);
        }

        void abort() {
            ended = true;
            onEnd.run();
            response.reset();
        }

        private void write(JsonObject event) {
            if (ended) {
                return;
            }
            response.write("event: " + event.getString("type") + "\ndata: " + event.encode() + "\n\n");
            if ("end".equals(event.getString("type"))) {
                ended = true;
                onEnd.run();
                response.end();
            }
        }
    }
}
