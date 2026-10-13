package cluster93.core.host;

import cluster93.core.files.FileInfo;
import cluster93.core.files.FileResponder;
import cluster93.core.files.FileStore;
import cluster93.core.files.FileWriter;
import cluster93.core.files.Room;
import cluster93.core.files.StorageCapacity;
import cluster93.core.runtime.FetchedResponse;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.HttpExchange;
import cluster93.core.runtime.api.FilesApi;
import cluster93.core.storage.StorageRoom;
import cluster93.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import static cluster93.core.host.Names.guarded;

/** {@code files} on the node's {@link FileStore}; uploads are counted against the tenant's quota as they finish. */
final class NodeFilesApi implements FilesApi {

    private final HostScope scope;
    private final FileStore files;
    private final StorageCapacity capacity;
    private final TenantQuota quota;
    private final Room room;

    NodeFilesApi(HostScope scope, FileStore files, StorageCapacity capacity, TenantQuota quota, Room room) {
        this.scope = scope;
        this.files = files;
        this.capacity = capacity;
        this.quota = quota;
        this.room = room;
    }

    @Override
    public Future<JsonObject> stat(String path) {
        return guarded(() -> store().stat(scope.tenant(), path(path)).map(info -> info == null ? null : info.toViewJson()));
    }

    @Override
    public Future<JsonObject> list(String prefix, int limit, String cursor) {
        return guarded(() -> store().list(scope.tenant(), prefix == null ? "" : prefix, Names.limit(limit, MAX_LIST_LIMIT), cursor));
    }

    @Override
    public Future<Boolean> delete(String path) {
        return guarded(() -> store().delete(scope.tenant(), path(path)));
    }

    @Override
    public Future<FileWriter> create(String path, String contentType) {
        return guarded(() -> {
            String p = path(path);
            FileStore store = store();
            return capacity.ensureRoom()
                    .compose(ok -> store.create(scope.tenant(), p, contentType, room))
                    .map(this::counted);
        });
    }

    @Override
    public Future<FetchedResponse> open(String path) {
        return guarded(() -> {
            String p = path(path);
            FileStore store = store();
            return store.stat(scope.tenant(), p).compose(info -> info == null
                    ? Future.failedFuture(new HostException("Error", "not_found", "No such file: " + p))
                    : Future.succeededFuture(new FetchedResponse(info.toViewJson(), store.open(info, 0, info.size() - 1))));
        });
    }

    @Override
    public Future<Void> respond(HttpExchange exchange, String path, JsonObject headers) {
        try {
            JsonObject request = exchange.request();
            return FileResponder.respond(store(), scope.tenant(), path(path), request.getString("method"),
                    request.getJsonObject("headers", new JsonObject()), headers, exchange);
        } catch (HostException e) {
            exchange.fail(500, e.getMessage());
            return Future.failedFuture(e);
        }
    }

    private FileStore store() {
        if (files == null) {
            throw new HostException("Error", "unavailable", "Files are not available here");
        }
        return files;
    }

    private static String path(String path) {
        String invalid = FileStore.invalidPath(path);
        if (invalid != null) {
            throw HostException.invalid(invalid);
        }
        return path;
    }

    /**
     * A writer whose finished upload is counted towards the tenant's quota straight away, and which is discarded if
     * the script ends without finishing it.
     */
    private FileWriter counted(FileWriter writer) {
        Runnable discard = writer::abort;
        scope.onClose(discard);
        return new FileWriter() {
            @Override
            public Future<Void> write(Buffer data) {
                return writer.write(data);
            }

            @Override
            public Future<FileInfo> finish() {
                scope.forget(discard);
                return writer.finish().onSuccess(info -> {
                    if (StorageRoom.countsAgainstQuota(info.backend())) {
                        quota.added(scope.tenant(), info.size());
                    }
                });
            }

            @Override
            public Future<Void> abort() {
                scope.forget(discard);
                return writer.abort();
            }
        };
    }
}
