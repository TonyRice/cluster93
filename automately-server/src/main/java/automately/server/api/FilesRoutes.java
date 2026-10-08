package automately.server.api;

import automately.core.files.FileResponder;
import automately.core.files.FileStore;
import automately.core.files.FileWriter;
import automately.core.host.ServerExchange;
import automately.core.runtime.HostException;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static automately.server.api.ApiVerticle.error;

/**
 * {@code /v1/files}: upload (streamed, never buffered), download (Range, ETag, HEAD), list and delete a tenant's files.
 * The same files scripts use through {@code files.*}; the CLI will use this to deploy static assets.
 */
final class FilesRoutes {

    static final String PREFIX = "/v1/files/";

    private final FileStore files;
    private final TenantQuota quota;
    private final automately.core.files.Room room;
    private final automately.core.files.FileTree tree;
    private final automately.core.files.FileLinks links;

    FilesRoutes(FileStore files, TenantQuota quota, automately.core.files.Room room, automately.core.files.FileTree tree,
                automately.core.files.FileLinks links) {
        this.tree = tree;
        this.links = links;
        this.files = files;
        this.quota = quota;
        this.room = room;
    }

    void mount(Router router) {
        router.get("/v1/dirs").handler(ctx -> listDir(ctx, ""));
        router.getWithRegex("/v1/dirs/.+").handler(ctx -> withDir(ctx, dir -> listDir(ctx, dir)));
        router.putWithRegex("/v1/dirs/.+").handler(ctx -> withDir(ctx, dir -> files.mkdir(ctx.get("tenant"), dir, true)
                .compose(created -> files.statEntry(ctx.get("tenant"), dir).map(entry -> {
                    ctx.response().setStatusCode(created ? 201 : 200);
                    ctx.json(entry);
                    return entry;
                })).onFailure(err -> fail(ctx, err))));
        router.deleteWithRegex("/v1/dirs/.+").handler(ctx -> withDir(ctx, dir -> {
            if ("true".equals(ctx.request().getParam("recursive"))) {
                // Everything in it, as a journaled tree operation (D-077).
                tree.delete(ctx.get("tenant"), dir).onSuccess(result -> ctx.response().setStatusCode(204).end()).onFailure(err -> fail(ctx, err));
                return;
            }
            files.rmdir(ctx.get("tenant"), dir).onSuccess(removed -> {
                if (removed) {
                    ctx.response().setStatusCode(204).end();
                } else {
                    error(ctx, 404, "not_found", "No such directory: " + dir);
                }
            }).onFailure(err -> fail(ctx, err));
        }));
        router.post("/v1/fs/share").handler(this::share);
        router.post("/v1/fs/share/rotate").handler(ctx -> links.rotate(ctx.get("tenant"))
                .onSuccess(v -> ctx.response().setStatusCode(204).end()).onFailure(err -> fail(ctx, err)));
        router.getWithRegex("/v1/shared/.+").handler(this::shared);
        router.headWithRegex("/v1/shared/.+").handler(this::shared);
        router.post("/v1/fs/move").handler(ctx -> transfer(ctx, true));
        router.post("/v1/fs/copy").handler(ctx -> transfer(ctx, false));
        router.get("/v1/files").handler(this::list);
        router.putWithRegex("/v1/files/.+").handler(this::upload);
        router.getWithRegex("/v1/files/.+").handler(this::download);
        router.headWithRegex("/v1/files/.+").handler(this::download);
        router.deleteWithRegex("/v1/files/.+").handler(this::delete);
    }

    private static String path(RoutingContext ctx) {
        String raw = ctx.request().path().substring(PREFIX.length());
        String path = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        String invalid = FileStore.invalidPath(path);
        if (invalid != null) {
            error(ctx, 400, "invalid_path", invalid);
            return null;
        }
        return path;
    }

    /** A directory path from {@code /v1/dirs/…}: slashes around it don't matter, and it follows the file path rules (D-076). */
    private static void withDir(RoutingContext ctx, java.util.function.Consumer<String> then) {
        String raw = ctx.request().path().substring("/v1/dirs/".length());
        String dir = automately.core.files.Paths.directory(URLDecoder.decode(raw, StandardCharsets.UTF_8));
        String invalid = dir.isEmpty() ? null : FileStore.invalidPath(dir);
        if (invalid != null) {
            error(ctx, 400, "invalid_path", invalid);
            return;
        }
        then.accept(dir);
    }

    /**
     * {@code {from, to, overwrite}}: moves or copies a file, or a folder with everything in it (D-077). Only metadata
     * changes; copies share their bytes.
     */
    private void transfer(RoutingContext ctx, boolean move) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        if (body == null) {
            error(ctx, 400, "invalid_json", "The body must be {from, to, overwrite?}");
            return;
        }
        String from = automately.core.files.Paths.directory(body.getString("from"));
        String to = automately.core.files.Paths.directory(body.getString("to"));
        String invalid = FileStore.invalidPath(from) != null ? FileStore.invalidPath(from) : FileStore.invalidPath(to);
        if (invalid != null) {
            error(ctx, 400, "invalid_path", invalid);
            return;
        }
        boolean overwrite = body.getBoolean("overwrite", false);
        String tenant = ctx.get("tenant");
        (move ? tree.move(tenant, from, to, overwrite) : tree.copy(tenant, from, to, overwrite)).onSuccess(ctx::json).onFailure(err -> fail(ctx, err));
    }

    /** {@code {path, expiresInSeconds}}: a public link to the file's current version (D-080). */
    private void share(RoutingContext ctx) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        String path = body == null ? null : automately.core.files.Paths.directory(body.getString("path"));
        String invalid = path == null || path.isEmpty() ? "\"path\" is required" : FileStore.invalidPath(path);
        if (invalid != null) {
            error(ctx, 400, "invalid_path", invalid);
            return;
        }
        long seconds = body.getLong("expiresInSeconds", automately.core.files.FileLinks.DEFAULT_TTL.toSeconds());
        String absolute = ctx.request().absoluteURI();
        String base = absolute.substring(0, absolute.indexOf("/v1/"));
        links.create(ctx.get("tenant"), path, java.time.Duration.ofSeconds(seconds))
                .onSuccess(link -> {
                    ctx.response().putHeader("Cache-Control", "no-store");
                    ctx.json(link.put("url", base + "/v1/shared/" + link.getString("token")));
                })
                .onFailure(err -> fail(ctx, err));
    }

    /** Public: a shared file, with Range and ETag like any download, while its link is valid. */
    private void shared(RoutingContext ctx) {
        String token = ctx.request().path().substring("/v1/shared/".length());
        links.resolve(token).onFailure(err -> fail(ctx, err)).onSuccess(file -> {
            if (file == null) {
                error(ctx, 404, "not_found", "This link doesn't exist, has expired, or the file has changed since");
                return;
            }
            ServerExchange exchange = new ServerExchange(ctx.request(), ctx.vertx().getOrCreateContext());
            JsonObject request = exchange.request();
            FileResponder.respond(files, file.tenant(), file.path(), request.getString("method"), request.getJsonObject("headers"),
                    new JsonObject().put("cache-control", "private, no-store"), exchange).onFailure(err -> {
                if (!ctx.response().headWritten()) {
                    fail(ctx, err);
                }
            });
        });
    }

    /** One level of a directory, by name, paged with {@code limit} and {@code cursor}. */
    private void listDir(RoutingContext ctx, String dir) {
        int limit;
        try {
            limit = Math.min(Math.max(1, Integer.parseInt(ctx.request().getParam("limit", "100"))), 1000);
        } catch (NumberFormatException e) {
            error(ctx, 400, "invalid_request", "limit must be a number");
            return;
        }
        files.listDir(ctx.get("tenant"), dir, limit, ctx.request().getParam("cursor")).onSuccess(ctx::json).onFailure(err -> fail(ctx, err));
    }

    /** Streams the request body into the store chunk by chunk, pausing the client while each piece is stored. */
    private void upload(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        request.pause();
        String path = path(ctx);
        if (path == null) {
            return;
        }
        String length = request.getHeader("Content-Length");
        long declared;
        try {
            declared = length == null ? 0 : Long.parseLong(length.trim());
        } catch (NumberFormatException e) {
            error(ctx, 400, "invalid_request", "Content-Length must be a number");
            return;
        }
        // A known size is checked against the quota (files and kv, D-043) before a byte is read.
        String tenant = ctx.get("tenant");
        files.ensureRoom(tenant, path, room, declared)
                .compose(fits -> files.create(tenant, path, request.getHeader("Content-Type"), room))
                .onFailure(err -> fail(ctx, err)).onSuccess(writer -> {
            boolean[] failed = {false};
            request.handler(chunk -> {
                request.pause();
                writer.write(chunk).onComplete(ar -> {
                    if (ar.succeeded()) {
                        request.resume();
                    } else if (!failed[0]) {
                        failed[0] = true;
                        writer.abort();
                        fail(ctx, ar.cause());
                    }
                });
            });
            request.endHandler(v -> {
                if (failed[0]) {
                    return;
                }
                writer.finish()
                        .onSuccess(info -> {
                            if (automately.core.storage.StorageRoom.countsAgainstQuota(info.backend())) {
                                quota.added(tenant, info.size());
                            }
                        })
                        .onSuccess(info -> ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json")
                                .putHeader("ETag", info.etag()).end(info.toViewJson().encode()))
                        .onFailure(err -> fail(ctx, err));
            });
            request.exceptionHandler(err -> writer.abort());
            request.resume();
        });
    }

    private void download(RoutingContext ctx) {
        String path = path(ctx);
        if (path == null) {
            return;
        }
        ServerExchange exchange = new ServerExchange(ctx.request(), ctx.vertx().getOrCreateContext());
        JsonObject request = exchange.request();
        FileResponder.respond(files, ctx.get("tenant"), path, request.getString("method"), request.getJsonObject("headers"), null, exchange)
                .onFailure(err -> {
                    if (!ctx.response().headWritten()) {
                        fail(ctx, err);
                    }
                });
    }

    private void list(RoutingContext ctx) {
        int limit;
        try {
            limit = Math.min(Math.max(1, Integer.parseInt(ctx.request().getParam("limit", "100"))), 1000);
        } catch (NumberFormatException e) {
            error(ctx, 400, "invalid_request", "limit must be a number");
            return;
        }
        files.list(ctx.get("tenant"), ctx.request().getParam("prefix", ""), limit, ctx.request().getParam("cursor"))
                .onSuccess(ctx::json)
                .onFailure(err -> fail(ctx, err));
    }

    private void delete(RoutingContext ctx) {
        String path = path(ctx);
        if (path == null) {
            return;
        }
        files.delete(ctx.get("tenant"), path).onSuccess(deleted -> {
            if (deleted) {
                ctx.response().setStatusCode(204).end();
            } else {
                error(ctx, 404, "not_found", "No such file");
            }
        }).onFailure(err -> fail(ctx, err));
    }

    /** Storage problems map to HTTP: no room on the cluster is 507, over the tenant's quota is 413. */
    static void fail(RoutingContext ctx, Throwable err) {
        if (ctx.response().ended()) {
            return;
        }
        if (err instanceof HostException host) {
            String reason = switch (host.code()) {
                case "insufficient_storage" -> "insufficient_storage";
                case "limit_exceeded" -> host.getMessage().contains("allotment") ? "allotment" : "quota";
                case "storage_disabled" -> "disabled";
                case "storage_unavailable" -> "unavailable";
                default -> null;
            };
            if (reason != null) {
                automately.core.metrics.Metrics.of(ctx.vertx()).storageRefused(reason);
            }
            switch (host.code()) {
                case "not_found" -> error(ctx, 404, host.code(), host.getMessage());
                case "read_only", "reserved" -> error(ctx, 403, host.code(), host.getMessage());
                case "already_exists", "not_empty", "is_a_directory", "not_a_directory", "busy", "unavailable" -> error(ctx, 409, host.code(), host.getMessage());
                case "insufficient_storage" -> error(ctx, 507, host.code(), host.getMessage());
                case "storage_unavailable" -> error(ctx, 503, host.code(), host.getMessage());
                case "storage_disabled" -> error(ctx, 403, host.code(), host.getMessage());
                case "limit_exceeded" -> error(ctx, 413, host.code(), host.getMessage());
                default -> error(ctx, 400, host.code(), host.getMessage());
            }
            return;
        }
        ctx.fail(err);
    }
}
