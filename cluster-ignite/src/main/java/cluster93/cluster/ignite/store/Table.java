package cluster93.cluster.ignite.store;

import cluster93.cluster.ignite.IgniteCluster;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryObjectBuilder;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.QueryIndex;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.lang.IgniteFuture;

import javax.cache.expiry.CreatedExpiryPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * One durable, replicated table: rows keyed by string, each holding the record as JSON plus a few indexed
 * columns for SQL. Writes are atomic per key; {@link #update} is compare-and-set on the whole row, the same
 * contract the in-memory stores have (D-011). Lives in the durable data region (D-030).
 */
final class Table {

    static final String SCHEMA = "CLUSTER93";
    static final int MAX_UPDATE_ATTEMPTS = 50;
    /** Upper bound for the random pause between compare-and-set retries, in ms. */
    static final int MAX_BACKOFF_MS = 50;

    /** A table's shape: name, typed columns (besides the JSON), and indexes. */
    record Definition(String name, LinkedHashMap<String, Class<?>> columns, List<QueryIndex> indexes) {
    }

    private final Vertx vertx;
    private final String name;
    private final IgniteCache<String, BinaryObject> cache;
    private final Ignite ignite;
    private final Function<JsonObject, Map<String, Object>> columnsOf;
    private final List<String> columns;

    Table(Vertx vertx, Ignite ignite, Definition definition, Function<JsonObject, Map<String, Object>> columnsOf) {
        this.vertx = vertx;
        this.ignite = ignite;
        this.name = definition.name();
        this.cache = ignite.cache(definition.name()).withKeepBinary();
        this.columnsOf = columnsOf;
        this.columns = List.copyOf(definition.columns().keySet());
    }

    /**
     * Registers the table's one binary schema with the cluster. Rows always carry every column in the declared order
     * (nulls included), so this is the only schema a row can have, and none is ever first sent later (D-042).
     */
    static void registerSchema(Ignite ignite, Definition definition) {
        ignite.binary().toBinary(build(ignite, definition.name(), List.copyOf(definition.columns().keySet()), "{}", Map.of()));
    }

    /** Creates the table's cache if it doesn't exist. Blocking; called once at startup. */
    static void create(Ignite ignite, Definition definition, int backups) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("json", String.class.getName());
        definition.columns().forEach((column, type) -> fields.put(column, type.getName()));
        QueryEntity entity = new QueryEntity(String.class.getName(), definition.name())
                .setTableName(definition.name())
                .setFields(fields)
                .setIndexes(definition.indexes());
        CacheConfiguration<String, BinaryObject> config = new CacheConfiguration<String, BinaryObject>(definition.name())
                .setCacheMode(CacheMode.PARTITIONED)
                .setBackups(backups)
                .setAffinity(new org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction(false, IgniteCluster.PARTITIONS))
                .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                // A write completes once every copy has it, so a read on any node sees it (D-041).
                .setWriteSynchronizationMode(org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC)
                .setDataRegionName(IgniteCluster.DATA_REGION)
                .setSqlSchema(SCHEMA)
                .setQueryEntities(List.of(entity))
                .setEagerTtl(true);
        // Right after activation (first start or restart) Ignite can still be finishing the state change.
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                ignite.getOrCreateCache(config);
                addMissingColumns(ignite, definition);
                return;
            } catch (javax.cache.CacheException e) {
                if (System.currentTimeMillis() > deadline || !String.valueOf(e.getMessage()).contains("state change is in progress")) {
                    throw e;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * A table created by an older version keeps the columns it had: Ignite ignores a new definition for an existing
     * cache. Columns added since are added with SQL DDL, with any index that uses them, so data directories carry over
     * across upgrades. Rows written before have nulls there until something rewrites them.
     */
    private static void addMissingColumns(Ignite ignite, Definition definition) {
        IgniteCache<String, BinaryObject> cache = ignite.cache(definition.name()).withKeepBinary();
        @SuppressWarnings("unchecked")
        CacheConfiguration<String, BinaryObject> current = cache.getConfiguration(CacheConfiguration.class);
        java.util.Set<String> existing = new java.util.HashSet<>();
        current.getQueryEntities().forEach(entity -> entity.getFields().keySet().forEach(field -> existing.add(field.toUpperCase(java.util.Locale.ROOT))));
        java.util.Set<String> added = new java.util.HashSet<>();
        definition.columns().forEach((column, type) -> {
            if (!existing.contains(column.toUpperCase(java.util.Locale.ROOT))) {
                ddl(cache, "ALTER TABLE " + SCHEMA + "." + definition.name() + " ADD COLUMN IF NOT EXISTS " + column + " " + sqlType(type));
                added.add(column);
            }
        });
        for (QueryIndex index : definition.indexes()) {
            if (index.getFields().keySet().stream().anyMatch(added::contains)) {
                String indexName = definition.name() + "_" + String.join("_", index.getFields().keySet()) + "_IDX";
                ddl(cache, "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + SCHEMA + "." + definition.name()
                        + " (" + String.join(", ", index.getFields().keySet()) + ")");
            }
        }
    }

    private static void ddl(IgniteCache<String, BinaryObject> cache, String sql) {
        try (var cursor = cache.query(new SqlFieldsQuery(sql).setSchema(SCHEMA))) {
            cursor.getAll();
        }
    }

    private static String sqlType(Class<?> type) {
        if (type == String.class) {
            return "VARCHAR";
        }
        if (type == Long.class) {
            return "BIGINT";
        }
        if (type == Integer.class) {
            return "INT";
        }
        if (type == Boolean.class) {
            return "BOOLEAN";
        }
        throw new IllegalArgumentException("No SQL type for " + type);
    }

    String name() {
        return name;
    }

    Future<JsonObject> get(String key) {
        return async(() -> cache.getAsync(key)).map(Table::json);
    }

    Future<Void> put(String key, JsonObject json) {
        return async(() -> cache.putAsync(key, row(json))).mapEmpty();
    }

    /** Put with a time to live; the row's own expiry column stays authoritative for reads. */
    Future<Void> put(String key, JsonObject json, long ttlMs) {
        return async(() -> cache.withExpiryPolicy(new CreatedExpiryPolicy(new javax.cache.expiry.Duration(java.util.concurrent.TimeUnit.MILLISECONDS, ttlMs)))
                .putAsync(key, row(json))).mapEmpty();
    }

    /** Stores the row and completes with what it replaced, or null. */
    Future<JsonObject> getAndPut(String key, JsonObject json) {
        return async(() -> cache.getAndPutAsync(key, row(json))).map(Table::json);
    }

    Future<Boolean> putIfAbsent(String key, JsonObject json) {
        return async(() -> cache.putIfAbsentAsync(key, row(json)));
    }

    /** Removes the row and completes with what it held, or null. */
    Future<JsonObject> remove(String key) {
        return async(() -> cache.getAndRemoveAsync(key)).map(Table::json);
    }

    Future<Boolean> removeIfUnchanged(String key, JsonObject expected) {
        return async(() -> cache.getAsync(key)).compose(current -> current == null || !expected.equals(json(current))
                ? Future.succeededFuture(false)
                : async(() -> cache.removeAsync(key, current)));
    }

    /**
     * Compare-and-set: reads the row, applies {@code change}, and writes only if nobody changed it in between,
     * retrying on conflict. Empty when the row doesn't exist or the change doesn't apply.
     */
    Future<Optional<JsonObject>> update(String key, Function<JsonObject, Optional<JsonObject>> change) {
        return update(key, change, 1);
    }

    private Future<Optional<JsonObject>> update(String key, Function<JsonObject, Optional<JsonObject>> change, int tries) {
        return async(() -> cache.getAsync(key)).compose(current -> {
            if (current == null) {
                return Future.succeededFuture(Optional.empty());
            }
            Optional<JsonObject> next = change.apply(json(current));
            if (next.isEmpty()) {
                return Future.succeededFuture(Optional.empty());
            }
            return async(() -> cache.replaceAsync(key, current, row(next.get()))).compose(replaced -> {
                if (replaced) {
                    return Future.succeededFuture(next);
                }
                if (tries >= MAX_UPDATE_ATTEMPTS) {
                    return Future.failedFuture("Gave up updating " + name + "/" + key + " after " + tries + " conflicting writes");
                }
                // Jittered backoff: under contention, immediate retries just collide again.
                Promise<Optional<JsonObject>> retried = Promise.promise();
                long pause = 1 + java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.min(MAX_BACKOFF_MS, 1L << Math.min(tries, 6)));
                vertx.setTimer(pause, id -> update(key, change, tries + 1).onComplete(retried));
                return retried.future();
            });
        });
    }

    /** Runs SQL (off the event loop) and returns the first column of each row as JSON. */
    Future<List<JsonObject>> queryJson(String sql, Object... args) {
        return query(sql, args).map(rows -> rows.stream().map(r -> new JsonObject((String) r.getFirst())).toList());
    }

    /** Runs SQL (off the event loop) and returns raw rows. */
    Future<List<List<?>>> query(String sql, Object... args) {
        return retrying(() -> vertx.executeBlocking(() -> {
            List<List<?>> rows = new ArrayList<>();
            try (var cursor = cache.query(new SqlFieldsQuery(sql).setSchema(SCHEMA).setArgs(args))) {
                cursor.forEach(rows::add);
            }
            return rows;
        }, false), 1);
    }

    private BinaryObject row(JsonObject json) {
        return build(ignite, name, columns, json.encode(), columnsOf.apply(json));
    }

    /** Field order is part of a binary schema, so it's always json, then the columns in declared order. */
    private static BinaryObject build(Ignite ignite, String type, List<String> columns, String json, Map<String, Object> values) {
        BinaryObjectBuilder builder = ignite.binary().builder(type).setField("json", json);
        for (String column : columns) {
            builder.setField(column, values.get(column));
        }
        return builder.build();
    }

    private static JsonObject json(BinaryObject row) {
        return row == null ? null : new JsonObject((String) row.field("json"));
    }

    private <T> Future<T> async(java.util.function.Supplier<IgniteFuture<T>> operation) {
        return IgniteFutures.async(vertx, operation);
    }

    private <T> Future<T> retrying(java.util.function.Supplier<Future<T>> operation, int attempt) {
        return IgniteFutures.retrying(vertx, operation, attempt);
    }

    static QueryIndex index(String... columns) {
        QueryIndex index = new QueryIndex();
        LinkedHashMap<String, Boolean> fields = new LinkedHashMap<>();
        for (String column : columns) {
            fields.put(column, true);
        }
        return index.setFields(fields).setIndexType(org.apache.ignite.cache.QueryIndexType.SORTED);
    }

    static LinkedHashMap<String, Class<?>> columns(Object... nameTypePairs) {
        LinkedHashMap<String, Class<?>> columns = new LinkedHashMap<>();
        for (int i = 0; i < nameTypePairs.length; i += 2) {
            columns.put((String) nameTypePairs[i], (Class<?>) nameTypePairs[i + 1]);
        }
        return columns;
    }
}
