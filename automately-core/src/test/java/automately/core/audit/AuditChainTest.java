package automately.core.audit;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tamper evidence (D-072): what the verifier finds when entries are changed, removed or forged. */
class AuditChainTest {

    private static final String KEY = "audit-chain-test-key-0123456789abcdef";
    private final SecureRandom random = new SecureRandom();

    private List<AuditEntry> record(AuditLog log, AuditChain chain, int count) throws Exception {
        AuditLog sealing = chain.sealing(log);
        for (int i = 0; i < count; i++) {
            await(sealing.record(AuditEntry.of(1_000 + i, random, "acme", "alice", "usr_1", "tok_1", false, "putService", "PUT",
                    "/v1/services/web" + i, 200, "10.0.0.1", null)));
        }
        return await(log.scan(null, 1000));
    }

    @Test
    void anUntouchedLogVerifies() throws Exception {
        MemoryAuditLog log = new MemoryAuditLog();
        record(log, new AuditChain("node-a/1", KEY), 5);
        record(log, new AuditChain("node-b/1", KEY), 3);

        JsonObject result = await(AuditVerifier.verify(log, KEY));
        assertThat(result.getBoolean("ok")).as(result.encodePrettily()).isTrue();
        assertThat(result.getBoolean("keyed")).isTrue();
        assertThat(result.getLong("checked")).isEqualTo(8);
        assertThat(result.getJsonArray("chains")).hasSize(2);
    }

    @Test
    void aChangedEntryIsFound() throws Exception {
        MemoryAuditLog log = new MemoryAuditLog();
        AuditEntry victim = record(log, new AuditChain("node-a/1", KEY), 5).get(2);
        // Someone with database access edits who did it, keeping the old hash.
        await(log.record(new AuditEntry(victim.id(), victim.at(), victim.tenant(), "mallory", victim.userId(), victim.token(), victim.operator(),
                victim.action(), victim.method(), victim.path(), victim.status(), victim.ip(), victim.forwardedFor(), victim.chain(), victim.seq(),
                victim.prev(), victim.hash())));

        assertThat(problems(log)).containsExactly("3: its contents don't match its hash: it was changed");
    }

    @Test
    void aRemovedEntryIsFoundButAPrunedStartIsNot() throws Exception {
        MemoryAuditLog log = new MemoryAuditLog();
        List<AuditEntry> entries = record(log, new AuditChain("node-a/1", KEY), 6);
        await(log.prune(entries.get(2).at()));
        assertThat(problems(log)).as("the first two were pruned").isEmpty();

        MemoryAuditLog holed = new MemoryAuditLog();
        List<AuditEntry> rest = entries.subList(2, 6);
        await(holed.record(rest.get(0)));
        await(holed.record(rest.get(1)));
        await(holed.record(rest.get(3)));
        assertThat(problems(holed)).containsExactly("6: entries 5 to 5 are missing");
    }

    @Test
    void aForgedEntryNeedsTheKey() throws Exception {
        MemoryAuditLog log = new MemoryAuditLog();
        List<AuditEntry> entries = record(log, new AuditChain("node-a/1", KEY), 3);
        // A forger without the key can only chain with a different one (or none): every hash then fails.
        MemoryAuditLog forged = new MemoryAuditLog();
        record(forged, new AuditChain("node-a/1", "a-different-key-0123456789abcdef-0123"), 3);

        assertThat(problems(forged)).hasSize(3).allMatch(p -> p.contains("don't match its hash"));
        JsonObject withoutKey = await(AuditVerifier.verify(log, null));
        assertThat(withoutKey.getBoolean("ok")).as("checked without the key it was sealed with").isFalse();
        assertThat(entries.get(1).prev()).isEqualTo(entries.get(0).hash());
    }

    private static List<String> problems(AuditLog log) throws Exception {
        return await(AuditVerifier.verify(log, KEY)).getJsonArray("problems").stream()
                .map(p -> ((JsonObject) p).getLong("seq") + ": " + ((JsonObject) p).getString("problem")).toList();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
