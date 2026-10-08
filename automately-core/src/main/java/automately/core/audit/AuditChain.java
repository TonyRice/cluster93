package automately.core.audit;

import io.vertx.core.Future;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Makes a node's audit entries tamper-evident (D-072): each gets the next number in this node's chain, the previous
 * entry's hash, and its own hash over all of that. With the cluster's secrets key the hash is an HMAC, so someone who
 * can write to the database but doesn't have the key can't forge a consistent chain; without one it's a plain SHA-256,
 * which still shows careless edits. One chain per node process: a restarted node starts a new one.
 */
public final class AuditChain {

    private final String chain;
    private final byte[] key;
    private long seq;
    private String prev;

    /** @param secretsKey {@code AUTOMATELY_SECRETS_KEY}, or null */
    public AuditChain(String chain, String secretsKey) {
        this.chain = chain;
        this.key = secretsKey == null ? null : derive(secretsKey);
    }

    public boolean keyed() {
        return key != null;
    }

    /** The entry with its place in the chain. Sealing and recording happen in this order, one at a time. */
    public synchronized AuditEntry seal(AuditEntry entry) {
        seq++;
        AuditEntry placed = entry.chained(chain, seq, prev, null);
        String digest = digest(key, placed);
        prev = digest;
        return placed.chained(chain, seq, placed.prev(), digest);
    }

    /** An {@link AuditLog} that seals every entry it records with this chain. */
    public AuditLog sealing(AuditLog log) {
        AuditChain self = this;
        return new AuditLog() {
            @Override
            public Future<Void> record(AuditEntry entry) {
                return log.record(self.seal(entry));
            }

            @Override
            public Future<List<AuditEntry>> tenant(String tenant, long since, String before, int limit) {
                return log.tenant(tenant, since, before, limit);
            }

            @Override
            public Future<List<AuditEntry>> operators(String before, int limit) {
                return log.operators(before, limit);
            }

            @Override
            public Future<List<AuditEntry>> scan(String after, int limit) {
                return log.scan(after, limit);
            }

            @Override
            public Future<Void> prune(long at) {
                return log.prune(at);
            }

        };
    }

    static byte[] derive(String secretsKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(("automately audit chain\n" + secretsKey).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The hash an entry should carry: HMAC-SHA256 with the key, or SHA-256 without one, over the entry minus its hash. */
    static String digest(byte[] key, AuditEntry entry) {
        byte[] text = entry.unhashed().encode().getBytes(StandardCharsets.UTF_8);
        try {
            if (key == null) {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text));
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
