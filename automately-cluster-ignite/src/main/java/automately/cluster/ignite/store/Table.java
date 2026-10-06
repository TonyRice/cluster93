package automately.cluster.ignite.store;

import automately.cluster.ignite.IgniteCluster;
import io.vertx.core.Context;
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

    static final String SCHEMA = "AUTOMATELY";
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

    Table(Vertx vertx, Ignite ignite, Definition definition, Function<JsonObject, Map<String, Object>> columnsOf) {
        this.vertx = vertx;
        this.ignite = ignite;
        this.name = definition.name();
        this.cache = ignite.cache(definition.name()).withKeepBinary();
        this.columnsOf = columnsOf;
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
                .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                .setDataRegionName(IgniteCluster.DATA_REGION)
                .setSqlSchema(SCHEMA)
                .setQueryEntities(List.of(entity))
                .setEagerTtl(true);
        // Right after activation (first start or restart) Ignite can still be finishing the state change.
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                ignite.getOrCreateCache(config);
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
        BinaryObjectBuilder builder = ignite.binary().builder(name).setField("json", json.encode());
        columnsOf.apply(json).forEach((column, value) -> builder.setField(column, value));
        return builder.build();
    }

    private static JsonObject json(BinaryObject row) {
        return row == null ? null : new JsonObject((String) row.field("json"));
    }

    /** Starts an Ignite operation, retrying it if it fails only because the topology changed. */
    private <T> Future<T> async(java.util.function.Supplier<IgniteFuture<T>> operation) {
        return retrying(() -> {
            Context context = vertx.getOrCreateContext();
            Promise<T> promise = Promise.promise();
            try {
                operation.get().listen(done -> context.runOnContext(v -> {
                    try {
                        promise.complete(done.get());
                    } catch (RuntimeException e) {
                        promise.fail(e);
                    }
                }));
            } catch (RuntimeException e) {
                promise.fail(e);
            }
            return promise.future();
        }, 1);
    }

    static final int MAX_TOPOLOGY_RETRIES = 10;

    /**
     * While a node joins or leaves, Ignite can fail an operation with a topology error that succeeds when
     * retried a moment later. Only those are retried (operations here are reads, idempotent puts, or
     * compare-and-set, which re-reads); anything else fails at once.
     */
    private <T> Future<T> retrying(java.util.function.Supplier<Future<T>> operation, int attempt) {
        return operation.get().recover(err -> {
            if (attempt >= MAX_TOPOLOGY_RETRIES || !isTopologyChange(err)) {
                return Future.failedFuture(err);
            }
            Promise<T> retried = Promise.promise();
            vertx.setTimer(100L * attempt, id -> retrying(operation, attempt + 1).onComplete(retried));
            return retried.future();
        });
    }

    static boolean isTopologyChange(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.apache.ignite.cluster.ClusterTopologyException
                    || t instanceof org.apache.ignite.internal.cluster.ClusterTopologyCheckedException
                    || String.valueOf(t.getMessage()).contains("topology")) {
                return true;
            }
        }
        return false;
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
