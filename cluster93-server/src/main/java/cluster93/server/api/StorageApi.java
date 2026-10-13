package cluster93.server.api;

import cluster93.core.auth.AuthStore;
import cluster93.core.files.FileStore;
import cluster93.core.storage.SecretBox;
import cluster93.core.storage.StorageConfigStore;
import cluster93.core.storage.StoragePolicy;
import cluster93.core.storage.StorageProvider;
import cluster93.core.storage.StorageRoutes;
import cluster93.core.storage.TenantBlobStores;
import cluster93.core.storage.TenantQuota;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static cluster93.server.api.ApiVerticle.error;

/**
 * Tenants' own storage (D-057): the admin's policy per tenant, and each tenant's providers and routes. Secrets are
 * write-only: sealed on the way in, never returned. A provider is checked against its backend before it's saved.
 */
public final class StorageApi {

    /** What the storage API needs from the node. */
    public record Wiring(StorageConfigStore config, TenantBlobStores stores, SecretBox secrets, Path diskRoot, String defaultBackend) {
    }

    private final Wiring wiring;
    private final FileStore files;
    private final AuthStore auth;
    private final TenantQuota quota;
    private final SecureRandom random = new SecureRandom();

    StorageApi(Wiring wiring, FileStore files, AuthStore auth, TenantQuota quota) {
        this.wiring = wiring;
        this.files = files;
        this.auth = auth;
        this.quota = quota;
    }

    void mount(Router router) {
        router.get("/v1/tenants/:tenant/storage-policy").handler(this::getPolicy);
        router.put("/v1/tenants/:tenant/storage-policy").handler(this::putPolicy);
        router.get("/v1/storage").handler(this::overview);
        router.get("/v1/storage/providers").handler(ctx -> wiring.config().providers(ctx.get("tenant")).onFailure(ctx::fail)
                .onSuccess(all -> ctx.json(new JsonObject().put("providers", new JsonArray(all.stream().map(StorageProvider::toViewJson).toList())))));
        router.get("/v1/storage/providers/:name").handler(ctx -> withProvider(ctx, p -> ctx.json(p.toViewJson())));
        router.put("/v1/storage/providers/:name").handler(this::putProvider);
        router.delete("/v1/storage/providers/:name").handler(this::deleteProvider);
        router.post("/v1/storage/providers/:name/check").handler(this::checkProvider);
        router.get("/v1/storage/routes").handler(ctx -> wiring.config().routes(ctx.get("tenant")).onFailure(ctx::fail)
                .onSuccess(routes -> ctx.json(view(routes))));
        router.put("/v1/storage/routes").handler(this::putRoutes);
        router.get("/v1/mounts").handler(this::listMounts);
        router.putWithRegex("/v1/mounts/.+").handler(this::putMount);
        router.deleteWithRegex("/v1/mounts/.+").handler(this::deleteMount);
    }

    // --- admin ---

    private void getPolicy(RoutingContext ctx) {
        String tenant = ctx.pathParam("tenant");
        auth.tenant(tenant).onFailure(ctx::fail).onSuccess(found -> {
            if (found == null) {
                error(ctx, 404, "not_found", "No such tenant");
            } else {
                wiring.config().policy(tenant).onFailure(ctx::fail).onSuccess(policy -> ctx.json(policy.toJson()));
            }
        });
    }

    private void putPolicy(RoutingContext ctx) {
        String tenant = ctx.pathParam("tenant");
        JsonObject body = ctx.body().asJsonObject();
        Set<String> allow = new LinkedHashSet<>();
        for (Object type : body.getJsonArray("allow", new JsonArray())) {
            allow.add(String.valueOf(type));
        }
        StoragePolicy policy = new StoragePolicy(tenant, Set.copyOf(allow), body.getLong("diskAllotmentMb", 0L) << 20,
                body.getBoolean("allowPrivateEndpoints", false));
        auth.tenant(tenant).onFailure(ctx::fail).onSuccess(found -> {
            if (found == null) {
                error(ctx, 404, "not_found", "No such tenant");
                return;
            }
            wiring.config().putPolicy(policy).onFailure(ctx::fail).onSuccess(v -> ctx.json(policy.toJson()));
        });
    }

    // --- tenants ---

    private void overview(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        Future.all(wiring.config().policy(tenant), wiring.config().routes(tenant), wiring.config().providers(tenant), quota.remaining(tenant))
                .compose(all -> {
                    StoragePolicy policy = all.resultAt(0);
                    java.util.List<StorageProvider> owned = all.resultAt(2);
                    long left = all.resultAt(3);
                    return files.usageIn(tenant, owned.stream().filter(p -> StorageProvider.DISK.equals(p.type())).map(StorageProvider::backend).toList())
                            .map(diskUsed -> new JsonObject()
                                    .put("defaultBackend", wiring.defaultBackend())
                                    .put("policy", policy.toJson().copy())
                                    .put("routes", view(all.resultAt(1)))
                                    .put("providers", owned.size())
                                    .put("quota", new JsonObject().put("bytes", quota.bytes()).put("usedBytes", quota.bytes() - left))
                                    .put("disk", new JsonObject().put("allotmentBytes", policy.diskAllotmentBytes()).put("usedBytes", diskUsed)));
                })
                .onFailure(ctx::fail).onSuccess(ctx::json);
    }

    private void withProvider(RoutingContext ctx, java.util.function.Consumer<StorageProvider> then) {
        wiring.config().provider(ctx.get("tenant"), ctx.pathParam("name")).onFailure(ctx::fail).onSuccess(p -> {
            if (p == null) {
                error(ctx, 404, "not_found", "No such storage provider");
            } else {
                then.accept(p);
            }
        });
    }

    private void putProvider(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        String name = ctx.pathParam("name");
        if (!Names.isValid(name) || name.equals(StorageRoutes.DEFAULT)) {
            error(ctx, 400, "invalid_request", "A provider name must be " + Names.RULE + ", and not \"default\"");
            return;
        }
        JsonObject body = ctx.body().asJsonObject();
        String type = body.getString("type");
        Future.all(wiring.config().policy(tenant), wiring.config().provider(tenant, name)).onFailure(ctx::fail).onSuccess(both -> {
            StoragePolicy policy = both.resultAt(0);
            StorageProvider existing = both.resultAt(1);
            if (!policy.allows(type)) {
                error(ctx, 403, "storage_disabled", "The admin hasn't enabled " + type + " storage providers for this tenant");
                return;
            }
            if (existing != null && !existing.type().equals(type)) {
                error(ctx, 409, "conflict", "Provider " + name + " is " + existing.type() + "; delete it to make a " + type + " one");
                return;
            }
            StorageProvider candidate;
            try {
                candidate = build(tenant, name, type, body, existing, policy);
            } catch (HostException e) {
                error(ctx, e.code().equals("storage_unavailable") ? 409 : 400, e.code(), e.getMessage());
                return;
            }
            // Checked against the real backend before it's saved: a provider that can't be used is never stored.
            wiring.stores().store(candidate, policy).compose(store -> store.check())
                    .onFailure(err -> {
                        if (err instanceof HostException host && host.code().equals("storage_unavailable")) {
                            // The cluster's setup, not the tenant's settings (no secrets key, no shared disk root).
                            error(ctx, 409, host.code(), host.getMessage());
                        } else {
                            error(ctx, 400, "provider_check_failed", err.getMessage());
                        }
                    })
                    .onSuccess(ok -> wiring.config().putProvider(candidate).onFailure(ctx::fail).onSuccess(v -> {
                        ctx.response().setStatusCode(existing == null ? 201 : 200);
                        ctx.json(candidate.toViewJson());
                    }));
        });
    }

    private StorageProvider build(String tenant, String name, String type, JsonObject body, StorageProvider existing, StoragePolicy policy) {
        long now = System.currentTimeMillis();
        String id = existing != null ? existing.id() : newId();
        long created = existing != null ? existing.createdAt() : now;
        if (StorageProvider.DISK.equals(type)) {
            if (wiring.diskRoot() == null) {
                throw new HostException("Error", "storage_unavailable", "This cluster has no C93_DISK_ROOT for disk providers");
            }
            if (policy.diskAllotmentBytes() <= 0) {
                throw new HostException("Error", "storage_unavailable", "The admin's storage policy gives this tenant no disk allotment");
            }
            JsonObject settings = new JsonObject().put("folder", wiring.stores().diskFolder(tenant, name).toString());
            return new StorageProvider(id, tenant, name, type, settings, null, created, now);
        }
        if (wiring.secrets() == null) {
            throw new HostException("Error", "storage_unavailable", "Tenant S3 providers need C93_SECRETS_KEY on every node");
        }
        String endpoint = body.getString("endpoint");
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException e) {
            throw HostException.invalid("\"endpoint\" must be a URL like https://s3.eu-west-1.amazonaws.com");
        }
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw HostException.invalid("\"endpoint\" must be an http(s) URL like https://s3.eu-west-1.amazonaws.com");
        }
        String bucket = body.getString("bucket");
        String accessKeyId = body.getString("accessKeyId");
        if (bucket == null || bucket.isBlank() || accessKeyId == null || accessKeyId.isBlank()) {
            throw HostException.invalid("\"bucket\" and \"accessKeyId\" are required");
        }
        String secret = body.getString("secretAccessKey");
        String sealed;
        if (secret != null && !secret.isBlank()) {
            sealed = wiring.secrets().seal(secret, TenantBlobStores.secretContext(tenant, id));
        } else if (existing != null && existing.sealedSecret() != null) {
            sealed = existing.sealedSecret();
        } else {
            throw HostException.invalid("\"secretAccessKey\" is required for a new S3 provider");
        }
        JsonObject settings = new JsonObject().put("endpoint", endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint)
                .put("region", body.getString("region", "us-east-1")).put("bucket", bucket)
                .put("prefix", body.getString("prefix", "cluster93/")).put("pathStyle", body.getBoolean("pathStyle", true))
                .put("accessKeyId", accessKeyId);
        return new StorageProvider(id, tenant, name, type, settings, sealed, created, now);
    }

    private String newId() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return "prv_" + HexFormat.of().formatHex(bytes);
    }

    private void deleteProvider(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        withProvider(ctx, provider -> Future.all(files.countIn(provider.backend()), wiring.config().routes(tenant)).onFailure(ctx::fail).onSuccess(both -> {
            long count = both.resultAt(0);
            StorageRoutes routes = both.resultAt(1);
            if (count > 0) {
                error(ctx, 409, "conflict", count + " file(s) are stored in " + provider.name() + "; delete them first");
                return;
            }
            if (provider.name().equals(routes.defaultProvider()) || routes.prefixes().containsValue(provider.name())) {
                error(ctx, 409, "conflict", "The storage routes still send files to " + provider.name() + "; change them first");
                return;
            }
            wiring.config().deleteProvider(tenant, provider.name()).onFailure(ctx::fail).onSuccess(v -> ctx.response().setStatusCode(204).end());
        }));
    }

    private void checkProvider(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        withProvider(ctx, provider -> wiring.config().policy(tenant)
                .compose(policy -> wiring.stores().store(provider, policy))
                .compose(store -> store.check())
                .onComplete(ar -> ctx.json(ar.succeeded() ? new JsonObject().put("ok", true)
                        : new JsonObject().put("ok", false).put("error", ar.cause().getMessage()))));
    }

    private void putRoutes(RoutingContext ctx) {
        String tenant = ctx.get("tenant");
        JsonObject body = ctx.body().asJsonObject();
        Map<String, String> prefixes = new TreeMap<>();
        for (Map.Entry<String, Object> entry : body.getJsonObject("prefixes", new JsonObject())) {
            String prefix = entry.getKey();
            if (prefix.isEmpty() || prefix.startsWith("/") || prefix.contains("\\") || prefix.contains("..")) {
                error(ctx, 400, "invalid_request", "A route prefix is a relative path start like \"media/\" (no leading slash, no \"..\")");
                return;
            }
            prefixes.put(prefix, String.valueOf(entry.getValue()));
        }
        String defaultProvider = body.getString("default", StorageRoutes.DEFAULT);
        StorageRoutes requested = StorageRoutes.fromJson(new JsonObject().put("tenant", tenant).put("default", defaultProvider)
                .put("prefixes", new JsonObject(new java.util.HashMap<>(prefixes))));
        Set<String> named = new LinkedHashSet<>(prefixes.values());
        named.add(defaultProvider);
        named.remove(StorageRoutes.DEFAULT);
        wiring.config().providers(tenant).onFailure(ctx::fail).onSuccess(owned -> {
            Set<String> known = new java.util.HashSet<>(owned.stream().map(StorageProvider::name).toList());
            for (String provider : named) {
                if (!known.contains(provider)) {
                    error(ctx, 400, "invalid_request", "No storage provider named \"" + provider + "\" (use \"default\" for the cluster's own storage)");
                    return;
                }
            }
            // Mounts that stay keep their read-only flag (D-078).
            wiring.config().routes(tenant).map(current -> new StorageRoutes(tenant, requested.defaultProvider(), requested.prefixes(),
                            current.readOnly().stream().filter(requested.prefixes()::containsKey).collect(java.util.stream.Collectors.toSet())))
                    .compose(routes -> wiring.config().putRoutes(routes).map(routes))
                    .onFailure(ctx::fail).onSuccess(routes -> ctx.json(view(routes)));
        });
    }

    // --- mounts (D-078): a folder bound to a provider; the routes' prefixes ending in "/" ---

    private void listMounts(RoutingContext ctx) {
        wiring.config().routes(ctx.get("tenant")).onFailure(ctx::fail).onSuccess(routes -> {
            io.vertx.core.json.JsonArray mounts = new io.vertx.core.json.JsonArray();
            routes.mounts().forEach((folder, provider) -> mounts.add(new JsonObject().put("path", folder).put("provider", provider)
                    .put("readOnly", routes.readOnly().contains(folder + "/"))));
            ctx.json(new JsonObject().put("default", routes.defaultProvider() == null ? StorageRoutes.DEFAULT : routes.defaultProvider())
                    .put("mounts", mounts));
        });
    }

    /** A folder path from {@code /v1/mounts/…}, slashes around it trimmed; null (after answering 400) if it's not a valid path. */
    private static String mountPath(RoutingContext ctx) {
        String raw = ctx.request().path().substring("/v1/mounts/".length());
        String folder = cluster93.core.files.Paths.directory(java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8));
        String invalid = folder.isEmpty() ? "The top level is the default storage; set it with PUT /v1/storage/routes" : FileStore.invalidPath(folder);
        if (invalid != null) {
            error(ctx, 400, "invalid_path", invalid);
            return null;
        }
        if (cluster93.core.files.BlobStores.RESERVED.contains(folder.split("/")[0])) {
            error(ctx, 400, "reserved", folder.split("/")[0] + "/ is reserved");
            return null;
        }
        return folder;
    }

    /** {@code {provider, readOnly}}: binds the folder to the provider, making the folder if needed. */
    private void putMount(RoutingContext ctx) {
        String folder = mountPath(ctx);
        if (folder == null) {
            return;
        }
        JsonObject body = ctx.body().asJsonObject();
        String provider = body == null ? null : body.getString("provider");
        boolean readOnly = body != null && body.getBoolean("readOnly", false);
        String tenant = ctx.get("tenant");
        Future.all(wiring.config().provider(tenant, provider == null ? "" : provider), wiring.config().policy(tenant), wiring.config().routes(tenant),
                files.statEntry(tenant, folder)).onFailure(ctx::fail).onSuccess(all -> {
            StorageProvider found = all.resultAt(0);
            StoragePolicy policy = all.resultAt(1);
            StorageRoutes routes = all.resultAt(2);
            JsonObject there = all.resultAt(3);
            if (found == null) {
                error(ctx, 400, "invalid_request", "No storage provider named \"" + provider + "\"");
                return;
            }
            if (!policy.allows(found.type())) {
                error(ctx, 403, "storage_disabled", "The admin hasn't enabled " + found.type() + " storage providers for this tenant");
                return;
            }
            if (there != null && "file".equals(there.getString("kind"))) {
                error(ctx, 409, "not_a_directory", folder + " is a file");
                return;
            }
            boolean created = !routes.mounts().containsKey(folder);
            wiring.config().putRoutes(routes.withMount(folder, provider, readOnly))
                    .compose(v -> files.mkdir(tenant, folder, true))
                    .onFailure(err -> FilesRoutes.fail(ctx, err))
                    .onSuccess(v -> {
                        ctx.response().setStatusCode(created ? 201 : 200);
                        ctx.json(new JsonObject().put("path", folder).put("provider", provider).put("readOnly", readOnly));
                    });
        });
    }

    /** Unbinds the folder. Files written through the mount stay readable where they are; new ones go by the other routes. */
    private void deleteMount(RoutingContext ctx) {
        String folder = mountPath(ctx);
        if (folder == null) {
            return;
        }
        String tenant = ctx.get("tenant");
        wiring.config().routes(tenant).onFailure(ctx::fail).onSuccess(routes -> {
            if (!routes.mounts().containsKey(folder)) {
                error(ctx, 404, "not_found", folder + " isn't a mount");
                return;
            }
            wiring.config().putRoutes(routes.withoutMount(folder)).onFailure(ctx::fail).onSuccess(v -> ctx.response().setStatusCode(204).end());
        });
    }

    private static JsonObject view(StorageRoutes routes) {
        JsonObject json = routes.toJson();
        json.remove("tenant");
        return json;
    }
}
