package cluster93.core.audit;

import io.vertx.core.Future;

import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Predicate;

/** {@link AuditLog} in this process's memory, for a single development node. */
public final class MemoryAuditLog implements AuditLog {

    private final NavigableMap<String, AuditEntry> entries = new ConcurrentSkipListMap<>();

    @Override
    public Future<Void> record(AuditEntry entry) {
        entries.put(entry.id(), entry);
        return Future.succeededFuture();
    }

    @Override
    public Future<List<AuditEntry>> tenant(String tenant, long since, String before, int limit) {
        return page(e -> Objects.equals(tenant, e.tenant()) && e.at() >= since, before, limit);
    }

    @Override
    public Future<List<AuditEntry>> operators(String before, int limit) {
        return page(e -> e.tenant() == null || e.operator(), before, limit);
    }

    private Future<List<AuditEntry>> page(Predicate<AuditEntry> filter, String before, int limit) {
        NavigableMap<String, AuditEntry> older = before == null ? entries : entries.headMap(before, false);
        return Future.succeededFuture(older.descendingMap().values().stream().filter(filter).limit(Math.min(limit, MAX_PAGE)).toList());
    }

    @Override
    public Future<List<AuditEntry>> scan(String after, int limit) {
        NavigableMap<String, AuditEntry> newer = after == null ? entries : entries.tailMap(after, false);
        return Future.succeededFuture(newer.values().stream().limit(Math.min(limit, MAX_PAGE)).toList());
    }

    @Override
    public Future<Void> prune(long at) {
        entries.values().removeIf(e -> e.at() < at);
        return Future.succeededFuture();
    }

}
