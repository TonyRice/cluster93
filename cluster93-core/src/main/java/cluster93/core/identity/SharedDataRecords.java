package cluster93.core.identity;

import io.vertx.core.Future;
import cluster93.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** {@link Records} on a Vert.x shared map, for a single in-memory node. Lookups scan, which is fine at that size. */
public final class SharedDataRecords implements Records {

    private static final int MAX_UPDATE_ATTEMPTS = 50;

    private final SharedMap<String, JsonObject> map;
    private final Kind kind;

    public SharedDataRecords(Vertx vertx, Kind kind) {
        this.kind = kind;
        this.map = new SharedMap<>(vertx, "cluster93.records." + kind.name());
    }

    public static Factory factory(Vertx vertx) {
        return kind -> new SharedDataRecords(vertx, kind);
    }

    @Override
    public Future<JsonObject> get(String key) {
        return map.compose(m -> m.get(key)).map(json -> json == null ? null : json.copy());
    }

    @Override
    public Future<Void> put(String key, JsonObject value) {
        return map.compose(m -> m.put(key, value.copy()));
    }

    @Override
    public Future<Boolean> putIfAbsent(String key, JsonObject value) {
        return map.compose(m -> m.putIfAbsent(key, value.copy())).map(previous -> previous == null);
    }

    @Override
    public Future<JsonObject> remove(String key) {
        return map.compose(m -> m.remove(key));
    }

    @Override
    public Future<Optional<JsonObject>> update(String key, Function<JsonObject, Optional<JsonObject>> change) {
        return update(key, change, 1);
    }

    private Future<Optional<JsonObject>> update(String key, Function<JsonObject, Optional<JsonObject>> change, int tries) {
        return map.compose(m -> m.get(key).compose(current -> {
            if (current == null) {
                return Future.succeededFuture(Optional.empty());
            }
            Optional<JsonObject> next = change.apply(current.copy());
            if (next.isEmpty()) {
                return Future.succeededFuture(Optional.empty());
            }
            return m.replaceIfPresent(key, current, next.get().copy()).compose(replaced -> {
                if (replaced) {
                    return Future.succeededFuture(next);
                }
                return tries >= MAX_UPDATE_ATTEMPTS
                        ? Future.failedFuture("Gave up updating " + kind.name() + "/" + key + " after " + tries + " conflicting writes")
                        : update(key, change, tries + 1);
            });
        }));
    }

    @Override
    public Future<List<JsonObject>> where(String field, String value) {
        if (!kind.indexed().contains(field)) {
            return Future.failedFuture(new IllegalArgumentException(kind.name() + " isn't indexed by " + field));
        }
        return all().map(rows -> rows.stream().filter(json -> value.equals(json.getString(field))).toList());
    }

    @Override
    public Future<List<JsonObject>> all() {
        return map.compose(AsyncMap::values).map(values -> values.stream().map(JsonObject::copy).toList());
    }
}
