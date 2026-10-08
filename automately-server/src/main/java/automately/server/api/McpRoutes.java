package automately.server.api;

import automately.core.auth.AuthFailure;
import automately.core.auth.Authenticator;
import automately.core.auth.Principal;
import automately.core.auth.RateLimiter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The MCP server (D-047): MCP's Streamable HTTP transport at {@code /mcp}, stateless, answering with plain JSON.
 *
 * <p>Every tool calls this node's own REST API over loopback with the caller's token, so scopes, validation, quotas
 * and rate limits apply exactly as they do to any other client, and there is one implementation of each operation.
 * The scripting guides are resources, so an agent can learn the script API before writing one.
 */
final class McpRoutes {

    /** Newest first; a client asking for one of these gets it back, any other gets the newest. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    static final Duration MAX_WAIT = Duration.ofMinutes(5);
    static final int MAX_FILE_CHARS = 256 * 1024;
    static final long LOG_WINDOW_MS = 1_500;
    static final String LOOPBACK_HEADER = "X-Automately-Mcp-Loopback";

    private static final String INSTRUCTIONS = """
            Automately runs JavaScript (ES modules) in sandboxed V8 isolates on a cluster.
            A script is `export default async (input, ctx) => { ... }`; whatever it returns is the job's output.
            Jobs run once (run_job). Services run until stopped and can serve HTTP with ctx.serve(handler) at the hosts
            deploy_service reports. Scripts get kv, bus, fetch, files and log; read the automately://docs/* resources
            for the exact APIs (host-apis covers kv/bus/fetch/files/log and ctx.serve; job-context covers ctx.step and
            checkpoints; services covers long-running services) before writing a script.
            """;

    private static final List<JsonObject> RESOURCES = List.of(
            resource("host-apis", "Host APIs: kv, bus, fetch, files, log, and serving HTTP"),
            resource("job-context", "The job context: attempts, ctx.step, checkpoints, onOrphan policies"),
            resource("services", "Services: long-running scripts, restarts, timers, cancellation"));

    private final Vertx vertx;
    private final Authenticator authenticator;
    private final RateLimiter limiter;
    private final IntSupplier apiPort;
    private final String loopbackSecret;
    private final HttpClient loopback;
    private final List<Tool> tools;

    /** A tool: its MCP description, and what it does with the caller's arguments and token. */
    private record Tool(String name, String description, JsonObject inputSchema, boolean readOnly, boolean destructive,
                        Function<Call, Future<JsonObject>> run) {

        JsonObject describe() {
            return new JsonObject().put("name", name).put("description", description).put("inputSchema", inputSchema)
                    .put("annotations", new JsonObject().put("readOnlyHint", readOnly).put("destructiveHint", destructive)
                            .put("openWorldHint", false));
        }
    }

    /** One tools/call: the arguments, and the caller's token for the loopback calls. */
    private record Call(JsonObject args, String authorization) {

        String text(String field) {
            Object value = args.getValue(field);
            if (!(value instanceof String s) || s.isBlank()) {
                throw new ToolError("\"" + field + "\" is required and must be a non-empty string");
            }
            return s;
        }
    }

    /** A failure the agent should see as a tool result with isError, not a protocol error. */
    private static final class ToolError extends RuntimeException {
        ToolError(String message) {
            super(message, null, false, false);
        }
    }

    /** {@code loopbackSecret} marks this node's own tool calls, which the tool call already paid for in rate limits. */
    McpRoutes(Vertx vertx, Authenticator authenticator, RateLimiter limiter, IntSupplier apiPort, String loopbackSecret) {
        this.vertx = vertx;
        this.loopbackSecret = loopbackSecret;
        this.authenticator = authenticator;
        this.limiter = limiter;
        this.apiPort = apiPort;
        this.loopback = vertx.createHttpClient();
        this.tools = tools();
    }

    void mount(Router router) {
        router.post("/mcp").handler(BodyHandler.create(false).setBodyLimit(ApiVerticle.MAX_SCRIPT_BYTES + 64 * 1024)).handler(this::post);
        // No server-initiated stream and no sessions: GET and DELETE aren't offered (the transport allows that).
        router.get("/mcp").handler(ctx -> ctx.response().setStatusCode(405).putHeader("Allow", "POST").end());
        router.delete("/mcp").handler(ctx -> ctx.response().setStatusCode(405).putHeader("Allow", "POST").end());
    }

    private void post(RoutingContext ctx) {
        String authorization = ctx.request().getHeader("Authorization");
        authenticator.authenticate(authorization).onComplete(ar -> {
            if (ar.failed()) {
                if (ar.cause() instanceof AuthFailure failure) {
                    ctx.response().putHeader("WWW-Authenticate", "Bearer realm=\"automately\"");
                    ApiVerticle.error(ctx, failure.status(), failure.code(), failure.getMessage());
                } else {
                    ctx.fail(ar.cause());
                }
                return;
            }
            Principal principal = ar.result();
            if (principal.admin()) {
                ApiVerticle.error(ctx, 403, "forbidden", "MCP tools act on a tenant's jobs, services and files; use a tenant token");
                return;
            }
            if (limiter.enabled() && !limiter.acquire(principal.tenant(), System.nanoTime()).allowed()) {
                ApiVerticle.error(ctx, 429, "rate_limited", "Over this tenant's request budget; slow down");
                return;
            }
            JsonObject message;
            try {
                Object parsed = io.vertx.core.json.Json.decodeValue(ctx.body().buffer() == null ? Buffer.buffer() : ctx.body().buffer());
                if (!(parsed instanceof JsonObject object)) {
                    rpc(ctx, error(null, -32600, "Send one JSON-RPC message per request (batches aren't supported)"));
                    return;
                }
                message = object;
            } catch (RuntimeException e) {
                rpc(ctx, error(null, -32700, "Parse error"));
                return;
            }
            Object id = message.getValue("id");
            String method = message.getString("method");
            if (method == null) {
                // A response or a stray message from the client: nothing to answer.
                ctx.response().setStatusCode(202).end();
                return;
            }
            if (id == null) {
                // Notifications (initialized, cancelled, ...) need no answer.
                ctx.response().setStatusCode(202).end();
                return;
            }
            handle(method, message.getJsonObject("params", new JsonObject()), authorization)
                    .onSuccess(result -> rpc(ctx, new JsonObject().put("jsonrpc", "2.0").put("id", id).put("result", result)))
                    .onFailure(err -> rpc(ctx, err instanceof RpcError rpcError
                            ? error(id, rpcError.code, rpcError.getMessage())
                            : error(id, -32603, "Internal error: " + err.getMessage())));
        });
    }

    private static final class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    private Future<JsonObject> handle(String method, JsonObject params, String authorization) {
        return switch (method) {
            case "initialize" -> Future.succeededFuture(initialize(params));
            case "ping" -> Future.succeededFuture(new JsonObject());
            case "tools/list" -> Future.succeededFuture(new JsonObject().put("tools", new JsonArray(tools.stream().map(Tool::describe).toList())));
            case "tools/call" -> callTool(params, authorization);
            case "resources/list" -> Future.succeededFuture(new JsonObject().put("resources", new JsonArray(RESOURCES)));
            case "resources/read" -> readResource(params.getString("uri"));
            case "prompts/list" -> Future.succeededFuture(new JsonObject().put("prompts", new JsonArray()));
            default -> Future.failedFuture(new RpcError(-32601, "Method not found: " + method));
        };
    }

    private static JsonObject initialize(JsonObject params) {
        String asked = params.getString("protocolVersion");
        String version = PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.getFirst();
        return new JsonObject()
                .put("protocolVersion", version)
                .put("capabilities", new JsonObject()
                        .put("tools", new JsonObject().put("listChanged", false))
                        .put("resources", new JsonObject().put("listChanged", false).put("subscribe", false)))
                .put("serverInfo", new JsonObject().put("name", "automately").put("title", "Automately").put("version", version()))
                .put("instructions", INSTRUCTIONS);
    }

    private static String version() {
        String version = McpRoutes.class.getPackage().getImplementationVersion();
        return version == null ? "2.0.0" : version;
    }

    private Future<JsonObject> callTool(JsonObject params, String authorization) {
        String name = params.getString("name");
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return Future.failedFuture(new RpcError(-32602, "Unknown tool: " + name));
        }
        Call call = new Call(params.getJsonObject("arguments", new JsonObject()), authorization);
        Future<JsonObject> result;
        try {
            result = tool.run().apply(call);
        } catch (ToolError e) {
            result = Future.failedFuture(e);
        }
        return result.map(structured -> new JsonObject()
                        .put("content", new JsonArray().add(new JsonObject().put("type", "text").put("text", structured.encodePrettily())))
                        .put("structuredContent", structured)
                        .put("isError", false))
                .recover(err -> Future.succeededFuture(new JsonObject()
                        .put("content", new JsonArray().add(new JsonObject().put("type", "text").put("text", err.getMessage())))
                        .put("isError", true)));
    }

    private static JsonObject resource(String name, String description) {
        return new JsonObject().put("uri", "automately://docs/" + name).put("name", name).put("title", description)
                .put("description", description).put("mimeType", "text/markdown");
    }

    private Future<JsonObject> readResource(String uri) {
        if (uri == null || !uri.startsWith("automately://docs/")) {
            return Future.failedFuture(new RpcError(-32602, "Unknown resource: " + uri));
        }
        String name = uri.substring("automately://docs/".length());
        if (RESOURCES.stream().noneMatch(r -> r.getString("name").equals(name))) {
            return Future.failedFuture(new RpcError(-32602, "Unknown resource: " + uri));
        }
        try (InputStream in = McpRoutes.class.getResourceAsStream("/mcp-docs/" + name + ".md")) {
            if (in == null) {
                return Future.failedFuture(new RpcError(-32603, "The docs weren't packaged with this build"));
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Future.succeededFuture(new JsonObject().put("contents", new JsonArray()
                    .add(new JsonObject().put("uri", uri).put("mimeType", "text/markdown").put("text", text))));
        } catch (IOException e) {
            return Future.failedFuture(new RpcError(-32603, "Can't read " + uri));
        }
    }

    private void rpc(RoutingContext ctx, JsonObject message) {
        ctx.response().putHeader("Content-Type", "application/json").end(message.encode());
    }

    private static JsonObject error(Object id, int code, String message) {
        return new JsonObject().put("jsonrpc", "2.0").put("id", id).put("error", new JsonObject().put("code", code).put("message", message));
    }

    // --- the loopback API ---

    /** One REST call as the caller. Completes with the JSON body (or null); an error status fails it with the API's message. */
    private Future<JsonObject> api(Call call, HttpMethod method, String path, Buffer body, String contentType) {
        RequestOptions options = new RequestOptions().setMethod(method).setHost("127.0.0.1").setPort(apiPort.getAsInt()).setURI(path)
                .putHeader("Authorization", call.authorization()).putHeader(LOOPBACK_HEADER, loopbackSecret);
        if (contentType != null) {
            options.putHeader("Content-Type", contentType);
        }
        return loopback.request(options)
                .compose(request -> body == null ? request.send() : request.send(body))
                .compose(response -> response.body().map(data -> new Object[] {response, data}))
                .compose(pair -> {
                    HttpClientResponse response = (HttpClientResponse) pair[0];
                    Buffer data = (Buffer) pair[1];
                    if (response.statusCode() >= 400) {
                        String message = data.length() == 0 ? "HTTP " + response.statusCode() : describeError(data, response.statusCode());
                        return Future.failedFuture(new ToolError(message));
                    }
                    if (data.length() == 0) {
                        return Future.succeededFuture((JsonObject) null);
                    }
                    String type = response.getHeader("Content-Type");
                    if (type != null && type.startsWith("application/json")) {
                        Object json = io.vertx.core.json.Json.decodeValue(data);
                        return Future.succeededFuture(json instanceof JsonObject o ? o : new JsonObject().put("value", json));
                    }
                    return Future.succeededFuture(new JsonObject().put("text", data.toString()).put("contentType", type));
                });
    }

    private static String describeError(Buffer data, int status) {
        try {
            JsonObject error = new JsonObject(data).getJsonObject("error");
            return error.getString("message") + " (HTTP " + status + ", " + error.getValue("code") + ")";
        } catch (RuntimeException e) {
            return "HTTP " + status + ": " + data;
        }
    }

    private Future<JsonObject> get(Call call, String path) {
        return api(call, HttpMethod.GET, path, null, null);
    }

    private Future<JsonObject> post(Call call, String path, JsonObject body) {
        return api(call, HttpMethod.POST, path, body == null ? null : body.toBuffer(), body == null ? null : "application/json");
    }

    /** A tool's script, or the package it names instead (D-091). */
    private static JsonObject source(Call call) {
        if (call.args().getValue("package") instanceof JsonObject target) {
            return new JsonObject().put("package", target);
        }
        return new JsonObject().put("script", call.text("script"));
    }

    /** Uploads the files the cluster doesn't have, then commits the version: the CLI's three steps, over loopback. */
    private Future<JsonObject> publishPackage(Call call) {
        if (!(call.args().getValue("files") instanceof JsonObject files) || files.isEmpty()) {
            throw new ToolError("\"files\" must map each file's path to its text");
        }
        java.util.Map<String, Buffer> bytes = new java.util.LinkedHashMap<>();
        JsonObject hashes = new JsonObject();
        for (String path : files.fieldNames()) {
            if (!(files.getValue(path) instanceof String text)) {
                throw new ToolError("The file " + path + " must be given as text");
            }
            Buffer content = Buffer.buffer(text);
            String sha = sha256(content);
            bytes.put(sha, content);
            hashes.put(path, "sha256:" + sha);
        }
        JsonObject manifest = new JsonObject().put("name", call.text("name")).put("version", call.text("version")).put("files", hashes);
        for (String optional : List.of("exports", "dependencies")) {
            if (call.args().getValue(optional) != null) {
                manifest.put(optional, call.args().getValue(optional));
            }
        }
        return post(call, "/v1/packages/blobs/missing", new JsonObject().put("sha256", new JsonArray(List.copyOf(bytes.keySet()))))
                .compose(found -> {
                    List<Future<JsonObject>> uploads = new ArrayList<>();
                    found.getJsonArray("missing").forEach(sha -> uploads.add(api(call, HttpMethod.PUT, "/v1/packages/blobs/" + sha,
                            bytes.get(String.valueOf(sha)), "application/octet-stream")));
                    return Future.all(uploads);
                })
                .compose(uploaded -> post(call, "/v1/packages", manifest));
    }

    private static String sha256(Buffer content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String seg(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String filePath(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/", -1)) {
            parts.add(seg(part));
        }
        return String.join("/", parts);
    }

    /** A finished job is fetched; a running one is waited for, up to {@code waitMs}. */
    private Future<JsonObject> awaitJob(Call call, String id, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        Promise<JsonObject> done = Promise.promise();
        poll(call, id, deadline, done);
        return done.future();
    }

    private void poll(Call call, String id, long deadline, Promise<JsonObject> done) {
        get(call, "/v1/jobs/" + seg(id)).onFailure(done::fail).onSuccess(job -> {
            String status = job.getString("status");
            if (!"QUEUED".equals(status) && !"RUNNING".equals(status) || System.currentTimeMillis() >= deadline) {
                done.complete(job);
            } else {
                vertx.setTimer(200, t -> poll(call, id, deadline, done));
            }
        });
    }

    /** What a job has logged: all of it if it ended, its recent lines (from its event stream) if it's running. */
    private Future<JsonObject> logsOf(Call call, String id) {
        return get(call, "/v1/jobs/" + seg(id)).compose(job -> {
            String status = job.getString("status");
            if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                return Future.succeededFuture(new JsonObject().put("jobId", id).put("status", status).put("logs", job.getJsonArray("logs")));
            }
            Promise<JsonObject> collected = Promise.promise();
            JsonArray lines = new JsonArray();
            RequestOptions options = new RequestOptions().setHost("127.0.0.1").setPort(apiPort.getAsInt())
                    .setURI("/v1/jobs/" + seg(id) + "/events").putHeader("Authorization", call.authorization())
                    .putHeader(LOOPBACK_HEADER, loopbackSecret);
            loopback.request(options).compose(r -> r.send()).onFailure(collected::fail).onSuccess(response -> {
                StringBuilder pending = new StringBuilder();
                long timer = vertx.setTimer(LOG_WINDOW_MS, t -> {
                    response.request().reset();
                    collected.tryComplete(new JsonObject().put("jobId", id).put("status", status).put("logs", lines));
                });
                response.handler(chunk -> {
                    pending.append(chunk.toString(StandardCharsets.UTF_8));
                    int end;
                    while ((end = pending.indexOf("\n\n")) >= 0) {
                        String event = pending.substring(0, end);
                        pending.delete(0, end + 2);
                        for (String line : event.split("\n")) {
                            if (line.startsWith("data:")) {
                                JsonObject json = new JsonObject(line.substring(5).trim());
                                if ("log".equals(json.getString("type"))) {
                                    lines.add(json.getString("line"));
                                }
                            }
                        }
                    }
                });
                response.endHandler(v -> {
                    vertx.cancelTimer(timer);
                    collected.tryComplete(new JsonObject().put("jobId", id).put("status", status).put("logs", lines));
                });
                response.exceptionHandler(err -> collected.tryComplete(new JsonObject().put("jobId", id).put("status", status).put("logs", lines)));
            });
            return collected.future();
        });
    }

    // --- the tools ---

    private static JsonObject schema(Map<String, JsonObject> properties, String... required) {
        JsonObject props = new JsonObject();
        properties.forEach(props::put);
        return new JsonObject().put("type", "object").put("properties", props).put("required", new JsonArray(List.of(required)))
                .put("additionalProperties", false);
    }

    private static JsonObject string(String description) {
        return new JsonObject().put("type", "string").put("description", description);
    }

    private static JsonObject object(String description) {
        return new JsonObject().put("type", "object").put("description", description);
    }

    private static JsonObject integer(String description) {
        return new JsonObject().put("type", "integer").put("description", description);
    }

    private List<Tool> tools() {
        JsonObject name = string("The service's name: lowercase letters, digits, '_' and '-'.");
        JsonObject id = string("A job id.");
        JsonObject target = object("Instead of a script: a published package version to run, {name, version?, export?}. "
                + "Its export's default export is the script; without a version, the newest release.");
        return List.of(
                new Tool("run_job", "Run a script once and (by default) wait for it to finish. Returns its status, output, error and logs.",
                        schema(Map.of(
                                "script", string("An ES module: export default async (input, ctx) => { ... }. Its return value is the output."),
                                "input", object("The job's input object (the default export's first argument)."),
                                "timeoutMs", integer("The job's time limit in milliseconds."),
                                "wait", new JsonObject().put("type", "boolean").put("description", "Wait for the result (default true)."),
                                "package", target)),
                        false, false, call -> {
                            JsonObject body = source(call);
                            if (call.args().getValue("input") != null) {
                                body.put("input", call.args().getValue("input"));
                            }
                            if (call.args().getValue("timeoutMs") != null) {
                                body.put("timeoutMs", call.args().getValue("timeoutMs"));
                            }
                            boolean wait = call.args().getBoolean("wait", true);
                            long timeout = call.args().getLong("timeoutMs", 10_000L);
                            return post(call, "/v1/jobs", body).compose(job -> !wait
                                    ? Future.succeededFuture(summary(job))
                                    : awaitJob(call, job.getString("id"), Math.min(MAX_WAIT.toMillis(), timeout + 5_000)).map(McpRoutes::summary));
                        }),
                new Tool("get_job", "A job's status, output, error and logs.", schema(Map.of("id", id), "id"), true, false,
                        call -> get(call, "/v1/jobs/" + seg(call.text("id"))).map(McpRoutes::summary)),
                new Tool("job_logs", "A job's logs: all of them if it ended, its recent lines if it's still running.",
                        schema(Map.of("id", id), "id"), true, false, call -> logsOf(call, call.text("id"))),
                new Tool("list_jobs", "Your most recent jobs.", schema(Map.of("limit", integer("How many (default 20, at most 1000).")))
                        , true, false, call -> get(call, "/v1/jobs?limit=" + call.args().getInteger("limit", 20))),
                new Tool("cancel_job", "Cancel a job; a running one gets a graceful stop.", schema(Map.of("id", id), "id"), false, true,
                        call -> post(call, "/v1/jobs/" + seg(call.text("id")) + "/cancel", null).map(McpRoutes::summary)),
                new Tool("deploy_service", "Create or update a long-running service. It restarts on crashes and can serve HTTP with "
                        + "ctx.serve(handler); the result lists the hosts it's reachable at.",
                        schema(Map.of(
                                "name", name,
                                "script", string("An ES module whose default export runs until ctx.stopRequested, e.g. await ctx.serve(req => 'hi'); return ctx.stopRequested;"),
                                "config", object("Passed to the script as ctx.service.config."),
                                "restart", new JsonObject().put("type", "string").put("enum", new JsonArray().add("always").add("on-failure")),
                                "package", target),
                                "name"),
                        false, false, call -> {
                            JsonObject body = source(call);
                            if (call.args().getValue("config") != null) {
                                body.put("config", call.args().getValue("config"));
                            }
                            if (call.args().getValue("restart") != null) {
                                body.put("restart", call.args().getValue("restart"));
                            }
                            return api(call, HttpMethod.PUT, "/v1/services/" + seg(call.text("name")), body.toBuffer(), "application/json");
                        }),
                new Tool("list_services", "Your services, with status and hosts.", schema(Map.of()), true, false,
                        call -> get(call, "/v1/services")),
                new Tool("service_status", "One service: status, runs, last exit, hosts.", schema(Map.of("name", name), "name"), true, false,
                        call -> get(call, "/v1/services/" + seg(call.text("name")))),
                new Tool("service_logs", "The recent logs of a service's current run.", schema(Map.of("name", name), "name"), true, false,
                        call -> get(call, "/v1/services/" + seg(call.text("name"))).compose(service -> service.getString("currentJobId") == null
                                ? Future.failedFuture(new ToolError("The service has no current run (" + service.getString("status") + ")"))
                                : logsOf(call, service.getString("currentJobId")))),
                new Tool("restart_service", "Restart a service's current run.", schema(Map.of("name", name), "name"), false, false,
                        call -> post(call, "/v1/services/" + seg(call.text("name")) + "/restart", null)),
                new Tool("stop_service", "Stop (disable) a service; it gets a graceful stop.", schema(Map.of("name", name), "name"), false, true,
                        call -> post(call, "/v1/services/" + seg(call.text("name")) + "/stop", null)),
                new Tool("start_service", "Start (enable) a stopped service.", schema(Map.of("name", name), "name"), false, false,
                        call -> post(call, "/v1/services/" + seg(call.text("name")) + "/start", null)),
                new Tool("delete_service", "Stop and remove a service.", schema(Map.of("name", name), "name"), false, true,
                        call -> api(call, HttpMethod.DELETE, "/v1/services/" + seg(call.text("name")), null, null)
                                .map(v -> new JsonObject().put("deleted", call.text("name")))),
                new Tool("write_file", "Store a text file (scripts read it with files.read; services can serve it with files.stream).",
                        schema(Map.of("path", string("A relative path, e.g. site/index.html."), "content", string("The file's text."),
                                "contentType", string("e.g. text/html; defaults to text/plain.")), "path", "content"),
                        false, false, call -> api(call, HttpMethod.PUT, "/v1/files/" + filePath(call.text("path")),
                                Buffer.buffer(call.args().getString("content", "")), call.args().getString("contentType", "text/plain; charset=utf-8"))),
                new Tool("read_file", "Read a text file (up to 256 KB).", schema(Map.of("path", string("The file's path.")), "path"), true, false,
                        call -> get(call, "/v1/files/" + filePath(call.text("path"))).map(file -> {
                            String text = file == null ? "" : file.containsKey("text") ? file.getString("text") : file.encode();
                            return new JsonObject().put("path", call.text("path")).put("truncated", text.length() > MAX_FILE_CHARS)
                                    .put("content", text.length() > MAX_FILE_CHARS ? text.substring(0, MAX_FILE_CHARS) : text);
                        })),
                new Tool("list_files", "Your files under a prefix.", schema(Map.of("prefix", string("e.g. site/ (default: all)."))), true, false,
                        call -> get(call, "/v1/files?limit=1000&prefix=" + seg(call.args().getString("prefix", "")))),
                new Tool("list_dir", "One level of a folder: its files and subfolders, by name.",
                        schema(Map.of("path", string("The folder, e.g. site/assets (default: the top level)."))), true, false,
                        call -> {
                            String dir = automately.core.files.Paths.directory(call.args().getString("path", ""));
                            return get(call, (dir.isEmpty() ? "/v1/dirs" : "/v1/dirs/" + filePath(dir)) + "?limit=1000");
                        }),
                new Tool("make_dir", "Make a folder, and any missing above it.", schema(Map.of("path", string("The folder, e.g. site/assets.")), "path"),
                        false, false, call -> api(call, HttpMethod.PUT, "/v1/dirs/" + filePath(automately.core.files.Paths.directory(call.text("path"))),
                                null, null)),
                new Tool("move", "Move or rename a file, or a folder with everything in it. Nothing is re-uploaded.",
                        schema(Map.of("from", string("The file or folder."), "to", string("Where it goes."),
                                "overwrite", new JsonObject().put("type", "boolean").put("description", "Replace or merge into what's there.")), "from", "to"),
                        false, true, call -> api(call, HttpMethod.POST, "/v1/fs/move", call.args().copy().put("overwrite",
                                call.args().getBoolean("overwrite", false)).toBuffer(), "application/json")),
                new Tool("copy", "Copy a file, or a folder with everything in it. Copies share their bytes.",
                        schema(Map.of("from", string("The file or folder."), "to", string("Where the copy goes."),
                                "overwrite", new JsonObject().put("type", "boolean").put("description", "Replace or merge into what's there.")), "from", "to"),
                        false, false, call -> api(call, HttpMethod.POST, "/v1/fs/copy", call.args().copy().put("overwrite",
                                call.args().getBoolean("overwrite", false)).toBuffer(), "application/json")),
                new Tool("delete_file", "Delete a file.", schema(Map.of("path", string("The file's path.")), "path"), false, true,
                        call -> api(call, HttpMethod.DELETE, "/v1/files/" + filePath(call.text("path")), null, null)
                                .map(v -> new JsonObject().put("deleted", call.text("path")))),
                new Tool("publish", "Publish a JSON message to a bus topic your scripts subscribe to.",
                        schema(Map.of("topic", string("[a-zA-Z0-9._-]{1,128}"), "message", new JsonObject().put("description", "Any JSON.")), "topic"),
                        false, false, call -> api(call, HttpMethod.POST, "/v1/bus/" + seg(call.text("topic")),
                                io.vertx.core.json.Json.encodeToBuffer(call.args().getValue("message")), "application/json")
                                .map(v -> new JsonObject().put("published", call.text("topic")))),
                new Tool("request", "Call a bus topic's handler (a script's bus.handle) and return its reply.",
                        schema(Map.of("topic", string("[a-zA-Z0-9._-]{1,128}"), "message", new JsonObject().put("description", "Any JSON."),
                                "timeoutMs", integer("At most 30000.")), "topic"),
                        false, false, call -> api(call, HttpMethod.POST, "/v1/bus/" + seg(call.text("topic")) + "/request?timeoutMs="
                                        + call.args().getInteger("timeoutMs", 10_000),
                                io.vertx.core.json.Json.encodeToBuffer(call.args().getValue("message")), "application/json")),
                new Tool("queue_push", "Push a JSON message onto a durable queue that your services consume (bus.queue(name).consume). "
                        + "Delivered at least once; a failing consumer gets it again later.",
                        schema(Map.of("queue", string("[a-zA-Z0-9._-]{1,128}"), "message", new JsonObject().put("description", "Any JSON."),
                                "delayMs", integer("Deliver no sooner than this."),
                                "key", string("Optional: push at most once while a message with this key is queued.")), "queue", "message"),
                        false, false, call -> {
                            JsonObject body = new JsonObject().put("message", call.args().getValue("message"));
                            if (call.args().getValue("delayMs") != null) {
                                body.put("delayMs", call.args().getValue("delayMs"));
                            }
                            if (call.args().getValue("key") != null) {
                                body.put("key", call.args().getValue("key"));
                            }
                            return api(call, HttpMethod.POST, "/v1/queues/" + seg(call.text("queue")), body.toBuffer(), "application/json");
                        }),
                new Tool("publish_package", "Publish a small package from its files' text: versioned code that scripts import by name "
                        + "(import { x } from 'name'). A version never changes; publish a new one to fix it. Scripts' imports are "
                        + "locked to exact versions when a job is submitted.",
                        schema(Map.of(
                                "name", string("Lowercase letters, digits, '.', '_' and '-'."),
                                "version", string("Like 1.0.0, or 2.0.0-beta.1."),
                                "files", object("Each file's path → its text, e.g. {\"index.js\": \"export const slug = s => ...\"}. ES modules."),
                                "exports", object("Optional: \".\" and \"./subpath\" → a file. Defaults to index.js as \".\"."),
                                "dependencies", object("Optional: other packages it imports → a version range, e.g. {\"strings\": \"^1.2.0\"}.")),
                                "name", "version", "files"),
                        false, false, this::publishPackage),
                new Tool("list_packages", "Your packages and the cluster's, with each one's latest version.", schema(Map.of()), true, false,
                        call -> get(call, "/v1/packages")),
                new Tool("package_info", "A package's versions; or, given a version, its files, exports and dependencies.",
                        schema(Map.of("name", string("The package's name, e.g. strings or @cluster/dates."), "version", string("Optional: one version.")),
                                "name"), true, false,
                        call -> get(call, "/v1/packages/" + seg(call.text("name"))
                                + (call.args().getValue("version") instanceof String v ? "/versions/" + seg(v) : ""))),
                new Tool("queue_stats", "How many messages a queue has ready, in flight and dead-lettered.",
                        schema(Map.of("queue", string("The queue's name.")), "queue"), true, false,
                        call -> get(call, "/v1/queues/" + seg(call.text("queue")))),
                new Tool("queue_dead", "A queue's dead-lettered messages (they used up their attempts), with their last error.",
                        schema(Map.of("queue", string("The queue's name.")), "queue"), true, false,
                        call -> get(call, "/v1/queues/" + seg(call.text("queue")) + "/dead?limit=100")),
                new Tool("queue_retry", "Put a dead-lettered message back on its queue, with its attempts reset.",
                        schema(Map.of("queue", string("The queue's name."), "id", string("The message's id, from queue_dead.")), "queue", "id"),
                        false, false, call -> api(call, HttpMethod.POST, "/v1/queues/" + seg(call.text("queue")) + "/dead/" + seg(call.text("id"))
                                + "/retry", null, null)));
    }

    /** What an agent needs from a job, without the script. */
    private static JsonObject summary(JsonObject job) {
        return new JsonObject().put("id", job.getString("id")).put("status", job.getString("status"))
                .put("output", job.getValue("output")).put("error", job.getValue("error"))
                .put("logs", job.getJsonArray("logs", new JsonArray())).put("attempt", job.getValue("attempt"));
    }
}
