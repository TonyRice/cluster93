package automately.cluster.ignite.store;

import automately.core.identity.Records;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link Records} as one durable table each. Every indexed field gets its own indexed column, named {@code <field>Key}
 * so field names that are SQL words ({@code user}) stay usable.
 */
public final class IgniteRecords implements Records {

    private final Table table;
    private final Kind kind;

    IgniteRecords(Vertx vertx, Ignite ignite, Kind kind) {
        this.kind = kind;
        this.table = new Table(vertx, ignite, definition(kind), json -> {
            Map<String, Object> columns = new HashMap<>();
            kind.indexed().forEach(field -> columns.put(column(field), json.getString(field)));
            return columns;
        });
    }

    static Table.Definition definition(Kind kind) {
        LinkedHashMap<String, Class<?>> columns = new LinkedHashMap<>();
        kind.indexed().forEach(field -> columns.put(column(field), String.class));
        return new Table.Definition(kind.name(), columns, kind.indexed().stream().map(field -> Table.index(column(field))).toList());
    }

    private static String column(String field) {
        return field + "Key";
    }

    @Override
    public Future<JsonObject> get(String key) {
        return table.get(key);
    }

    @Override
    public Future<Void> put(String key, JsonObject value) {
        return table.put(key, value);
    }

    @Override
    public Future<Boolean> putIfAbsent(String key, JsonObject value) {
        return table.putIfAbsent(key, value);
    }

    @Override
    public Future<JsonObject> remove(String key) {
        return table.remove(key);
    }

    @Override
    public Future<Optional<JsonObject>> update(String key, Function<JsonObject, Optional<JsonObject>> change) {
        return table.update(key, change);
    }

    @Override
    public Future<List<JsonObject>> where(String field, String value) {
        if (!kind.indexed().contains(field)) {
            return Future.failedFuture(new IllegalArgumentException(kind.name() + " isn't indexed by " + field));
        }
        return table.queryJson("SELECT json FROM " + kind.name() + " WHERE " + column(field) + " = ?", value);
    }

    @Override
    public Future<List<JsonObject>> all() {
        return table.queryJson("SELECT json FROM " + kind.name());
    }
}
