package cluster93.runner.protocol.client;

import cluster93.core.files.FileInfo;
import cluster93.core.files.FileWriter;
import cluster93.core.host.JvmCrypto;
import cluster93.core.host.JvmZlib;
import cluster93.core.runtime.BodyStream;
import cluster93.core.runtime.FetchedResponse;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.HttpExchange;
import cluster93.core.runtime.Inbound;
import cluster93.core.runtime.OutgoingRequest;
import cluster93.core.runtime.OutgoingSocket;
import cluster93.core.runtime.api.BusApi;
import cluster93.core.runtime.api.CryptoApi;
import cluster93.core.runtime.api.DnsApi;
import cluster93.core.runtime.api.FetchApi;
import cluster93.core.runtime.api.FilesApi;
import cluster93.core.runtime.api.FsApi;
import cluster93.core.runtime.api.HostApis;
import cluster93.core.runtime.api.HttpApi;
import cluster93.core.runtime.api.JobsApi;
import cluster93.core.runtime.api.KvApi;
import cluster93.core.runtime.api.QueueApi;
import cluster93.core.runtime.api.Registration;
import cluster93.core.runtime.api.SecretsApi;
import cluster93.core.runtime.api.SocketApi;
import cluster93.core.runtime.api.ZlibApi;
import cluster93.runner.protocol.message.CallMessage;
import cluster93.runner.protocol.message.EventMessage;
import cluster93.runner.protocol.message.OpenStreamMessage;
import cluster93.runner.protocol.message.ReplyMessage;
import cluster93.runner.protocol.message.ResultMessage;
import cluster93.runner.protocol.stream.StreamMultiplexer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Worker-side proxy implementing {@link HostApis} by sending RPC {@link CallMessage}s to the node and bridging the
 * streaming and listener host APIs over the {@link cluster93.runner.protocol.stream.StreamMultiplexer}
 * (worker-node-protocol.md §6, D-128). Pure computation ({@link #crypto()}, {@link #zlib()}) executes in-process.
 */
public final class RemoteHostApis implements HostApis {

    private final WorkerClientConnection connection;
    private final String runId;
    private final int channelId;

    private final KvApi kv;
    private final BusApi bus;
    private final QueueApi queue;
    private final JobsApi jobs;
    private final FilesApi files;
    private final FsApi fs;
    private final FetchApi fetch;
    private final SocketApi sockets;
    private final DnsApi dns;
    private final SecretsApi secrets;
    private final HttpApi http;
    private final CryptoApi crypto = new JvmCrypto();
    private final ZlibApi zlib = new JvmZlib();

    /** Listener callbacks registered by the script, by {@code method:topic}. */
    private final Map<String, Consumer<Inbound>> listeners = new ConcurrentHashMap<>();
    /** Fetch/file requests awaiting their response stream, by the request stream's id. */
    private final Map<Integer, Promise<FetchedResponse>> pendingResponses = new ConcurrentHashMap<>();
    /** Socket requests awaiting their data stream, by the request stream's id. */
    private final Map<Integer, PendingSocket> pendingSockets = new ConcurrentHashMap<>();
    /** File uploads awaiting the node's commit, by the {@code files.create} stream's id. */
    private final Map<Integer, Promise<FileInfo>> pendingFileFinishes = new ConcurrentHashMap<>();

    private record PendingSocket(Promise<OutgoingSocket> promise, io.vertx.core.streams.WriteStream<Buffer> request) {}
    /** The service's {@code ctx.serve} handler; a Node server registers a port handler instead (D-123). */
    private volatile Consumer<HttpExchange> mainHandler;
    /**
     * A Node server's handler by port. Insertion order is kept: the service's main host reaches {@code ctx.serve}, else
     * the first server that listened, matching the node's {@link cluster93.core.host.ServeRegistry} (D-123).
     */
    private final Map<Integer, Consumer<HttpExchange>> portHandlers =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    public RemoteHostApis(WorkerClientConnection connection, String runId) {
        this(connection, runId, 0);
    }

    /**
     * The network host APIs this run serves <b>itself</b>, when the worker decides egress (D-173/A4), or null when every
     * call goes to the node. Only the client half of the network surface moves: {@code kv}, files, the bus, secrets,
     * the jobs API and the {@code node:http} <em>server</em> are the node's, because they are what the node owns.
     */
    public record LocalNetwork(FetchApi fetch, DnsApi dns, SocketApi sockets) {
    }

    public RemoteHostApis(WorkerClientConnection connection, String runId, int channelId) {
        this(connection, runId, channelId, null);
    }

    /**
     * @param local the network APIs this run decides and connects with itself, or null to ask the node (D-173)
     */
    public RemoteHostApis(WorkerClientConnection connection, String runId, int channelId, LocalNetwork local) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.channelId = channelId;

        this.kv = new RemoteKv();
        this.bus = new RemoteBus();
        this.queue = new RemoteQueue();
        this.jobs = new RemoteJobs();
        this.files = new RemoteFiles();
        this.fs = new RemoteFs();
        // Egress in the worker (D-173): the same host-API surface, served by this process, under the rules the node
        // synced — with the node's own implementations of the network APIs, given this worker's gate.
        this.fetch = local == null ? new RemoteFetch() : local.fetch();
        this.sockets = local == null ? new RemoteSocket() : local.sockets();
        this.dns = local == null ? new RemoteDns() : local.dns();
        this.secrets = new RemoteSecrets();
        this.http = new RemoteHttp();
    }

    private Future<ResultMessage> call(String method, JsonObject params) {
        return call(method, params, Buffer.buffer());
    }

    private Future<ResultMessage> call(String method, JsonObject params, Buffer binary) {
        return connection.call(new CallMessage(connection.nextCallId(), runId, method, params, binary));
    }

    /**
     * Creates the outgoing side of a request stream and wires its reset/error to {@code failure}, so a node that
     * refuses the stream (a denied fetch, a failed socket connect, a missing file) fails the caller instead of hanging.
     */
    private io.vertx.core.streams.WriteStream<Buffer> requestStream(int streamId, Promise<?> failure) {
        io.vertx.core.streams.WriteStream<Buffer> out = connection.streamMultiplexer().createOutputStream(streamId);
        out.exceptionHandler(failure::tryFail);
        return out;
    }

    @Override
    public KvApi kv() {
        return kv;
    }

    @Override
    public BusApi bus() {
        return bus;
    }

    @Override
    public QueueApi queue() {
        return queue;
    }

    @Override
    public JobsApi jobs() {
        return jobs;
    }

    @Override
    public FilesApi files() {
        return files;
    }

    @Override
    public FsApi fs() {
        return fs;
    }

    @Override
    public FetchApi fetch() {
        return fetch;
    }

    @Override
    public SocketApi sockets() {
        return sockets;
    }

    @Override
    public DnsApi dns() {
        return dns;
    }

    @Override
    public SecretsApi secrets() {
        return secrets;
    }

    @Override
    public HttpApi http() {
        return http;
    }

    @Override
    public CryptoApi crypto() {
        return crypto;
    }

    @Override
    public ZlibApi zlib() {
        return zlib;
    }

    @Override
    public Future<Void> close() {
        return call("execution.close", new JsonObject()).mapEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // Incoming frames from the node: events and response streams
    // -------------------------------------------------------------------------------------------

    /** Dispatches an asynchronous event to the script's registered listener. */
    void dispatchEvent(EventMessage event) {
        Consumer<Inbound> listener = listeners.get(event.type() + ":" + event.topic());
        if (listener == null) {
            if (event.expectsReply()) {
                connection.send(ReplyMessage.failure(channelId, event.eventId(), "503", "no listener for " + event.topic()).toFrame());
            }
            return;
        }
        Inbound inbound = new Inbound() {
            @Override
            public Object payload() {
                return event.payload();
            }

            @Override
            public boolean expectsReply() {
                return event.expectsReply();
            }

            @Override
            public void reply(Object value) {
                connection.send(ReplyMessage.success(channelId, event.eventId(), value).toFrame());
            }

            @Override
            public void fail(int code, String message) {
                connection.send(ReplyMessage.failure(channelId, event.eventId(), String.valueOf(code), message).toFrame());
            }
        };
        try {
            listener.accept(inbound);
        } catch (RuntimeException e) {
            if (event.expectsReply()) {
                inbound.fail(500, String.valueOf(e.getMessage()));
            }
        }
    }

    /** Handles a stream the node opened for this run (a fetch/file/socket response, or an HTTP request). */
    void onIncomingStream(OpenStreamMessage open) {
        String type = open.streamType();
        JsonObject meta = open.metadata();
        int requestStreamId = meta.getInteger("requestStreamId", -1);
        switch (type) {
            case "fetch.response" -> completeResponse(pendingResponses.remove(requestStreamId), meta, open.streamId());
            case "files.data" -> completeResponse(pendingResponses.remove(requestStreamId), meta, open.streamId());
            case "socket.data" -> {
                PendingSocket pending = pendingSockets.remove(requestStreamId);
                if (pending == null) {
                    connection.streamMultiplexer().resetStream(open.streamId(), "UNKNOWN_SOCKET", 404);
                    return;
                }
                if (meta.containsKey("error")) {
                    // A refused connect: rebuild the node's failure, name and code included, so a script sees the same
                    // TypeError/egress_denied a worker-side gate gives it — the placement must not show through (D-179).
                    // An unclassified failure keeps this side's own default, which is what it would have been here.
                    String name = meta.getString("errorName", "Error");
                    String code = meta.getString("errorCode", "host_error");
                    pending.promise().tryFail(new HostException(name, code, meta.getString("error")));
                    connection.streamMultiplexer().resetStream(open.streamId(), "error", 502);
                    return;
                }
                pending.promise().complete(socket(meta.getJsonObject("info", new JsonObject()), requestStreamId, open.streamId(), pending.request()));
            }
            case "http.request", "http.request.upgrade" -> {
                Consumer<HttpExchange> handler = httpHandler(meta.getInteger("port"));
                if (handler == null) {
                    connection.streamMultiplexer().resetStream(open.streamId(), "NOT_SERVING", 404);
                    return;
                }
                handler.accept(new RemoteHttpExchange(open));
            }
            case "files.finish" -> completeFileFinish(meta, open.streamId());
            default -> connection.streamMultiplexer().resetStream(open.streamId(), "UNSUPPORTED_STREAM_TYPE", 400);
        }
    }

    private void completeResponse(Promise<FetchedResponse> promise, JsonObject meta, int streamId) {
        if (promise == null) {
            connection.streamMultiplexer().resetStream(streamId, "UNKNOWN_REQUEST", 404);
            return;
        }
        if (meta.containsKey("error")) {
            // Rebuild the node's failure, name and code included: the script sees TypeError/egress_denied, not a generic
            // request_failed (D-132).
            String name = meta.getString("errorName", "Error");
            String code = meta.getString("errorCode", "request_failed");
            promise.tryFail(new HostException(name, code, meta.getString("error")));
            connection.streamMultiplexer().resetStream(streamId, "error", 502);
            return;
        }
        ReadStream<Buffer> in = connection.streamMultiplexer().createInputStream(streamId);
        promise.complete(new FetchedResponse(meta.getJsonObject("meta", new JsonObject()), new ReadStreamBodyStream(in)));
    }

    /**
     * The handler a request belongs to: the port's Node server when the request names one, else {@code ctx.serve}, else
     * the first server that listened (the service's main host), matching the node's {@link cluster93.core.host.ServeRegistry}.
     */
    private Consumer<HttpExchange> httpHandler(Integer port) {
        if (port != null) {
            Consumer<HttpExchange> byPort = portHandlers.get(port);
            if (byPort != null) {
                return byPort;
            }
        }
        Consumer<HttpExchange> main = mainHandler;
        if (main != null) {
            return main;
        }
        synchronized (portHandlers) {
            var servers = portHandlers.values().iterator();
            return servers.hasNext() ? servers.next() : null;
        }
    }

    /** Completes a file upload's {@code finish()} when the node reports the commit (or its failure) (D-132). */
    private void completeFileFinish(JsonObject meta, int streamId) {
        int requestStreamId = meta.getInteger("requestStreamId", -1);
        Promise<FileInfo> finish = pendingFileFinishes.remove(requestStreamId);
        if (finish != null) {
            if (meta.containsKey("error")) {
                String name = meta.getString("errorName", "Error");
                String code = meta.getString("errorCode", "request_failed");
                finish.tryFail(new HostException(name, code, meta.getString("error")));
            } else {
                finish.tryComplete(FileInfo.fromJson(meta.getJsonObject("file")));
            }
        }
        // The node opened this stream only to carry the result; close it so its credit is released.
        connection.streamMultiplexer().resetStream(streamId, "done", 0);
    }

    private OutgoingSocket socket(JsonObject info, int requestStreamId, int dataStreamId, io.vertx.core.streams.WriteStream<Buffer> out) {
        ReadStream<Buffer> in = connection.streamMultiplexer().createInputStream(dataStreamId);
        return new OutgoingSocket() {
            @Override
            public JsonObject info() {
                return info == null ? new JsonObject() : info;
            }

            @Override
            public BodyStream data() {
                return new ReadStreamBodyStream(in);
            }

            @Override
            public Future<Void> write(Buffer chunk) {
                return out.write(chunk);
            }

            @Override
            public Future<Void> end() {
                return out.end();
            }

            @Override
            public Future<JsonObject> upgradeTls(JsonObject tls) {
                return Future.failedFuture(new HostException("Error", "unavailable", "STARTTLS isn't available over the worker protocol"));
            }

            @Override
            public void setNoDelay(boolean noDelay) {
            }

            @Override
            public void setKeepAlive(boolean keepAlive) {
            }

            @Override
            public void destroy() {
                connection.streamMultiplexer().resetStream(requestStreamId, "destroyed", 499);
            }
        };
    }

    // --- Sub-API implementations ---

    private final class RemoteKv implements KvApi {
        @Override
        public Future<Object> get(String namespace, String key) {
            return call("kv.get", new JsonObject().put("namespace", namespace).put("key", key))
                    .map(ResultMessage::value);
        }

        @Override
        public Future<Void> set(String namespace, String key, Object value, Long ttlMs) {
            JsonObject params = new JsonObject().put("namespace", namespace).put("key", key).put("value", value);
            if (ttlMs != null) {
                params.put("ttlMs", ttlMs);
            }
            return call("kv.set", params).mapEmpty();
        }

        @Override
        public Future<Boolean> delete(String namespace, String key) {
            return call("kv.delete", new JsonObject().put("namespace", namespace).put("key", key))
                    .map(r -> Boolean.TRUE.equals(r.value()));
        }

        @Override
        public Future<JsonObject> list(String namespace, String prefix, int limit, String cursor) {
            JsonObject params = new JsonObject().put("namespace", namespace).put("prefix", prefix).put("limit", limit);
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            return call("kv.list", params).map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<Long> increment(String namespace, String key, long by) {
            return call("kv.increment", new JsonObject().put("namespace", namespace).put("key", key).put("by", by))
                    .map(r -> r.value() instanceof Number n ? n.longValue() : 0L);
        }
    }

    private final class RemoteSecrets implements SecretsApi {
        @Override
        public Future<String> get(String name) {
            return call("secrets.get", new JsonObject().put("name", name))
                    .map(r -> r.value() != null ? String.valueOf(r.value()) : null);
        }
    }

    private final class RemoteBus implements BusApi {
        @Override
        public Future<Void> publish(String topic, Object message) {
            return call("bus.publish", new JsonObject().put("topic", topic).put("message", message)).mapEmpty();
        }

        @Override
        public Future<Object> request(String topic, Object message, long timeoutMs) {
            return call("bus.request", new JsonObject().put("topic", topic).put("message", message).put("timeoutMs", timeoutMs))
                    .map(ResultMessage::value);
        }

        @Override
        public Future<Registration> subscribe(String topic, Consumer<Inbound> listener) {
            return registerListener("bus.subscribe", topic, new JsonObject().put("topic", topic), listener,
                    () -> call("bus.unsubscribe", new JsonObject().put("topic", topic)).mapEmpty());
        }

        @Override
        public Future<Registration> handle(String topic, Consumer<Inbound> listener) {
            return registerListener("bus.handle", topic, new JsonObject().put("topic", topic), listener,
                    () -> call("bus.unhandle", new JsonObject().put("topic", topic)).mapEmpty());
        }
    }

    private final class RemoteFs implements FsApi {
        @Override
        public Future<JsonObject> stat(String path) {
            return call("fs.stat", new JsonObject().put("path", path))
                    .map(r -> r.value() instanceof JsonObject o ? o : null);
        }

        @Override
        public Future<JsonArray> readdir(String path) {
            return call("fs.readdir", new JsonObject().put("path", path))
                    .map(r -> r.value() instanceof JsonArray a ? a : new JsonArray());
        }

        @Override
        public Future<Void> mkdir(String path, boolean recursive) {
            return call("fs.mkdir", new JsonObject().put("path", path).put("recursive", recursive)).mapEmpty();
        }

        @Override
        public Future<Void> rmdir(String path, boolean recursive) {
            return call("fs.rmdir", new JsonObject().put("path", path).put("recursive", recursive)).mapEmpty();
        }

        @Override
        public Future<Void> unlink(String path) {
            return call("fs.unlink", new JsonObject().put("path", path)).mapEmpty();
        }

        @Override
        public Future<Void> rename(String from, String to) {
            return call("fs.rename", new JsonObject().put("from", from).put("to", to)).mapEmpty();
        }

        @Override
        public Future<Void> copyFile(String from, String to, boolean overwrite) {
            return call("fs.copyFile", new JsonObject().put("from", from).put("to", to).put("overwrite", overwrite)).mapEmpty();
        }

        @Override
        public Future<Buffer> readFile(String path) {
            return call("fs.readFile", new JsonObject().put("path", path)).map(ResultMessage::binary);
        }

        @Override
        public Future<Void> writeFile(String path, Buffer data, String contentType) {
            return call("fs.writeFile", new JsonObject().put("path", path).put("contentType", contentType), data).mapEmpty();
        }

        @Override
        public Future<Void> appendFile(String path, Buffer data) {
            return call("fs.appendFile", new JsonObject().put("path", path), data).mapEmpty();
        }

        @Override
        public Future<JsonObject> lock(String path, long ttlMs) {
            return call("fs.lock", new JsonObject().put("path", path).put("ttlMs", ttlMs))
                    .map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<JsonObject> renewLock(String path, String token, long ttlMs) {
            return call("fs.renewLock", new JsonObject().put("path", path).put("token", token).put("ttlMs", ttlMs))
                    .map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<Boolean> unlock(String path, String token) {
            return call("fs.unlock", new JsonObject().put("path", path).put("token", token))
                    .map(r -> Boolean.TRUE.equals(r.value()));
        }
    }

    private final class RemoteQueue implements QueueApi {
        @Override
        public Future<String> push(String queue, Object message, Long delayMs, String key, Integer maxAttempts) {
            JsonObject params = new JsonObject().put("queue", queue).put("message", message);
            if (delayMs != null) params.put("delayMs", delayMs);
            if (key != null) params.put("key", key);
            if (maxAttempts != null) params.put("maxAttempts", maxAttempts);
            return call("queue.push", params).map(r -> String.valueOf(r.value()));
        }

        @Override
        public Future<Registration> consume(String queue, int concurrency, Consumer<Inbound> listener) {
            JsonObject params = new JsonObject().put("queue", queue).put("concurrency", concurrency);
            return registerListener("queue.consume", queue, params, listener,
                    () -> call("queue.unconsume", new JsonObject().put("queue", queue)).mapEmpty());
        }

        @Override
        public Future<JsonObject> stats(String queue) {
            return call("queue.stats", new JsonObject().put("queue", queue))
                    .map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<JsonObject> dead(String queue, int limit, String cursor) {
            JsonObject params = new JsonObject().put("queue", queue).put("limit", limit);
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            return call("queue.dead", params).map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<Boolean> retry(String queue, String id) {
            return call("queue.retry", new JsonObject().put("queue", queue).put("id", id))
                    .map(r -> Boolean.TRUE.equals(r.value()));
        }

        @Override
        public Future<Boolean> discard(String queue, String id) {
            return call("queue.discard", new JsonObject().put("queue", queue).put("id", id))
                    .map(r -> Boolean.TRUE.equals(r.value()));
        }
    }

    private final class RemoteJobs implements JobsApi {
        @Override
        public Future<Void> setCallback(String url, Object data) {
            return call("jobs.setCallback", new JsonObject().put("url", url).put("data", data)).mapEmpty();
        }

        @Override
        public Future<JsonObject> run(String script, JsonObject input, JsonObject options) {
            return call("jobs.run", new JsonObject().put("script", script).put("input", input).put("options", options))
                    .map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<JsonObject> get(String id) {
            return call("jobs.get", new JsonObject().put("id", id))
                    .map(r -> r.value() instanceof JsonObject o ? o : null);
        }

        @Override
        public Future<JsonObject> result(String id) {
            return call("jobs.result", new JsonObject().put("id", id))
                    .map(r -> r.value() instanceof JsonObject o ? o : null);
        }

        @Override
        public Future<JsonObject> stop(String id, String reason) {
            return call("jobs.stop", new JsonObject().put("id", id).put("reason", reason))
                    .map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<Registration> events(String id, Consumer<Inbound> listener) {
            return registerListener("jobs.events", id, new JsonObject().put("id", id), listener,
                    () -> call("jobs.unevents", new JsonObject().put("id", id)).mapEmpty());
        }
    }

    private final class RemoteFiles implements FilesApi {
        @Override
        public Future<JsonObject> stat(String path) {
            return call("files.stat", new JsonObject().put("path", path))
                    .map(r -> r.value() instanceof JsonObject o ? o : null);
        }

        @Override
        public Future<JsonObject> list(String prefix, int limit, String cursor) {
            JsonObject params = new JsonObject().put("prefix", prefix).put("limit", limit);
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            return call("files.list", params).map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<Boolean> delete(String path) {
            return call("files.delete", new JsonObject().put("path", path))
                    .map(r -> Boolean.TRUE.equals(r.value()));
        }

        @Override
        public Future<FileWriter> create(String path, String contentType) {
            int streamId = connection.nextStreamId();
            JsonObject meta = new JsonObject()
                    .put("runId", runId)
                    .put("path", path)
                    .put("contentType", contentType);

            // The node commits after the stream ends and answers on a files.finish stream; finish() awaits that commit
            // instead of racing a files.stat that would still see the old (or no) version (D-132).
            Promise<FileInfo> finishPromise = Promise.promise();
            pendingFileFinishes.put(streamId, finishPromise);

            return connection.streamMultiplexer().openStream(streamId, "files.create", meta)
                    .onFailure(err -> {
                        pendingFileFinishes.remove(streamId);
                        finishPromise.tryFail(err);
                    })
                    .map(v -> {
                        var outStream = connection.streamMultiplexer().createOutputStream(streamId);
                        outStream.exceptionHandler(finishPromise::tryFail);
                        return new FileWriter() {
                            @Override
                            public Future<Void> write(Buffer data) {
                                // The node can fail the create before the first chunk; surface its error (name and code),
                                // not a bare stream reset.
                                return finishPromise.future().failed()
                                        ? Future.failedFuture(finishPromise.future().cause())
                                        : outStream.write(data);
                            }

                            @Override
                            public Future<FileInfo> finish() {
                                return outStream.end()
                                        .compose(vEnd -> finishPromise.future())
                                        .recover(err -> {
                                            // A node-side failure (a bad path, a quota) is the real answer; a fallback
                                            // stat only covers a missing files.finish (an older node).
                                            if (finishPromise.future().failed()) {
                                                return Future.failedFuture(finishPromise.future().cause());
                                            }
                                            return call("files.stat", new JsonObject().put("path", path))
                                                    .compose(res -> res.value() instanceof JsonObject obj
                                                            ? Future.succeededFuture(FileInfo.fromJson(obj))
                                                            : Future.failedFuture(err));
                                        })
                                        .onComplete(ar -> pendingFileFinishes.remove(streamId));
                            }

                            @Override
                            public Future<Void> abort() {
                                pendingFileFinishes.remove(streamId);
                                finishPromise.tryFail(new HostException("Error", "aborted", "The upload was aborted"));
                                return connection.streamMultiplexer().resetStream(streamId, "ABORTED", 499);
                            }
                        };
                    });
        }

        @Override
        public Future<FetchedResponse> open(String path) {
            int streamId = connection.nextStreamId();
            Promise<FetchedResponse> promise = Promise.promise();
            pendingResponses.put(streamId, promise);
            requestStream(streamId, promise);
            connection.streamMultiplexer().openStream(streamId, "files.open",
                            new JsonObject().put("runId", runId).put("path", path))
                    .onFailure(promise::tryFail);
            return promise.future();
        }

        @Override
        public Future<Void> respond(HttpExchange exchange, String path, JsonObject headers) {
            if (exchange instanceof RemoteHttpExchange remote) {
                JsonObject params = new JsonObject().put("exchangeId", remote.streamId()).put("path", path);
                if (headers != null) {
                    params.put("headers", headers);
                }
                return call("files.respond", params).mapEmpty();
            }
            return Future.failedFuture(new HostException("Error", "invalid_argument", "files.respond needs an exchange from this run"));
        }
    }

    private final class RemoteFetch implements FetchApi {
        @Override
        public Future<FetchedResponse> fetch(JsonObject request, byte[] body) {
            return open(request).compose(outgoing -> {
                Future<Void> sent = body == null || body.length == 0
                        ? outgoing.end()
                        : outgoing.write(Buffer.buffer(body)).compose(v -> outgoing.end());
                return sent.compose(v -> outgoing.response());
            });
        }

        @Override
        public Future<OutgoingRequest> open(JsonObject request) {
            int streamId = connection.nextStreamId();
            Promise<FetchedResponse> response = Promise.promise();
            pendingResponses.put(streamId, response);
            io.vertx.core.streams.WriteStream<Buffer> out = requestStream(streamId, response);
            return connection.streamMultiplexer().openStream(streamId, "fetch.request",
                            new JsonObject().put("runId", runId).put("request", request))
                    .map(v -> new OutgoingRequest() {
                        @Override
                        public Future<Void> write(Buffer chunk) {
                            return out.write(chunk);
                        }

                        @Override
                        public Future<Void> end() {
                            return out.end();
                        }

                        @Override
                        public Future<FetchedResponse> response() {
                            return response.future();
                        }

                        @Override
                        public void abort() {
                            pendingResponses.remove(streamId);
                            response.tryFail(new HostException("Error", "aborted", "The request was aborted"));
                            connection.streamMultiplexer().resetStream(streamId, "aborted", 499);
                        }
                    });
        }
    }

    private final class RemoteSocket implements SocketApi {
        @Override
        public Future<OutgoingSocket> connect(JsonObject request) {
            int streamId = connection.nextStreamId();
            Promise<OutgoingSocket> promise = Promise.promise();
            io.vertx.core.streams.WriteStream<Buffer> out = requestStream(streamId, promise);
            pendingSockets.put(streamId, new PendingSocket(promise, out));
            connection.streamMultiplexer().openStream(streamId, "socket.connect",
                            new JsonObject().put("runId", runId).put("request", request))
                    .onFailure(err -> {
                        pendingSockets.remove(streamId);
                        promise.tryFail(err);
                    });
            return promise.future();
        }
    }

    private final class RemoteDns implements DnsApi {
        @Override
        public Future<JsonArray> lookup(JsonObject request) {
            return call("dns.lookup", request).map(r -> r.value() instanceof JsonArray a ? a : new JsonArray());
        }

        @Override
        public Future<JsonArray> resolve(JsonObject request) {
            return call("dns.resolve", request).map(r -> r.value() instanceof JsonArray a ? a : new JsonArray());
        }
    }

    private final class RemoteHttp implements HttpApi {
        @Override
        public Future<Registration> serve(Consumer<HttpExchange> handler) {
            return call("serve.listen", new JsonObject()).map(r -> {
                mainHandler = handler;
                return () -> {
                    mainHandler = null;
                    return call("serve.unlisten", new JsonObject()).mapEmpty();
                };
            });
        }

        @Override
        public Future<Registration> listen(int port, Consumer<HttpExchange> handler) {
            // Register only once the node accepts the port: a second listen that fails with EADDRINUSE (a Node 'error')
            // must not steal the handler already serving there.
            return call("serve.listen", new JsonObject().put("port", port)).map(r -> {
                portHandlers.put(port, handler);
                return () -> {
                    portHandlers.remove(port);
                    return call("serve.unlisten", new JsonObject().put("port", port)).mapEmpty();
                };
            });
        }
    }

    private Future<Registration> registerListener(String method, String topic, JsonObject callParams, Consumer<Inbound> listener,
                                                  java.util.function.Supplier<Future<Void>> unregister) {
        String key = method + ":" + topic;
        return call(method, callParams).map(r -> {
            listeners.put(key, listener);
            return () -> {
                listeners.remove(key);
                return unregister.get();
            };
        });
    }

    // -------------------------------------------------------------------------------------------
    // Proxies for the streaming host APIs
    // -------------------------------------------------------------------------------------------

    /** A {@link BodyStream} over one direction of a multiplexed stream; starts paused, like the embedded one. */
    static final class ReadStreamBodyStream implements BodyStream {
        private final ReadStream<Buffer> in;
        /** Runs on any stream error before a body consumer's own callback; the exchange uses it for 'close' (D-123). */
        private Consumer<Throwable> onError = err -> { };

        ReadStreamBodyStream(ReadStream<Buffer> in) {
            this.in = in;
            this.in.pause();
            // A reset that carries a host failure's name and code becomes that same failure (D-184): a fetch body that
            // died mid-stream reads as TypeError/network_error here, exactly as it would have on the worker's side,
            // instead of as "Stream reset (code 502)" (D-181's remaining gap).
            this.in.exceptionHandler(err -> onError.accept(hostFailure(err)));
        }

        private static Throwable hostFailure(Throwable err) {
            if (err instanceof StreamMultiplexer.StreamResetException reset && reset.errorName() != null) {
                return new HostException(reset.errorName(),
                        reset.hostCode() == null ? "host_error" : reset.hostCode(), reset.reason());
            }
            return err;
        }

        /** Adds an error callback that always runs, alongside any the body consumer registers. */
        ReadStreamBodyStream onError(Consumer<Throwable> handler) {
            Consumer<Throwable> previous = onError;
            onError = err -> {
                previous.accept(err);
                handler.accept(err);
            };
            return this;
        }

        @Override
        public void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
            in.handler(chunk::accept);
            in.endHandler(v -> end.run());
            if (error != null) {
                onError(error);
            }
        }

        @Override
        public void pause() {
            in.pause();
        }

        @Override
        public void resume() {
            in.resume();
        }

        @Override
        public void cancel() {
            in.resume();
        }
    }

    /** The {@link HttpExchange} a {@code ctx.serve} handler sees, streamed over the worker protocol. */
    final class RemoteHttpExchange implements HttpExchange {
        private final int streamId;
        private final JsonObject request;
        private final boolean upgrade;
        private final BodyStream body;
        private final Promise<Boolean> closedPromise = Promise.promise();
        private io.vertx.core.streams.WriteStream<Buffer> responseOut;

        RemoteHttpExchange(OpenStreamMessage open) {
            this.streamId = open.streamId();
            this.request = open.metadata().getJsonObject("request", new JsonObject());
            this.upgrade = open.metadata().getBoolean("upgrade", false);
            // The node resets the request stream when the client leaves before the response ends (Node's 'close', D-123).
            this.body = new ReadStreamBodyStream(connection.streamMultiplexer().createInputStream(streamId))
                    .onError(err -> closedPromise.tryComplete(true));
        }

        int streamId() {
            return streamId;
        }

        @Override
        public JsonObject request() {
            return request;
        }

        @Override
        public BodyStream body() {
            return body;
        }

        @Override
        public void respond(int status, JsonObject headers) {
            openResponse(new JsonObject().put("status", status).put("headers", headers != null ? headers : new JsonObject()));
        }

        @Override
        public void respond(int status, String statusMessage, JsonArray rawHeaders) {
            openResponse(new JsonObject().put("status", status).put("statusMessage", statusMessage).put("rawHeaders", rawHeaders));
        }

        private void openResponse(JsonObject meta) {
            if (responseOut != null) {
                return;
            }
            int responseStreamId = connection.nextStreamId();
            JsonObject openMeta = meta.copy().put("runId", runId).put("requestStreamId", streamId);
            connection.streamMultiplexer().openStream(responseStreamId, "http.response", openMeta);
            responseOut = connection.streamMultiplexer().createOutputStream(responseStreamId);
            // The node resets this stream when the client leaves before the response ends (Node's 'close', D-123).
            responseOut.exceptionHandler(err -> closedPromise.tryComplete(true));
        }

        @Override
        public Future<Boolean> closed() {
            return closedPromise.future();
        }

        @Override
        public Future<Void> write(Buffer chunk) {
            return responseOut == null ? Future.failedFuture(new IllegalStateException("respond() must be called first")) : responseOut.write(chunk);
        }

        @Override
        public void trailers(JsonArray rawTrailers) {
            // The node owns the client connection, so hand the trailers over before end(), findable by this exchange's id.
            if (rawTrailers == null || rawTrailers.isEmpty()) {
                return;
            }
            call("serve.trailers", new JsonObject().put("exchangeId", streamId).put("trailers", rawTrailers));
        }

        @Override
        public void end() {
            if (responseOut != null) {
                closedPromise.tryComplete(false);
                responseOut.end();
            }
        }

        @Override
        public void fail(int status, String message) {
            closedPromise.tryComplete(true);
            if (responseOut == null) {
                openResponse(new JsonObject().put("status", status).put("error", message));
            }
            connection.streamMultiplexer().resetStream(streamId, message, status);
        }

        @Override
        public OutgoingSocket upgrade() {
            if (!upgrade) {
                return null;
            }
            ReadStream<Buffer> in = connection.streamMultiplexer().createInputStream(streamId);
            var out = connection.streamMultiplexer().createOutputStream(streamId);
            return new OutgoingSocket() {
                @Override
                public JsonObject info() {
                    return new JsonObject();
                }

                @Override
                public BodyStream data() {
                    return new ReadStreamBodyStream(in);
                }

                @Override
                public Future<Void> write(Buffer chunk) {
                    return out.write(chunk);
                }

                @Override
                public Future<Void> end() {
                    return out.end();
                }

                @Override
                public Future<JsonObject> upgradeTls(JsonObject tls) {
                    return Future.failedFuture(new HostException("Error", "unavailable", "STARTTLS isn't available over the worker protocol"));
                }

                @Override
                public void setNoDelay(boolean noDelay) {
                }

                @Override
                public void setKeepAlive(boolean keepAlive) {
                }

                @Override
                public void destroy() {
                    connection.streamMultiplexer().resetStream(streamId, "destroyed", 499);
                }
            };
        }
    }
}
