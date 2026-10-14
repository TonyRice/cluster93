package cluster93.core.runtime.api;

import cluster93.core.files.FileWriter;
import cluster93.core.runtime.FetchedResponse;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.HttpExchange;
import cluster93.core.runtime.Inbound;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.function.Consumer;

/** {@link HostApis#NONE}: every API exists and every call fails with {@code unavailable}. */
final class Unavailable {

    private Unavailable() {
    }

    static <T> Future<T> fail(String what) {
        return Future.failedFuture(new HostException("Error", "unavailable", what + " is not available here"));
    }

    static final SecretsApi SECRETS = name -> fail("Secrets");

    static final FsApi FS = new FsApi() {
        @Override
        public Future<io.vertx.core.json.JsonObject> stat(String path) {
            return fail("The file system");
        }

        @Override
        public Future<io.vertx.core.json.JsonArray> readdir(String path) {
            return fail("The file system");
        }

        @Override
        public Future<Void> mkdir(String path, boolean recursive) {
            return fail("The file system");
        }

        @Override
        public Future<Void> rmdir(String path, boolean recursive) {
            return fail("The file system");
        }

        @Override
        public Future<Void> unlink(String path) {
            return fail("The file system");
        }

        @Override
        public Future<Void> rename(String from, String to) {
            return fail("The file system");
        }

        @Override
        public Future<Void> copyFile(String from, String to, boolean overwrite) {
            return fail("The file system");
        }

        @Override
        public Future<io.vertx.core.buffer.Buffer> readFile(String path) {
            return fail("The file system");
        }

        @Override
        public Future<Void> writeFile(String path, io.vertx.core.buffer.Buffer data, String contentType) {
            return fail("The file system");
        }

        @Override
        public Future<Void> appendFile(String path, io.vertx.core.buffer.Buffer data) {
            return fail("The file system");
        }

        @Override
        public Future<io.vertx.core.json.JsonObject> lock(String path, long ttlMs) {
            return fail("The file system");
        }

        @Override
        public Future<io.vertx.core.json.JsonObject> renewLock(String path, String token, long ttlMs) {
            return fail("The file system");
        }

        @Override
        public Future<Boolean> unlock(String path, String token) {
            return fail("The file system");
        }
    };

    static final KvApi KV = new KvApi() {
        @Override
        public Future<Object> get(String namespace, String key) {
            return fail("kv");
        }

        @Override
        public Future<Void> set(String namespace, String key, Object value, Long ttlMs) {
            return fail("kv");
        }

        @Override
        public Future<Boolean> delete(String namespace, String key) {
            return fail("kv");
        }

        @Override
        public Future<JsonObject> list(String namespace, String prefix, int limit, String cursor) {
            return fail("kv");
        }

        @Override
        public Future<Long> increment(String namespace, String key, long by) {
            return fail("kv");
        }
    };

    static final BusApi BUS = new BusApi() {
        @Override
        public Future<Void> publish(String topic, Object message) {
            return fail("The bus");
        }

        @Override
        public Future<Object> request(String topic, Object message, long timeoutMs) {
            return fail("The bus");
        }

        @Override
        public Future<Registration> subscribe(String topic, Consumer<Inbound> listener) {
            return fail("The bus");
        }

        @Override
        public Future<Registration> handle(String topic, Consumer<Inbound> listener) {
            return fail("The bus");
        }
    };

    static final QueueApi QUEUE = new QueueApi() {
        @Override
        public Future<String> push(String queue, Object message, Long delayMs, String key, Integer maxAttempts) {
            return fail("Queues");
        }

        @Override
        public Future<Registration> consume(String queue, int concurrency, Consumer<Inbound> listener) {
            return fail("Queues");
        }

        @Override
        public Future<JsonObject> stats(String queue) {
            return fail("Queues");
        }

        @Override
        public Future<JsonObject> dead(String queue, int limit, String cursor) {
            return fail("Queues");
        }

        @Override
        public Future<Boolean> retry(String queue, String id) {
            return fail("Queues");
        }

        @Override
        public Future<Boolean> discard(String queue, String id) {
            return fail("Queues");
        }
    };

    static final FilesApi FILES = new FilesApi() {
        @Override
        public Future<JsonObject> stat(String path) {
            return fail("Files");
        }

        @Override
        public Future<JsonObject> list(String prefix, int limit, String cursor) {
            return fail("Files");
        }

        @Override
        public Future<Boolean> delete(String path) {
            return fail("Files");
        }

        @Override
        public Future<FileWriter> create(String path, String contentType) {
            return fail("Files");
        }

        @Override
        public Future<FetchedResponse> open(String path) {
            return fail("Files");
        }

        @Override
        public Future<Void> respond(HttpExchange exchange, String path, JsonObject headers) {
            exchange.fail(500, "Files are not available here");
            return fail("Files");
        }
    };

    static final FetchApi FETCH = (request, body) -> fail("fetch");

    static final JobsApi JOBS = new JobsApi() {
        @Override
        public Future<Void> setCallback(String url, Object data) {
            return fail("Jobs");
        }

        @Override
        public Future<JsonObject> run(String script, JsonObject input, JsonObject options) {
            return fail("Jobs");
        }

        @Override
        public Future<JsonObject> get(String id) {
            return fail("Jobs");
        }

        @Override
        public Future<JsonObject> result(String id) {
            return fail("Jobs");
        }

        @Override
        public Future<JsonObject> stop(String id, String reason) {
            return fail("Jobs");
        }

        @Override
        public Future<Registration> events(String id, Consumer<Inbound> listener) {
            return fail("Jobs");
        }
    };

    static final HttpApi HTTP = handler -> fail("Serving HTTP");

    static final HostApis APIS = new HostApis() {
        @Override
        public KvApi kv() {
            return KV;
        }

        @Override
        public BusApi bus() {
            return BUS;
        }

        @Override
        public QueueApi queue() {
            return QUEUE;
        }

        @Override
        public JobsApi jobs() {
            return JOBS;
        }

        @Override
        public FilesApi files() {
            return FILES;
        }

        @Override
        public FetchApi fetch() {
            return FETCH;
        }

        @Override
        public HttpApi http() {
            return HTTP;
        }

        @Override
        public Future<Void> close() {
            return Future.succeededFuture();
        }
    };
}
