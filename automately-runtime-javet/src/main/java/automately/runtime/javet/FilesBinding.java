package automately.runtime.javet;

import automately.core.files.FileWriter;
import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.api.FilesApi;
import automately.core.runtime.api.HostArgs;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/** {@code files} in JavaScript; {@code files.js} streams uploads through it chunk by chunk. */
public final class FilesBinding {

    private final HostBridge bridge;
    private final JsValues js;
    private final FilesApi files;

    FilesBinding(HostBridge bridge, FilesApi files) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.files = files;
    }

    @V8Function(name = "stat")
    public V8ValuePromise stat(V8Value path) throws JavetException {
        return bridge.promise(() -> files.stat(js.string(path)));
    }

    /** {@code options}: {@code {prefix = '', limit = 100, cursor}}. */
    @V8Function(name = "list")
    public V8ValuePromise list(V8Value options) throws JavetException {
        return bridge.promise(() -> {
            JsonObject o = js.options(options);
            return files.list(HostArgs.string(o.getValue("prefix", "")),
                    HostArgs.intBetween(o.getValue("limit", 100), "limit", 1, FilesApi.MAX_LIST_LIMIT), HostArgs.string(o.getValue("cursor")));
        });
    }

    @V8Function(name = "delete")
    public V8ValuePromise delete(V8Value path) throws JavetException {
        return bridge.promise(() -> files.delete(js.string(path)));
    }

    /** Starts an upload; resolves with the writer's id. */
    @V8Function(name = "create")
    public V8ValuePromise create(V8Value path, V8Value contentType) throws JavetException {
        return bridge.promise(() -> files.create(js.string(path), js.string(contentType)), bridge::addWriter);
    }

    /** Stores a chunk; settles once it's stored, so the script waits instead of piling data up. */
    @V8Function(name = "write")
    public V8ValuePromise write(int writerId, V8Value data) throws JavetException {
        FileWriter writer = bridge.writer(writerId);
        byte[] bytes = HostBridge.bytes(data);
        return bridge.promise(() -> writer == null ? closed("The upload is already finished") : writer.write(Buffer.buffer(bytes)).map(v -> JsValues.UNDEFINED));
    }

    /** Publishes the upload; resolves with the file's view. */
    @V8Function(name = "finish")
    public V8ValuePromise finish(int writerId) throws JavetException {
        FileWriter writer = bridge.removeWriter(writerId);
        return bridge.promise(() -> writer == null ? closed("The upload is already finished") : writer.finish().map(info -> info.toViewJson()));
    }

    @V8Function(name = "abort")
    public void abort(int writerId) {
        FileWriter writer = bridge.removeWriter(writerId);
        if (writer != null) {
            writer.abort();
        }
    }

    /** Resolves with {@code {file, streamId}}. */
    @V8Function(name = "open")
    public V8ValuePromise open(V8Value path) throws JavetException {
        return bridge.promise(() -> files.open(js.string(path)),
                opened -> new JsonObject().put("file", opened.meta()).put("streamId", bridge.openStream(opened.body())));
    }

    /** Hands the response over to the file responder (Range, ETag); the bytes never pass through the script. */
    @V8Function(name = "respond")
    public V8ValuePromise respond(int exchangeId, V8Value path, V8Value headers) throws JavetException {
        HttpExchange exchange = bridge.finish(exchangeId);
        return bridge.promise(() -> exchange == null ? closed("The response is already finished")
                : files.respond(exchange, js.string(path), js.options(headers)).map(v -> JsValues.UNDEFINED));
    }

    private static <T> Future<T> closed(String message) {
        return Future.failedFuture(new HostException("Error", "closed", message));
    }
}
