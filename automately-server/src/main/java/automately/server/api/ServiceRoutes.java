package automately.server.api;

import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.scheduler.JobScheduler;
import automately.core.service.RestartPolicy;
import automately.core.service.ServiceDefinition;
import automately.core.service.ServiceStore;
import automately.server.ServerConfig;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static automately.server.api.ApiVerticle.error;

/** {@code /v1/services}: define, inspect, start, stop, restart and delete services (docs/design/services.md). */
final class ServiceRoutes {

    private static final String NAME_PATTERN = "[a-z0-9][a-z0-9_-]{0,62}";

    private final ServerConfig config;
    private final ServiceStore services;
    private final JobStore jobs;
    private final JobScheduler scheduler;

    ServiceRoutes(ServerConfig config, ServiceStore services, JobStore jobs, JobScheduler scheduler) {
        this.config = config;
        this.services = services;
        this.jobs = jobs;
        this.scheduler = scheduler;
    }

    void mount(Router router, BodyHandler body) {
        router.put("/v1/services/:name").handler(body).handler(this::put);
        router.get("/v1/services").handler(this::list);
        router.get("/v1/services/:name").handler(ctx -> withService(ctx, service -> respond(ctx, 200, service)));
        router.post("/v1/services/:name/start").handler(this::start);
        router.post("/v1/services/:name/stop").handler(this::stop);
        router.post("/v1/services/:name/restart").handler(this::restart);
        router.delete("/v1/services/:name").handler(this::delete);
    }

    private void put(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        String name = ctx.pathParam("name");
        if (!name.matches(NAME_PATTERN)) {
            error(ctx, 400, "invalid_request", "A service name must match " + NAME_PATTERN);
            return;
        }
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
        if (script.length() > ApiVerticle.MAX_SCRIPT_BYTES) {
            error(ctx, 413, "script_too_large", "\"script\" is limited to " + ApiVerticle.MAX_SCRIPT_BYTES + " bytes");
            return;
        }
        Object configValue = body.getValue("config");
        if (configValue != null && !(configValue instanceof JsonObject)) {
            error(ctx, 400, "invalid_request", "\"config\" must be a JSON object");
            return;
        }
        RestartPolicy restart = body.getValue("restart", "always") instanceof String r ? RestartPolicy.fromApi(r) : null;
        if (restart == null) {
            error(ctx, 400, "invalid_request", "\"restart\" must be \"always\" or \"on-failure\"");
            return;
        }
        Object enabledValue = body.getValue("enabled", true);
        if (!(enabledValue instanceof Boolean enabled)) {
            error(ctx, 400, "invalid_request", "\"enabled\" must be true or false");
            return;
        }
        JsonObject serviceConfig = configValue == null ? new JsonObject() : (JsonObject) configValue;

        services.get(tenant, name).compose(existing -> withinLimit(tenant, name, enabled && (existing == null || !existing.enabled()))
                        .compose(allowed -> {
                            if (!allowed) {
                                return Future.succeededFuture((Runnable) () -> limitReached(ctx));
                            }
                            long now = System.currentTimeMillis();
                            if (existing == null) {
                                ServiceDefinition created = ServiceDefinition.create(tenant, name, script, serviceConfig, restart, enabled, now);
                                return services.create(created).map(ok -> ok
                                        ? () -> respond(ctx, 201, created)
                                        : () -> error(ctx, 409, "conflict", "The service was created concurrently; retry"));
                            }
                            return services.update(tenant, name, current -> Optional.of(
                                            current.redefined(script, serviceConfig, restart, now).withEnabled(enabled, now)))
                                    .map(updated -> updated.<Runnable>map(s -> () -> respond(ctx, 200, s))
                                            .orElse(() -> error(ctx, 404, "not_found", "No such service")));
                        }))
                .onSuccess(Runnable::run)
                .onFailure(ctx::fail);
    }

    private void list(RoutingContext ctx) {
        services.list(ctx.get("tenant"))
                .compose(all -> {
                    List<Future<JsonObject>> views = new ArrayList<>();
                    all.forEach(service -> views.add(view(service)));
                    return Future.all(views).map(done -> new JsonArray(views.stream().map(Future::result).toList()));
                })
                .onSuccess(array -> ctx.json(new JsonObject().put("services", array)))
                .onFailure(ctx::fail);
    }

    private void start(RoutingContext ctx) {
        withService(ctx, service -> withinLimit(service.tenant(), service.name(), !service.enabled() || service.completed())
                .onSuccess(allowed -> {
                    if (!allowed) {
                        limitReached(ctx);
                        return;
                    }
                    setEnabled(ctx, service, true);
                })
                .onFailure(ctx::fail));
    }

    private void stop(RoutingContext ctx) {
        withService(ctx, service -> setEnabled(ctx, service, false));
    }

    private void setEnabled(RoutingContext ctx, ServiceDefinition service, boolean enabled) {
        services.update(service.tenant(), service.name(), current -> Optional.of(current.withEnabled(enabled, System.currentTimeMillis())))
                .onSuccess(updated -> updated.ifPresentOrElse(s -> respond(ctx, 200, s), () -> error(ctx, 404, "not_found", "No such service")))
                .onFailure(ctx::fail);
    }

    /** Stops the current run without counting it as a failure; the supervisor starts the next one. */
    private void restart(RoutingContext ctx) {
        withService(ctx, service -> {
            if (!service.enabled() || service.completed()) {
                start(ctx);
                return;
            }
            Future<?> stopping = service.currentJobId() == null
                    ? Future.succeededFuture()
                    : scheduler.cancel(service.currentJobId(), "service " + service.name() + " restarted");
            stopping.compose(v -> services.get(service.tenant(), service.name()))
                    .onSuccess(current -> respond(ctx, 202, current))
                    .onFailure(ctx::fail);
        });
    }

    private void delete(RoutingContext ctx) {
        withService(ctx, service -> {
            Future<?> stopping = service.currentJobId() == null
                    ? Future.succeededFuture()
                    : scheduler.cancel(service.currentJobId(), "service " + service.name() + " deleted");
            stopping.compose(v -> services.delete(service.tenant(), service.name()))
                    .onSuccess(v -> ctx.response().setStatusCode(204).end())
                    .onFailure(ctx::fail);
        });
    }

    private void withService(RoutingContext ctx, java.util.function.Consumer<ServiceDefinition> then) {
        services.get(ctx.get("tenant"), ctx.pathParam("name"))
                .onSuccess(service -> {
                    if (service == null) {
                        error(ctx, 404, "not_found", "No such service");
                    } else {
                        then.accept(service);
                    }
                })
                .onFailure(ctx::fail);
    }

    /** True if enabling one more service (other than {@code name}) keeps the tenant within its limit. */
    private Future<Boolean> withinLimit(String tenant, String name, boolean enabling) {
        if (!enabling) {
            return Future.succeededFuture(true);
        }
        return services.list(tenant).map(all -> all.stream()
                .filter(s -> !s.name().equals(name) && s.enabled() && !s.completed())
                .count() < config.maxServicesPerTenant());
    }

    private void limitReached(RoutingContext ctx) {
        error(ctx, 409, "limit_reached", "A tenant can have at most " + config.maxServicesPerTenant() + " enabled services");
    }

    private void respond(RoutingContext ctx, int status, ServiceDefinition service) {
        view(service).onSuccess(json -> ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(json.encode()))
                .onFailure(ctx::fail);
    }

    /** The definition plus its status, which depends on the current run's job. */
    private Future<JsonObject> view(ServiceDefinition service) {
        Future<Job> current = service.currentJobId() == null ? Future.succeededFuture() : jobs.get(service.currentJobId());
        return current.map(job -> {
            JsonObject json = service.toJson();
            json.remove("tenant");
            json.remove("currentSince");
            json.put("status", service.status(job, System.currentTimeMillis()).name());
            return json;
        });
    }
}
