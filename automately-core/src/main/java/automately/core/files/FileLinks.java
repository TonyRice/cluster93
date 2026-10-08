package automately.core.files;

import automately.core.host.KvStore;
import automately.core.queue.QueueService;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Public links to files (D-080), as 2017's public file tokens: a URL anyone can fetch until it expires. The token
 * itself carries the tenant, path, file version and expiry, signed with an HMAC under a key derived from the cluster's
 * secrets key and a per-tenant link secret, so nothing is stored per link and any node can check one.
 * <ul>
 *   <li>A link is pinned to the version it was made for: replacing the file stops it.</li>
 *   <li>Rotating the tenant's link secret revokes every link it has made.</li>
 *   <li>Without a secrets key there are no links: someone with database access alone mustn't be able to forge them.</li>
 * </ul>
 */
public final class FileLinks {

    public static final Duration DEFAULT_TTL = Duration.ofHours(1);
    public static final Duration MAX_TTL = Duration.ofDays(30);
    static final String NAMESPACE = "file-links";

    private final KvStore kv;
    private final byte[] clusterKey;
    private final FileStore files;
    private final SecureRandom random = new SecureRandom();

    /** @param secretsKey {@code AUTOMATELY_SECRETS_KEY}, or null (then links are unavailable) */
    public FileLinks(KvStore kv, String secretsKey, FileStore files) {
        this.kv = kv;
        this.clusterKey = secretsKey == null ? null : sha256("automately file links\n" + secretsKey);
        this.files = files;
    }

    /** A link to the current version of a file: {@code {token, path, expiresAt}}. */
    public Future<JsonObject> create(String tenant, String path, Duration ttl) {
        if (clusterKey == null) {
            return Future.failedFuture(unavailable());
        }
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
            return Future.failedFuture(HostException.invalid("A link lasts between 1 second and " + MAX_TTL.toDays() + " days"));
        }
        return files.stat(tenant, path).compose(file -> {
            if (file == null) {
                return Future.failedFuture(new HostException("Error", "not_found", "No such file: " + path));
            }
            long expiresAt = System.currentTimeMillis() + ttl.toMillis();
            String payload = encode(new JsonObject().put("t", tenant).put("p", path).put("f", file.fileId()).put("e", expiresAt).encode());
            return secret(tenant).map(secret -> new JsonObject().put("token", payload + "." + sign(secret, payload)).put("path", path)
                    .put("expiresAt", expiresAt));
        });
    }

    /** The file a token points to, if it's genuine, unexpired and still that version; null otherwise. */
    public Future<FileInfo> resolve(String token) {
        if (clusterKey == null || token == null) {
            return Future.succeededFuture();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Future.succeededFuture();
        }
        String payload = token.substring(0, dot);
        JsonObject claims;
        try {
            claims = new JsonObject(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Future.succeededFuture();
        }
        String tenant = claims.getString("t");
        if (tenant == null || claims.getLong("e", 0L) < System.currentTimeMillis()) {
            return Future.succeededFuture();
        }
        return secret(tenant).compose(secret -> {
            if (!MessageDigest.isEqual(sign(secret, payload).getBytes(StandardCharsets.US_ASCII), token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) {
                return Future.succeededFuture();
            }
            return files.stat(tenant, claims.getString("p")).map(file -> file != null && file.fileId().equals(claims.getString("f")) ? file : null);
        });
    }

    /** Revokes every link the tenant has made. */
    public Future<Void> rotate(String tenant) {
        if (clusterKey == null) {
            return Future.failedFuture(unavailable());
        }
        return kv.set(QueueService.SYSTEM_TENANT, NAMESPACE, tenant, newSecret(), null);
    }

    /** The tenant's link secret, made on first use; racing nodes agree on one. */
    private Future<String> secret(String tenant) {
        return kv.get(QueueService.SYSTEM_TENANT, NAMESPACE, tenant).compose(found -> found instanceof String secret
                ? Future.succeededFuture(secret)
                : kv.putIfAbsent(QueueService.SYSTEM_TENANT, NAMESPACE, tenant, newSecret()).map(String::valueOf));
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String sign(String tenantSecret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = sha256(HexFormat.of().formatHex(clusterKey) + "\n" + tenantSecret);
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HostException unavailable() {
        return new HostException("Error", "unavailable", "Public links need AUTOMATELY_SECRETS_KEY to be set on every node");
    }
}
