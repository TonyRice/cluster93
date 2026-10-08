package automately.core.audit;

import io.vertx.core.Future;

import java.util.List;

/** Where audit entries are kept (D-071). Newest first; {@code before} is an entry id to page from, or null. */
public interface AuditLog {

    int MAX_PAGE = 1000;

    Future<Void> record(AuditEntry entry);

    /** A tenant's entries. */
    Future<List<AuditEntry>> tenant(String tenant, String before, int limit);

    /** What operators see: entries without a tenant, and everything done as an operator or with the admin token. */
    Future<List<AuditEntry>> operators(String before, int limit);

    /** Deletes entries from before {@code at}. */
    Future<Void> prune(long at);

    /** Deletes a deleted tenant's entries. */
    Future<Void> forget(String tenant);
}
