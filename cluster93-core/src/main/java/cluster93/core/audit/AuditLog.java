package cluster93.core.audit;

import io.vertx.core.Future;

import java.util.List;

/** Where audit entries are kept (D-071). Newest first; {@code before} is an entry id to page from, or null. */
public interface AuditLog {

    int MAX_PAGE = 1000;

    Future<Void> record(AuditEntry entry);

    /**
     * A tenant's entries from {@code since} on. A deleted tenant's entries stay until they're pruned (taking them out
     * would break the chains, D-072), so a new tenant with the same name asks only for those after it was created.
     */
    Future<List<AuditEntry>> tenant(String tenant, long since, String before, int limit);

    /** What operators see: entries without a tenant, and everything done as an operator or with the admin token. */
    Future<List<AuditEntry>> operators(String before, int limit);

    /** Every entry, oldest first, after the entry id {@code after} (or from the start): for verifying and exporting. */
    Future<List<AuditEntry>> scan(String after, int limit);

    /** Deletes entries from before {@code at}: only ever a chain's oldest, which verifying expects. */
    Future<Void> prune(long at);
}
