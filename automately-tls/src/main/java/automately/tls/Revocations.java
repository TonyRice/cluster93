package automately.tls;

import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Revoked nodes (D-066): node id → when it was revoked. A certificate for a revoked node issued before that moment is
 * refused; one issued afterwards (the node was invited again) is accepted. Thread-safe; the server keeps it current.
 */
public final class Revocations {

    private final Map<String, Long> revokedAt = new ConcurrentHashMap<>();

    public void revoke(String nodeId, long atMillis) {
        revokedAt.merge(nodeId, atMillis, Math::max);
    }

    public void replaceAll(Map<String, Long> all) {
        revokedAt.clear();
        revokedAt.putAll(all);
    }

    public Map<String, Long> all() {
        return Map.copyOf(revokedAt);
    }

    public boolean isRevoked(X509Certificate certificate) {
        Long at = revokedAt.get(String.valueOf(NodeIdentity.commonName(certificate)));
        return at != null && ClusterCa.issuedAt(certificate) <= at;
    }
}
