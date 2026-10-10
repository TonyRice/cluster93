package automately.runtime.js;

import automately.core.files.FileWriter;
import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.api.FilesApi;
import automately.core.runtime.api.HostArgs;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/** {@code files} in JavaScript; {@code files.js} streams uploads through it chunk by chunk. */
public final class FilesBinding {

    private final HostBridge bridge;
    private final FilesApi files;

    FilesBinding(HostBridge bridge, FilesApi files) {
        this.bridge = bridge;
        this.files = files;
    }

    @HostFunction(name = "stat")
    public JsHandle stat(JsArg path) throws JsEngineException {
        return bridge.promise(() -> files.stat(path.string()));
    }

    /** {@code options}: {@code {prefix = '', limit = 100, cursor}}. */
    @HostFunction(name = "list")
    public JsHandle list(JsArg options) throws JsEngineException {
        return bridge.promise(() -> {
            JsonObject o = options.options();
            return files.list(HostArgs.string(o.getValue("prefix", "")),
                    HostArgs.intBetween(o.getValue("limit", 100), "limit", 1, FilesApi.MAX_LIST_LIMIT), HostArgs.string(o.getValue("cursor")));
        });
    }

    @HostFunction(name = "delete")
    public JsHandle delete(JsArg path) throws JsEngineException {
        return bridge.promise(() -> files.delete(path.string()));
    }

    /** Starts an upload; resolves with the writer's id. */
    @HostFunction(name = "create")
    public JsHandle create(JsArg path, JsArg contentType) throws JsEngineException {
        return bridge.promise(() -> files.create(path.string(), contentType.string()), bridge::addWriter);
    }

    /** Stores a chunk; settles once it's stored, so the script waits instead of piling data up. */
    @HostFunction(name = "write")
    public JsHandle write(int writerId, JsArg data) throws JsEngineException {
        FileWriter writer = bridge.writer(writerId);
        byte[] bytes = data.bytes();
        return bridge.promise(() -> writer == null ? closed("The upload is already finished") : writer.write(Buffer.buffer(bytes)).map(v -> Js.UNDEFINED));
    }

    /** Publishes the upload; resolves with the file's view. */
    @HostFunction(name = "finish")
    public JsHandle finish(int writerId) throws JsEngineException {
        FileWriter writer = bridge.removeWriter(writerId);
        return bridge.promise(() -> writer == null ? closed("The upload is already finished") : writer.finish().map(info -> info.toViewJson()));
    }

    @HostFunction(name = "abort")
    public void abort(int writerId) {
        FileWriter writer = bridge.removeWriter(writerId);
        if (writer != null) {
            writer.abort();
        }
    }

    /** Resolves with {@code {file, streamId}}. */
    @HostFunction(name = "open")
    public JsHandle open(JsArg path) throws JsEngineException {
        return bridge.promise(() -> files.open(path.string()),
                opened -> new JsonObject().put("file", opened.meta()).put("streamId", bridge.openStream(opened.body())));
    }

    /** Hands the response over to the file responder (Range, ETag); the bytes never pass through the script. */
    @HostFunction(name = "respond")
    public JsHandle respond(int exchangeId, JsArg path, JsArg headers) throws JsEngineException {
        HttpExchange exchange = bridge.finish(exchangeId);
        return bridge.promise(() -> exchange == null ? closed("The response is already finished")
                : files.respond(exchange, path.string(), headers.options()).map(v -> Js.UNDEFINED));
    }

    private static <T> Future<T> closed(String message) {
        return Future.failedFuture(new HostException("Error", "closed", message));
    }
}
