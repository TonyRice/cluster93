package automately.server.api;

import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.scheduler.JobScheduler;
import automately.core.service.RestartPolicy;
import automately.core.service.ServiceDefinition;
import automately.core.service.ServiceStore;
import automately.core.host.Domain;
import automately.core.host.DomainStore;
import automately.core.host.HostRouter;
import automately.core.host.TxtResolver;
import automately.server.ServerConfig;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static automately.server.api.ApiVerticle.error;

/** {@code /v1/services}: define, inspect, start, stop, restart and delete services (docs/design/services.md). */
final class ServiceRoutes {

    private final ServerConfig config;
    private final ServiceStore services;
    private final JobStore jobs;
    private final JobScheduler scheduler;
    private final DomainStore domains;
    private final TxtResolver txt;
    private final HostRouter hostRouter;
    private final automately.core.packages.PackageStore packages;

    ServiceRoutes(ServerConfig config, ServiceStore services, JobStore jobs, JobScheduler scheduler, DomainStore domains, TxtResolver txt,
                  HostRouter hostRouter, automately.core.packages.PackageStore packages) {
        this.packages = packages;
        this.domains = domains;
        this.txt = txt;
        this.hostRouter = hostRouter;
        this.config = config;
        this.services = services;
        this.jobs = jobs;
        this.scheduler = scheduler;
    }

    void mount(Router router) {
        router.put("/v1/services/:name").handler(this::put);
        router.get("/v1/services").handler(this::list);
        router.get("/v1/services/:name").handler(ctx -> withService(ctx, service -> respond(ctx, 200, service)));
        router.post("/v1/services/:name/start").handler(this::start);
        router.post("/v1/services/:name/stop").handler(this::stop);
        router.post("/v1/services/:name/restart").handler(this::restart);
        router.delete("/v1/services/:name").handler(this::delete);
        router.post("/v1/services/:name/domains").handler(this::addDomain);
        router.get("/v1/services/:name/domains").handler(ctx -> withService(ctx, service -> domains.forService(service.tenant(), service.name())
                .onSuccess(list -> ctx.json(new JsonObject().put("domains", new JsonArray(list.stream().map(Domain::toViewJson).toList()))))
                .onFailure(ctx::fail)));
        router.post("/v1/services/:name/domains/:domain/verify").handler(this::verifyDomain);
        router.delete("/v1/services/:name/domains/:domain").handler(ctx -> withService(ctx, service ->
                domains.release(ctx.pathParam("domain").toLowerCase(java.util.Locale.ROOT), service.tenant())
                        .onSuccess(released -> {
                            if (released) {
                                ctx.response().setStatusCode(204).end();
                            } else {
                                error(ctx, 404, "not_found", "No such domain");
                            }
                        })
                        .onFailure(ctx::fail)));
    }

    /** Claims a custom domain for the service; it routes once {@code verify} finds the TXT record. */
    private void addDomain(RoutingContext ctx) {
        withService(ctx, service -> {
            JsonObject body;
            try {
                body = ctx.body().asJsonObject();
            } catch (RuntimeException e) {
                body = null;
            }
            String domain = body == null || !(body.getValue("domain") instanceof String d) ? null : d.trim().toLowerCase(java.util.Locale.ROOT);
            if (!Names.isValidDomain(domain)) {
                error(ctx, 400, "invalid_request", "\"domain\" must be a hostname such as shop.example.com");
                return;
            }
            if (hostRouter.isUnderIngressDomain(domain)) {
                error(ctx, 400, "invalid_request", "Hosts under the ingress domain are assigned automatically");
                return;
            }
            long now = System.currentTimeMillis();
            Domain claim = new Domain(domain, service.tenant(), service.name(), java.util.UUID.randomUUID().toString().replace("-", ""),
                    false, now, null);
            domains.claim(claim, now)
                    .onSuccess(stored -> stored.ifPresentOrElse(
                            d -> ctx.response().setStatusCode(d == claim ? 201 : 200).putHeader("Content-Type", "application/json")
                                    .end(d.toViewJson().encode()),
                            () -> error(ctx, 409, "domain_taken", "Another tenant has claimed " + domain)))
                    .onFailure(ctx::fail);
        });
    }

    private void verifyDomain(RoutingContext ctx) {
        withService(ctx, service -> {
            String name = ctx.pathParam("domain").toLowerCase(java.util.Locale.ROOT);
            domains.get(name).onSuccess(domain -> {
                if (domain == null || !domain.tenant().equals(service.tenant()) || !domain.service().equals(service.name())) {
                    error(ctx, 404, "not_found", "No such domain");
                    return;
                }
                if (domain.verified()) {
                    ctx.json(domain.toViewJson());
                    return;
                }
                txt.txt(domain.txtName())
                        .compose(records -> records.stream().anyMatch(r -> r.trim().equals(domain.txtValue()))
                                ? domains.markVerified(domain, System.currentTimeMillis())
                                : Future.succeededFuture(java.util.Optional.<Domain>empty()))
                        .onSuccess(verified -> verified.ifPresentOrElse(
                                d -> ctx.json(d.toViewJson()),
                                () -> error(ctx, 409, "not_verified", "No TXT record " + domain.txtName() + " = " + domain.txtValue() + " found yet")))
                        .onFailure(ctx::fail);
            }).onFailure(ctx::fail);
        });
    }

    private void put(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        String name = ctx.pathParam("name");
        if (!Names.isValid(name)) {
            error(ctx, 400, "invalid_request", "A service name must be " + Names.RULE);
            return;
        }
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            error(ctx, 400, "invalid_json", "The request body must be a JSON object");
            return;
        }
        String script = ApiVerticle.script(ctx, body);
        if (script == null) {
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

        // What its script imports is resolved now and kept on the revision; a redeploy resolves again (D-089).
        // A package version is its revision (D-091): the script re-exports it, and the lock pins it.
        ApiVerticle.resolve(packages, tenant, body, script, true).onComplete(locked -> {
            if (locked.failed()) {
                ApiVerticle.refuseImports(ctx, locked.cause());
            } else {
                define(ctx, tenant, name, locked.result().getString("script"), serviceConfig, restart, enabled,
                        locked.result().getJsonObject("packages"));
            }
        });
    }

    private void define(RoutingContext ctx, String tenant, String name, String script, JsonObject serviceConfig, RestartPolicy restart,
                        boolean enabled, JsonObject lock) {
        services.get(tenant, name).compose(existing -> withinLimit(tenant, name, enabled && (existing == null || !existing.enabled()))
                        .compose(allowed -> {
                            if (!allowed) {
                                return Future.succeededFuture((Runnable) () -> limitReached(ctx));
                            }
                            long now = System.currentTimeMillis();
                            // Its runs may read secrets only if whoever defines it may (D-068).
                            boolean secrets = ctx.<automately.core.auth.Principal>get("principal").allows(automately.core.auth.Scopes.SECRETS_USE);
                            if (existing == null) {
                                ServiceDefinition created = ServiceDefinition.create(tenant, name, script, serviceConfig, restart, enabled, secrets, now)
                                        .withPackages(lock);
                                return services.create(created).map(ok -> ok
                                        ? () -> respond(ctx, 201, created)
                                        : () -> error(ctx, 409, "conflict", "The service was created concurrently; retry"));
                            }
                            return services.update(tenant, name, current -> Optional.of(
                                            current.redefined(script, serviceConfig, restart, secrets, lock, now).withEnabled(enabled, now)))
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
            // Release its custom domains too, so a later service with the same name doesn't inherit them.
            stopping.compose(v -> domains.forService(service.tenant(), service.name()))
                    .compose(owned -> Future.all(owned.stream().map(d -> domains.release(d.domain(), service.tenant())).toList()))
                    .compose(v -> services.delete(service.tenant(), service.name()))
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
        Future<List<Domain>> custom = domains.forService(service.tenant(), service.name());
        return Future.all(current, custom).map(done -> {
            JsonObject json = service.toJson();
            json.remove("tenant");
            json.remove("currentSince");
            json.put("status", service.status(current.result(), System.currentTimeMillis()).name());
            JsonArray hosts = new JsonArray().add(hostRouter.automaticHost(service.tenant(), service.name()));
            custom.result().stream().filter(Domain::verified).forEach(d -> hosts.add(d.domain()));
            json.put("hosts", hosts);
            return json;
        });
    }
}
