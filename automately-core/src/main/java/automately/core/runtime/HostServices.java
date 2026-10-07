package automately.core.runtime;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.function.Consumer;

/**
 * What a script can do beyond computing: storage, messaging, outbound HTTP, serving HTTP. The node
 * builds one per execution, already scoped to the job's tenant, so nothing here takes a tenant from the
 * script. Calls may complete on any thread; the runtime moves results back to the isolate.
 * See docs/design/host-apis.md.
 */
public interface HostServices {

    /**
     * Runs one operation (for example {@code kv.get}). Completes with a JSON-compatible value, or fails
     * with {@link HostException} for an expected error.
     */
    Future<Object> call(String operation, JsonObject args);

    /**
     * Starts delivering inbound events of {@code kind} (for example {@code bus.subscribe} or {@code http.serve})
     * to {@code listener}. Completes once delivery is set up; the returned handle stops it.
     */
    Future<Registration> listen(String kind, JsonObject args, Consumer<Inbound> listener);

    /** Makes this run serve HTTP ({@code ctx.serve}); each request arrives as a streaming exchange. */
    Future<Registration> serve(Consumer<HttpExchange> handler);

    /** Outbound HTTP ({@code fetch}); the response body is a paused stream. */
    Future<FetchedResponse> fetch(JsonObject args);

    /** Starts writing a file ({@code files.write}); {@code args}: path, contentType. */
    Future<automately.core.files.FileWriter> createFile(JsonObject args);

    /** Opens a file for reading ({@code files.read}, {@code files.stream}); meta is the file's view, or fails if missing. */
    Future<FetchedResponse> openFile(JsonObject args);

    /** Serves a file as the response to {@code exchange}, with Range and ETag support; {@code args}: path, headers. */
    Future<Void> respondFile(HttpExchange exchange, JsonObject args);

    /** Stops every listener this execution registered. Called once the script has ended. */
    Future<Void> close();

    interface Registration {
        Future<Void> cancel();
    }

    /** No host APIs: every call fails. For tests and one-off runs. */
    HostServices NONE = new HostServices() {
        @Override
        public Future<Object> call(String operation, JsonObject args) {
            return Future.failedFuture(new HostException("Error", "unavailable", "Host API \"" + operation + "\" is not available here"));
        }

        @Override
        public Future<Registration> listen(String kind, JsonObject args, Consumer<Inbound> listener) {
            return Future.failedFuture(new HostException("Error", "unavailable", "Host API \"" + kind + "\" is not available here"));
        }

        @Override
        public Future<Registration> serve(Consumer<HttpExchange> handler) {
            return Future.failedFuture(new HostException("Error", "unavailable", "Serving HTTP is not available here"));
        }

        @Override
        public Future<FetchedResponse> fetch(JsonObject args) {
            return Future.failedFuture(new HostException("Error", "unavailable", "fetch is not available here"));
        }

        @Override
        public Future<automately.core.files.FileWriter> createFile(JsonObject args) {
            return Future.failedFuture(new HostException("Error", "unavailable", "Files are not available here"));
        }

        @Override
        public Future<FetchedResponse> openFile(JsonObject args) {
            return Future.failedFuture(new HostException("Error", "unavailable", "Files are not available here"));
        }

        @Override
        public Future<Void> respondFile(HttpExchange exchange, JsonObject args) {
            return Future.failedFuture(new HostException("Error", "unavailable", "Files are not available here"));
        }

        @Override
        public Future<Void> close() {
            return Future.succeededFuture();
        }
    };
}
