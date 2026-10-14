package cluster93.server.api;

import cluster93.core.auth.Principal;
import cluster93.core.packages.PackageStore;
import cluster93.core.runtime.HostException;
import cluster93.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayList;
import java.util.List;

import static cluster93.server.api.ApiVerticle.error;

/**
 * {@code /v1/packages}: publishing and reading packages (D-083). A tenant publishes its own; operators publish the
 * cluster's, under {@code @cluster/}. Publishing is three steps, so only new files travel: ask which files are
 * missing, upload those, then commit the version's manifest.
 */
final class PackagesRoutes {

    private final PackageStore packages;
    private final TenantQuota quota;
    private final cluster93.core.service.ServiceStore services;
    private final cluster93.core.scheduler.JobScheduler scheduler;

    PackagesRoutes(PackageStore packages, TenantQuota quota, cluster93.core.service.ServiceStore services,
                   cluster93.core.scheduler.JobScheduler scheduler) {
        this.packages = packages;
        this.quota = quota;
        this.services = services;
        this.scheduler = scheduler;
    }

    void mount(Router router) {
        router.get("/v1/packages").handler(this::list);
        router.post("/v1/packages").handler(this::publish);
        router.post("/v1/packages/blobs/missing").handler(this::missing);
        router.put("/v1/packages/blobs/:hash").handler(this::upload);
        router.get("/v1/packages/:name").handler(this::info);
        router.get("/v1/packages/:name/versions/:version").handler(this::version);
        String one = "/v1/packages/:name/versions/:version/";
        router.post(one + "yank").handler(ctx -> lifecycle(ctx, (owner, by) -> packages.yank(owner, name(ctx), ver(ctx), true, by), false));
        router.post(one + "unyank").handler(ctx -> lifecycle(ctx, (owner, by) -> packages.yank(owner, name(ctx), ver(ctx), false, by), false));
        router.post(one + "block").handler(ctx -> lifecycle(ctx, (owner, by) -> packages.block(owner, name(ctx), ver(ctx), reason(ctx), by), true));
        router.post(one + "unblock").handler(ctx -> lifecycle(ctx, (owner, by) -> packages.unblock(owner, name(ctx), ver(ctx)), false));
        router.post(one + "purge").handler(ctx -> lifecycle(ctx, (owner, by) -> packages.purge(owner, name(ctx), ver(ctx), reason(ctx), by), true));
    }

    /** The tenant a caller acts for, or null for operators. */
    private static String tenant(RoutingContext ctx) {
        return ctx.get("tenant");
    }

    /** Who a caller publishes as: its tenant, or the cluster for operators. */
    private static String owner(RoutingContext ctx) {
        String tenant = tenant(ctx);
        return tenant != null ? tenant : PackageStore.CLUSTER;
    }

    private void list(RoutingContext ctx) {
        packages.list(tenant(ctx)).onSuccess(all -> ctx.json(new JsonObject().put("packages", new JsonArray(all))))
                .onFailure(err -> fail(ctx, err));
    }

    private void info(RoutingContext ctx) {
        String name = ctx.pathParam("name");
        packages.info(tenant(ctx), name).onSuccess(found -> {
            if (found == null) {
                notFound(ctx, name);
            } else {
                ctx.json(found);
            }
        }).onFailure(err -> fail(ctx, err));
    }

    private void version(RoutingContext ctx) {
        String name = ctx.pathParam("name");
        String version = ctx.pathParam("version");
        packages.version(tenant(ctx), name, version).onSuccess(found -> {
            if (found == null) {
                error(ctx, 404, "not_found", "No version " + version + " of " + name);
            } else {
                ctx.json(found);
            }
        }).onFailure(err -> fail(ctx, err));
    }

    private static String name(RoutingContext ctx) {
        return ctx.pathParam("name");
    }

    private static String ver(RoutingContext ctx) {
        return ctx.pathParam("version");
    }

    private static String reason(RoutingContext ctx) {
        JsonObject body = body(ctx);
        return body != null && body.getValue("reason") instanceof String r ? r : null;
    }

    /**
     * Yank, block, unblock or purge one version (D-092). A tenant manages its own packages; an operator manages the
     * cluster's, and any tenant's with {@code ?tenant=<id>} (to block or purge one that's dangerous). Blocking and
     * purging stop the services running the version, and the answer lists them.
     */
    private void lifecycle(RoutingContext ctx, java.util.function.BiFunction<String, String, Future<JsonObject>> change, boolean stopsServices) {
        String name = name(ctx);
        String owner = tenant(ctx);
        if (owner == null) {
            String named = ctx.request().getParam("tenant");
            owner = name.startsWith(PackageStore.CLUSTER_PREFIX) ? PackageStore.CLUSTER : named;
            if (owner == null) {
                error(ctx, 400, "invalid_request", "Name the tenant whose package this is: ?tenant=<id>");
                return;
            }
        }
        Principal principal = ctx.get("principal");
        String packageOwner = owner;
        change.apply(owner, principal.actor()).compose(changed -> !stopsServices ? Future.succeededFuture(changed)
                        : stopServices(packageOwner, name, ver(ctx), changed).map(stopped -> changed.put("stoppedServices", new JsonArray(stopped))))
                .onSuccess(ctx::json)
                .onFailure(err -> fail(ctx, err));
    }

    /**
     * Stops every enabled service whose revision runs {@code name@version}: disabled, so it doesn't restart into the
     * same code, and its current run stopped with the reason. A cluster package's services can be any tenant's.
     */
    private Future<List<String>> stopServices(String owner, String name, String version, JsonObject changed) {
        String why = PackageStore.unloadable(changed.copy().put("name", name).put("version", version));
        Future<List<cluster93.core.service.ServiceDefinition>> candidates = PackageStore.CLUSTER.equals(owner) ? services.all() : services.list(owner);
        return candidates.compose(all -> {
            List<Future<String>> stopping = new ArrayList<>();
            for (cluster93.core.service.ServiceDefinition service : all) {
                if (!service.enabled() || service.packages() == null || !version.equals(service.packages().getString(name))) {
                    continue;
                }
                stopping.add(services.update(service.tenant(), service.name(), current -> java.util.Optional.of(current.withEnabled(false, System.currentTimeMillis())))
                        .compose(updated -> {
                            String job = updated.map(cluster93.core.service.ServiceDefinition::currentJobId).orElse(null);
                            return job == null ? Future.succeededFuture() : scheduler.cancel(job, why).mapEmpty();
                        })
                        .map(v -> service.tenant() + "/" + service.name()));
            }
            return Future.all(stopping).map(done -> stopping.stream().map(Future::result).toList());
        });
    }

    /** {@code {sha256: [...]}} → {@code {missing: [...]}}. */
    private void missing(RoutingContext ctx) {
        JsonObject body = body(ctx);
        JsonArray hashes = body == null ? null : body.getJsonArray("sha256");
        if (hashes == null) {
            error(ctx, 400, "invalid_request", "The body must be {sha256: [hashes]}");
            return;
        }
        List<String> wanted = new ArrayList<>();
        hashes.forEach(h -> wanted.add(String.valueOf(h)));
        packages.missing(owner(ctx), wanted).onSuccess(missing -> ctx.json(new JsonObject().put("missing", new JsonArray(missing))))
                .onFailure(err -> fail(ctx, err));
    }

    /**
     * One file, streamed and hashed as it arrives. A file the owner already has is answered 200 without storing it
     * again; a tenant's files count against its quota (D-085), the cluster's don't.
     */
    private void upload(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        request.pause();
        String hash = ctx.pathParam("hash");
        String owner = owner(ctx);
        String length = request.getHeader("Content-Length");
        long declared;
        try {
            declared = length == null ? 0 : Long.parseLong(length.trim());
        } catch (NumberFormatException e) {
            error(ctx, 400, "invalid_request", "Content-Length must be a number");
            return;
        }
        if (declared > PackageStore.MAX_BYTES) {
            error(ctx, 413, "limit_exceeded", "A package file can be at most " + PackageStore.MAX_BYTES + " bytes");
            return;
        }
        Future<Long> allowance = PackageStore.CLUSTER.equals(owner) ? Future.succeededFuture(PackageStore.MAX_BYTES) : quota.remaining(owner);
        packages.file(owner, hash).compose(had -> {
            if (had != null) {
                // Nothing to store: the body is read and dropped, so the connection stays usable.
                request.handler(chunk -> { });
                request.endHandler(v -> ctx.json(had.put("created", false)));
                request.resume();
                return Future.succeededFuture();
            }
            return allowance.compose(left -> {
                if (left < declared || left <= 0) {
                    return Future.failedFuture(TenantQuota.exceeded(quota.bytes()));
                }
                return packages.upload(owner, hash, left);
            }).onSuccess(upload -> stream(ctx, owner, upload)).mapEmpty();
        }).onFailure(err -> fail(ctx, err));
    }

    private void stream(RoutingContext ctx, String owner, PackageStore.Upload upload) {
        HttpServerRequest request = ctx.request();
        boolean[] failed = {false};
        request.handler(chunk -> {
            request.pause();
            upload.write(chunk).onComplete(ar -> {
                if (ar.succeeded()) {
                    request.resume();
                } else if (!failed[0]) {
                    failed[0] = true;
                    upload.abort();
                    fail(ctx, ar.cause());
                }
            });
        });
        request.endHandler(v -> {
            if (failed[0]) {
                return;
            }
            upload.finish().onSuccess(stored -> {
                if (stored.getBoolean("created") && !PackageStore.CLUSTER.equals(owner)) {
                    quota.added(owner, stored.getLong("size"));
                }
                ctx.response().setStatusCode(stored.getBoolean("created") ? 201 : 200).putHeader("Content-Type", "application/json")
                        .end(stored.encode());
            }).onFailure(err -> fail(ctx, err));
        });
        request.exceptionHandler(err -> upload.abort());
        request.resume();
    }

    /** Commits a version once its files are uploaded. */
    private void publish(RoutingContext ctx) {
        JsonObject manifest = body(ctx);
        Principal principal = ctx.get("principal");
        packages.publish(owner(ctx), manifest, principal.maker(), principal.actor())
                .onSuccess(published -> ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json")
                        .end(published.encode()))
                .onFailure(err -> fail(ctx, err));
    }

    private static JsonObject body(RoutingContext ctx) {
        try {
            return ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void notFound(RoutingContext ctx, String name) {
        error(ctx, 404, "not_found", "No package named \"" + name + "\"");
    }

    private static void fail(RoutingContext ctx, Throwable err) {
        if (ctx.response().ended()) {
            return;
        }
        if (err instanceof HostException host) {
            switch (host.code()) {
                case "forbidden" -> error(ctx, 403, host.code(), host.getMessage());
                case "not_found" -> error(ctx, 404, host.code(), host.getMessage());
                case "already_exists", "missing_files", "unmet_dependency", "purged", "already_purged" -> error(ctx, 409, host.code(), host.getMessage());
                case "limit_exceeded" -> error(ctx, 413, host.code(), host.getMessage());
                case "insufficient_storage" -> error(ctx, 507, host.code(), host.getMessage());
                case "invalid_argument" -> error(ctx, 400, "invalid_request", host.getMessage());
                default -> error(ctx, 400, host.code(), host.getMessage());
            }
            return;
        }
        ctx.fail(err);
    }
}
