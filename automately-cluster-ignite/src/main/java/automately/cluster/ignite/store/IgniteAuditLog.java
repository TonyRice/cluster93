package automately.cluster.ignite.store;

import automately.core.audit.AuditEntry;
import automately.core.audit.AuditLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.ignite.Ignite;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link AuditLog} in one durable table, keyed by the time-ordered entry id (D-071). */
public final class IgniteAuditLog implements AuditLog {

    static final Table.Definition TABLE = new Table.Definition("AUDIT",
            Table.columns("tenant", String.class, "at", Long.class, "operator", Boolean.class),
            List.of(Table.index("tenant"), Table.index("at")));
    /** Sorts after every id, for the first page. */
    private static final String END = "~";

    private final Table table;

    IgniteAuditLog(Vertx vertx, Ignite ignite) {
        this.table = new Table(vertx, ignite, TABLE, json -> {
            Map<String, Object> columns = new HashMap<>();
            columns.put("tenant", json.getString("tenant"));
            columns.put("at", json.getLong("at"));
            columns.put("operator", json.getJsonObject("actor").getBoolean("operator", false));
            return columns;
        });
    }

    @Override
    public Future<Void> record(AuditEntry entry) {
        return table.put(entry.id(), entry.toJson());
    }

    @Override
    public Future<List<AuditEntry>> tenant(String tenant, long since, String before, int limit) {
        return table.queryJson("SELECT json FROM AUDIT WHERE tenant = ? AND at >= ? AND _KEY < ? ORDER BY _KEY DESC LIMIT ?",
                tenant, since, before == null ? END : before, Math.min(limit, MAX_PAGE)).map(rows -> rows.stream().map(AuditEntry::fromJson).toList());
    }

    @Override
    public Future<List<AuditEntry>> operators(String before, int limit) {
        return table.queryJson("SELECT json FROM AUDIT WHERE (tenant IS NULL OR operator = TRUE) AND _KEY < ? ORDER BY _KEY DESC LIMIT ?",
                before == null ? END : before, Math.min(limit, MAX_PAGE)).map(rows -> rows.stream().map(AuditEntry::fromJson).toList());
    }

    @Override
    public Future<List<AuditEntry>> scan(String after, int limit) {
        return table.queryJson("SELECT json FROM AUDIT WHERE _KEY > ? ORDER BY _KEY LIMIT ?", after == null ? "" : after,
                Math.min(limit, MAX_PAGE)).map(rows -> rows.stream().map(AuditEntry::fromJson).toList());
    }

    @Override
    public Future<Void> prune(long at) {
        return table.query("DELETE FROM AUDIT WHERE at < ?", at).mapEmpty();
    }

}
