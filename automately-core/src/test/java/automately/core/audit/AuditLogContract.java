package automately.core.audit;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Behaviour every {@link AuditLog} must have; each implementation's test extends this. */
public abstract class AuditLogContract {

    /** Set by the implementation's test before each test. */
    protected AuditLog log;

    private static AuditEntry entry(long at, String tenant, boolean operator) {
        return new AuditEntry(String.format("%013d-%s", at, tenant == null ? "cluster" : tenant), at, tenant, operator ? null : "alice",
                "tok_1", operator, "putService", "PUT", "/v1/services/web", 200, "10.0.0.1", null);
    }

    @Test
    public void aTenantPagesThroughItsOwnEntriesNewestFirst() throws Exception {
        for (long at = 1; at <= 5; at++) {
            await(log.record(entry(at, "acme", false)));
        }
        await(log.record(entry(6, "globex", false)));

        List<AuditEntry> first = await(log.tenant("acme", null, 2));
        assertThat(first).extracting(AuditEntry::at).containsExactly(5L, 4L);
        List<AuditEntry> second = await(log.tenant("acme", first.getLast().id(), 10));
        assertThat(second).extracting(AuditEntry::at).containsExactly(3L, 2L, 1L);
        assertThat(second.getFirst()).isEqualTo(entry(3, "acme", false));
    }

    @Test
    public void operatorsSeeTheClusterAndWhatOperatorsDid() throws Exception {
        await(log.record(entry(1, "acme", false)));
        await(log.record(entry(2, "acme", true)));
        await(log.record(entry(3, null, true)));

        assertThat(await(log.operators(null, 10))).extracting(AuditEntry::at).containsExactly(3L, 2L);
    }

    @Test
    public void oldEntriesAndDeletedTenantsAreForgotten() throws Exception {
        await(log.record(entry(1, "acme", false)));
        await(log.record(entry(2, "acme", false)));
        await(log.record(entry(3, "globex", false)));

        await(log.prune(2));
        assertThat(await(log.tenant("acme", null, 10))).extracting(AuditEntry::at).containsExactly(2L);
        await(log.forget("acme"));
        assertThat(await(log.tenant("acme", null, 10))).isEmpty();
        assertThat(await(log.tenant("globex", null, 10))).hasSize(1);
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
