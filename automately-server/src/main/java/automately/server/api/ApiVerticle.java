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
import automately.core.files.FileStore;
import automately.core.host.DomainStore;
import automately.core.host.HostRouter;
import automately.core.host.TxtResolver;
import automately.core.state.JobStateStore;
import automately.core.state.StepRecord;
import automately.server.ServerConfig;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
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
 * <p>Every {@code /v1} request carries a bearer token (D-045); {@link RouteScopes} says what each route needs.
 */
public final class ApiVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(ApiVerticle.class);

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
    private final automately.core.storage.TenantQuota quota;
    private final FileStore files;
    private final automately.core.auth.Authenticator authenticator;
    private final automately.core.auth.AuthStore authStore;

    private final automately.core.auth.RateLimiter limiter;
    private final automately.core.files.Room room;
    private final StorageApi.Wiring storage;
    /** Marks the MCP tools' own calls to this API, which the tool call already counted (D-047). */
    private final String loopbackSecret = java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
    private final java.util.Map<Long, BodyHandler> bodyHandlers = new java.util.concurrent.ConcurrentHashMap<>();

    private HttpServer server;
    private Router router;
    private io.vertx.openapi.contract.OpenAPIContract contract;
    private io.vertx.openapi.validation.RequestValidator validator;

    public ApiVerticle(ServerConfig config, JobStore jobs, JobStateStore state, NodeRegistry nodes, JobScheduler scheduler,
                       ServiceStore services, DomainStore domains, TxtResolver txt, HostRouter hostRouter, FileStore files,
                       automately.core.storage.TenantQuota quota, automately.core.auth.Authenticator authenticator,
                       automately.core.auth.AuthStore authStore, automately.core.files.Room room, StorageApi.Wiring storage) {
        this.room = room;
        this.storage = storage;
        this.limiter = new automately.core.auth.RateLimiter(config.rateLimitPerMinute());
        this.authenticator = authenticator;
        this.authStore = authStore;
        this.files = files;
        this.quota = quota;
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
        return Contract.load(vertx).compose(loaded -> {
            for (RouteScopes.Rule rule : RouteScopes.RULES) {
                if (loaded.operation(rule.operationId()) == null) {
                    return Future.failedFuture(new IllegalStateException("Route rule names " + rule.operationId()
                            + ", which isn't in " + CONTRACT + " (D-046)"));
                }
            }
            contract = loaded;
            validator = io.vertx.openapi.validation.RequestValidator.create(vertx, loaded);
            refreshNodeCount();
            vertx.setPeriodic(NODE_COUNT_REFRESH.toMillis(), id -> refreshNodeCount());
            return listen();
        });
    }

    /** The contract every request is validated against (D-046). */
    static final String CONTRACT = Contract.PATH;
    static final Duration NODE_COUNT_REFRESH = Duration.ofSeconds(5);

    private void refreshNodeCount() {
        nodes.liveNodes().onSuccess(live -> limiter.nodes(live.size()));
    }

    private Future<HttpServer> listen() {
        router = Router.router(vertx);
        router.get("/health").handler(this::health);
        router.route("/v1/*").handler(this::authenticate).handler(this::rateLimit).handler(this::readBody).handler(this::validate);
        router.get("/v1/openapi.json").handler(ctx -> ctx.json(contract.getRawContract()));
        router.get("/v1/nodes").handler(this::listNodes);
        new McpRoutes(vertx, authenticator, limiter, () -> server.actualPort(), loopbackSecret).mount(router);
        new AuthRoutes(authStore, authenticator, this::isEmpty, tenant -> storage.config().forget(tenant)).mount(router);
        new StorageApi(storage, files, authStore, quota).mount(router);
        new FilesRoutes(files, quota, room).mount(router);
        new BusRoutes(vertx).mount(router);
        router.post("/v1/jobs").handler(this::submitJob);
        router.get("/v1/jobs").handler(this::listJobs);
        router.get("/v1/jobs/:id").handler(this::getJob);
        router.get("/v1/jobs/:id/events").handler(this::streamEvents);
        router.get("/v1/jobs/:id/steps").handler(this::getSteps);
        router.post("/v1/jobs/:id/cancel").handler(this::cancelJob);
        new ServiceRoutes(config, services, jobs, scheduler, domains, txt, hostRouter).mount(router);
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

    /** Every route this API serves, as "METHOD path" (regex routes as their pattern). For the route-table tests. */
    public List<String> routes() {
        List<String> all = new ArrayList<>();
        for (io.vertx.ext.web.Route route : router.getRoutes()) {
            if (route.methods() != null && route.getName() != null) {
                route.methods().forEach(method -> all.add(method.name() + " " + route.getName()));
            }
        }
        return all;
    }

    /**
     * Resolves the bearer token and checks it against the route's rule (D-045). The lookup is asynchronous, so the
     * request is paused meanwhile; routes that read the body get it still paused, the rest are resumed.
     */
    private void authenticate(RoutingContext ctx) {
        io.vertx.core.http.HttpServerRequest request = ctx.request();
        java.util.Optional<RouteScopes.Rule> rule = RouteScopes.find(request.method(), request.path());
        if (rule.isEmpty()) {
            error(ctx, 404, "not_found", "No such route");
            return;
        }
        request.pause();
        ctx.put("rule", rule.get());
        if (rule.get().who() == RouteScopes.Who.PUBLIC) {
            ctx.next();
            return;
        }
        authenticator.authenticate(request.getHeader("Authorization")).onComplete(ar -> {
            if (ar.failed()) {
                if (ar.cause() instanceof automately.core.auth.AuthFailure failure) {
                    if (failure.status() == 401) {
                        ctx.response().putHeader("WWW-Authenticate", "Bearer realm=\"automately\"");
                    }
                    error(ctx, failure.status(), failure.code(), failure.getMessage());
                } else {
                    ctx.fail(ar.cause());
                }
                return;
            }
            automately.core.auth.Principal principal = ar.result();
            String denied = denied(rule.get(), principal);
            if (denied != null) {
                error(ctx, 403, "forbidden", denied);
                return;
            }
            ctx.put("principal", principal);
            if (!principal.admin()) {
                ctx.put("tenant", principal.tenant());
            }
            ctx.next();
        });
    }

    /** Per-tenant limits (milestone 2). The admin and public routes aren't limited. */
    private void rateLimit(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        if (tenant == null || !limiter.enabled() || loopbackSecret.equals(ctx.request().getHeader(McpRoutes.LOOPBACK_HEADER))) {
            ctx.next();
            return;
        }
        automately.core.auth.RateLimiter.Decision decision = limiter.acquire(tenant, System.nanoTime());
        ctx.response().putHeader("RateLimit-Policy", "\"tenant\";q=" + decision.limit() + ";w=60")
                .putHeader("RateLimit", "\"tenant\";r=" + decision.remaining() + ";t=" + decision.resetSeconds());
        if (!decision.allowed()) {
            ctx.response().putHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            error(ctx, 429, "rate_limited", "Over " + decision.limit() + " requests a minute for this tenant; retry in "
                    + decision.retryAfterSeconds() + " s");
            return;
        }
        ctx.next();
    }

    /** JSON bodies are read here, once, with the route's limit; streamed bodies stay paused for their handler. */
    private void readBody(RoutingContext ctx) {
        RouteScopes.Rule rule = ctx.get("rule");
        switch (rule.body()) {
            case NONE -> {
                ctx.request().resume();
                ctx.next();
            }
            case JSON -> bodyHandlers.computeIfAbsent(rule.bodyLimit(), limit -> BodyHandler.create(false).setBodyLimit(limit)).handle(ctx);
            case STREAM -> ctx.next();
        }
    }

    /**
     * Checks the request against its operation in the contract before the handler runs (D-046). JSON bodies are
     * validated as JSON whatever Content-Type the client sent (curl's -d sends a form type); streamed bodies aren't.
     */
    private void validate(RoutingContext ctx) {
        RouteScopes.Rule rule = ctx.get("rule");
        io.vertx.openapi.contract.Operation operation = contract.operation(rule.operationId());
        io.vertx.openapi.validation.RequestUtils.extract(ctx.request(), operation, () -> Future.succeededFuture(Buffer.buffer()))
                .map(extracted -> rule.body() != RouteScopes.Body.JSON ? extracted : asJson(extracted, ctx.body().buffer()))
                .compose(request -> validator.validate(request, rule.operationId()))
                .onSuccess(validated -> ctx.next())
                .onFailure(err -> {
                    if (err instanceof io.vertx.openapi.validation.SchemaValidationException invalid) {
                        error(ctx, 400, "invalid_request", describe(invalid));
                    } else if (err instanceof io.vertx.openapi.validation.ValidatorException invalid) {
                        error(ctx, 400, "invalid_request", invalid.getMessage());
                    } else {
                        ctx.fail(err);
                    }
                });
    }

    /** The request as JSON, whatever Content-Type it came with; an empty body is no body. */
    private static io.vertx.openapi.validation.ValidatableRequest asJson(io.vertx.openapi.validation.ValidatableRequest extracted, Buffer body) {
        boolean empty = body == null || body.length() == 0;
        return new io.vertx.openapi.validation.impl.ValidatableRequestImpl(extracted.getCookies(), extracted.getHeaders(),
                extracted.getPathParameters(), extracted.getQuery(),
                new io.vertx.openapi.validation.impl.RequestParameterImpl(empty ? null : body), empty ? null : "application/json");
    }

    private static String describe(io.vertx.openapi.validation.SchemaValidationException invalid) {
        io.vertx.json.schema.OutputUnit unit = invalid.getOutputUnit();
        String where = unit == null || unit.getInstanceLocation() == null || unit.getInstanceLocation().isEmpty()
                ? "" : " at " + unit.getInstanceLocation();
        return invalid.getMessage() + where;
    }

    private static String denied(RouteScopes.Rule rule, automately.core.auth.Principal principal) {
        return switch (rule.who()) {
            case PUBLIC, ANY -> null;
            case ADMIN -> principal.admin() ? null : "Only the admin token can do this";
            case ADMIN_OR_TENANT -> principal.admin() || principal.allows(rule.scope()) ? null : lacks(rule.scope());
            case TENANT -> principal.admin()
                    ? "The admin token manages tenants and tokens only; use a tenant token for tenant data (D-044)"
                    : principal.allows(rule.scope()) ? null : lacks(rule.scope());
        };
    }

    private static String lacks(String scope) {
        return "This token doesn't have the \"" + scope + "\" scope";
    }

    /** A tenant can be deleted once nothing of it is left: no services, no files (wherever they are), no kv. */
    private Future<Boolean> isEmpty(String tenant) {
        return services.list(tenant).compose(owned -> !owned.isEmpty()
                ? Future.succeededFuture(false)
                : files.list(tenant, "", 1, null).compose(listed -> !listed.getJsonArray("files").isEmpty()
                        ? Future.succeededFuture(false)
                        : quota.remaining(tenant).map(left -> left == quota.bytes())));
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

            // Subscribe first, then look again: a job that ended in between is replayed from the store, and a running
            // one starts with the lines it has logged so far, from the node running it.
            consumer.completion()
                    .compose(v -> jobs.get(found.id()))
                    .onSuccess(job -> {
                        if (job == null || job.status().isTerminal()) {
                            stream.start(job, null);
                            return;
                        }
                        vertx.eventBus().<JsonObject>request(Addresses.jobTail(job.id()), null,
                                        new io.vertx.core.eventbus.DeliveryOptions().setSendTimeout(TAIL_TIMEOUT.toMillis()))
                                .onComplete(tail -> stream.start(job, tail.succeeded() ? tail.result().body() : null));
                    })
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
        if (ctx.response().ended()) {
            return;
        }
        automately.core.http.EarlyAnswer.dropEntityHeaders(ctx.response());
        // Answering before the body was read (a refused upload): drain it so the client sees this answer (D-039).
        boolean unread = automately.core.http.EarlyAnswer.prepare(ctx.request());
        if (unread) {
            ctx.response().putHeader("Connection", "close");
        }
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", new JsonObject().put("code", code).put("message", message)).encode());
    }

    /** Buffers live events until it knows whether the job is still running or must be replayed. */
    /** How long a new event stream waits for a running job's recent lines before going live without them. */
    static final Duration TAIL_TIMEOUT = Duration.ofSeconds(2);

    private static final class EventStream {

        private final HttpServerResponse response;
        private final List<JsonObject> pending = new ArrayList<>();
        private Runnable onEnd = () -> {
        };
        private boolean started;
        private boolean ended;
        /** Log lines up to this number were sent from the tail; live copies of them are skipped. */
        private long sentUpTo;

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

        void start(Job job, JsonObject tail) {
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
            if (tail != null) {
                JsonArray lines = tail.getJsonArray("lines", new JsonArray());
                long first = tail.getLong("lastSeq", 0L) - lines.size() + 1;
                for (int i = 0; i < lines.size(); i++) {
                    write(new JsonObject().put("type", "log").put("level", "info").put("line", lines.getString(i)).put("seq", first + i));
                }
                sentUpTo = tail.getLong("lastSeq", 0L);
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
            Long seq = event.getLong("seq");
            if ("log".equals(event.getString("type")) && seq != null && seq <= sentUpTo) {
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
