package cluster93.core.callback;

import cluster93.core.host.KvStore;
import cluster93.core.queue.QueueService;
import cluster93.core.storage.SecretBox;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Each tenant's callback signing secret (D-062), created on first use. Kept in the replicated kv store under the system
 * tenant, which no tenant can reach, sealed with {@code C93_SECRETS_KEY} when one is set (D-057). Creation is a
 * put-if-absent, so nodes racing to create it agree on one.
 */
public final class CallbackSecrets {

    static final String NAMESPACE = "callback-secrets";

    private final KvStore kv;
    private final SecretBox box;
    private final SecureRandom random = new SecureRandom();

    /** @param box null if the cluster has no secrets key: the secret is then stored as it is */
    public CallbackSecrets(KvStore kv, SecretBox box) {
        this.kv = kv;
        this.box = box;
    }

    public Future<String> secret(String tenant) {
        return kv.get(QueueService.SYSTEM_TENANT, NAMESPACE, tenant).compose(stored -> {
            String opened = open(tenant, stored);
            if (opened != null) {
                return Future.succeededFuture(opened);
            }
            String fresh = generate();
            return (stored == null
                    ? kv.putIfAbsent(QueueService.SYSTEM_TENANT, NAMESPACE, tenant, seal(tenant, fresh))
                    // Unreadable (the secrets key changed): replaced, as a rotation would.
                    : kv.set(QueueService.SYSTEM_TENANT, NAMESPACE, tenant, seal(tenant, fresh), null).map(v -> seal(tenant, fresh)))
                    .map(winner -> winner == null ? fresh : open(tenant, winner))
                    .map(secret -> secret == null ? fresh : secret);
        });
    }

    /** A new secret; signatures made with the old one no longer verify. */
    public Future<String> rotate(String tenant) {
        String fresh = generate();
        return kv.set(QueueService.SYSTEM_TENANT, NAMESPACE, tenant, seal(tenant, fresh), null).map(v -> fresh);
    }

    private String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    private JsonObject seal(String tenant, String secret) {
        return box == null ? new JsonObject().put("plain", secret) : new JsonObject().put("sealed", box.seal(secret, context(tenant)));
    }

    private String open(String tenant, Object stored) {
        if (!(stored instanceof JsonObject json)) {
            return null;
        }
        if (json.getString("plain") != null) {
            return json.getString("plain");
        }
        if (json.getString("sealed") != null && box != null) {
            try {
                return box.open(json.getString("sealed"), context(tenant));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static String context(String tenant) {
        return "callback-secret/" + tenant;
    }
}
