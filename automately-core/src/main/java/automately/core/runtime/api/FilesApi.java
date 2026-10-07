package automately.core.runtime.api;

import automately.core.files.FileWriter;
import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HttpExchange;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/** The tenant's files (docs/scripting/host-apis.md). File views are the API's JSON shape. */
public interface FilesApi {

    int MAX_LIST_LIMIT = 1_000;

    /** The file's view, or null if there's none. */
    Future<JsonObject> stat(String path);

    /** {@code {files: [...], cursor}}. */
    Future<JsonObject> list(String prefix, int limit, String cursor);

    Future<Boolean> delete(String path);

    /** Starts an upload; finishing it publishes the file. */
    Future<FileWriter> create(String path, String contentType);

    /** The file's view as meta and its contents as a paused stream; fails with {@code not_found}. */
    Future<FetchedResponse> open(String path);

    /** Answers {@code exchange} with the file, honouring Range and conditional headers; {@code headers} are added. */
    Future<Void> respond(HttpExchange exchange, String path, JsonObject headers);
}
