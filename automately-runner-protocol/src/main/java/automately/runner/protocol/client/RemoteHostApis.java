package automately.runner.protocol.client;

import automately.core.files.FileWriter;
import automately.core.host.JvmCrypto;
import automately.core.host.JvmZlib;
import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.Inbound;
import automately.core.runtime.OutgoingRequest;
import automately.core.runtime.OutgoingSocket;
import automately.core.runtime.api.BusApi;
import automately.core.runtime.api.CryptoApi;
import automately.core.runtime.api.DnsApi;
import automately.core.runtime.api.FetchApi;
import automately.core.runtime.api.FilesApi;
import automately.core.runtime.api.FsApi;
import automately.core.runtime.api.HostApis;
import automately.core.runtime.api.HttpApi;
import automately.core.runtime.api.JobsApi;
import automately.core.runtime.api.KvApi;
import automately.core.runtime.api.QueueApi;
import automately.core.runtime.api.Registration;
import automately.core.runtime.api.SecretsApi;
import automately.core.runtime.api.SocketApi;
import automately.core.runtime.api.ZlibApi;
import automately.runner.protocol.message.CallMessage;
import automately.runner.protocol.message.ResultMessage;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Worker-side proxy implementing {@link HostApis} by sending RPC {@link CallMessage}s to the node (worker-node-protocol.md §6).
 * Pure computation ({@link #crypto()}, {@link #zlib()}) executes in-process on the worker.
 */
public final class RemoteHostApis implements HostApis {

    private final WorkerClientConnection connection;
    private final String runId;

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

    public RemoteHostApis(WorkerClientConnection connection, String runId) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.runId = Objects.requireNonNull(runId, "runId");

        this.kv = new RemoteKv();
        this.bus = new RemoteBus();
        this.queue = new RemoteQueue();
        this.jobs = new RemoteJobs();
        this.files = new RemoteFiles();
        this.fs = new RemoteFs();
        this.fetch = new RemoteFetch();
        this.sockets = new RemoteSocket();
        this.dns = new RemoteDns();
        this.secrets = new RemoteSecrets();
        this.http = new RemoteHttp();
    }

    private Future<ResultMessage> call(String method, JsonObject params) {
        return call(method, params, Buffer.buffer());
    }

    private Future<ResultMessage> call(String method, JsonObject params, Buffer binary) {
        return connection.call(new CallMessage(connection.nextCallId(), runId, method, params, binary));
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
            return call("bus.subscribe", new JsonObject().put("topic", topic))
                    .map(r -> () -> call("bus.unsubscribe", new JsonObject().put("topic", topic)).mapEmpty());
        }

        @Override
        public Future<Registration> handle(String topic, Consumer<Inbound> listener) {
            return call("bus.handle", new JsonObject().put("topic", topic))
                    .map(r -> () -> call("bus.unhandle", new JsonObject().put("topic", topic)).mapEmpty());
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
            return call("queue.consume", params).map(r -> () -> call("queue.unconsume", params).mapEmpty());
        }

        @Override
        public Future<JsonObject> stats(String queue) {
            return call("queue.stats", new JsonObject().put("queue", queue))
                    .map(r -> r.value() instanceof JsonObject o ? o : new JsonObject());
        }

        @Override
        public Future<JsonObject> dead(String queue, int limit, String cursor) {
            JsonObject params = new JsonObject().put("queue", queue).put("limit", limit);
            if (cursor != null) params.put("cursor", cursor);
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
            return call("jobs.events", new JsonObject().put("id", id))
                    .map(r -> () -> call("jobs.unevents", new JsonObject().put("id", id)).mapEmpty());
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
            int streamId = connection.nextCallId();
            JsonObject meta = new JsonObject()
                    .put("runId", runId)
                    .put("path", path)
                    .put("contentType", contentType);

            return connection.streamMultiplexer().openStream(streamId, "files.create", meta)
                    .map(v -> {
                        var outStream = connection.streamMultiplexer().createOutputStream(streamId);
                        return new FileWriter() {
                            @Override
                            public Future<Void> write(Buffer data) {
                                return outStream.write(data);
                            }

                            @Override
                            public Future<automately.core.files.FileInfo> finish() {
                                return outStream.end().compose(vEnd -> {
                                    return call("files.stat", new JsonObject().put("path", path))
                                            .map(res -> {
                                                if (res.value() instanceof JsonObject obj) {
                                                    return automately.core.files.FileInfo.fromJson(obj);
                                                }
                                                return null;
                                            });
                                });
                            }

                            @Override
                            public Future<Void> abort() {
                                return connection.streamMultiplexer().resetStream(streamId, "ABORTED", 499);
                            }
                        };
                    });
        }

        @Override
        public Future<FetchedResponse> open(String path) {
            return Future.failedFuture(new UnsupportedOperationException("Streaming file open is handled via stream frames"));
        }

        @Override
        public Future<Void> respond(HttpExchange exchange, String path, JsonObject headers) {
            return call("files.respond", new JsonObject().put("path", path).put("headers", headers)).mapEmpty();
        }
    }

    private final class RemoteFetch implements FetchApi {
        @Override
        public Future<FetchedResponse> fetch(JsonObject request, byte[] body) {
            return Future.failedFuture(new UnsupportedOperationException("Streaming fetch is handled via stream frames"));
        }

        @Override
        public Future<OutgoingRequest> open(JsonObject request) {
            return Future.failedFuture(new UnsupportedOperationException("Streaming fetch open is handled via stream frames"));
        }
    }

    private final class RemoteSocket implements SocketApi {
        @Override
        public Future<OutgoingSocket> connect(JsonObject request) {
            return Future.failedFuture(new UnsupportedOperationException("Socket streaming is handled via stream frames"));
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
            return call("serve.listen", new JsonObject()).map(r -> () -> Future.succeededFuture());
        }
    }
}
