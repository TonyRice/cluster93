package cluster93.core.files;

import cluster93.core.identity.Records;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Advisory locks on paths (D-080), 2017's per-file locks: a cluster-wide lease that only cooperating code checks, for
 * multi-step work ("only one job rebuilds the index"). Writes don't look at them; the store's writes are atomic per file
 * already. A lease expires on its own, so a holder that dies can't keep it, and a job's locks go when it ends.
 */
public final class FileLocks {

    public static final Records.Kind KIND = new Records.Kind("FILE_LOCKS", List.of("tenant"));
    public static final Duration MAX_TTL = Duration.ofHours(1);
    public static final int MAX_PER_TENANT = 10_000;
    private static final char SEPARATOR = '\u001f';

    private final Records locks;
    private final SecureRandom random = new SecureRandom();

    public FileLocks(Records locks) {
        this.locks = locks;
    }

    private static String key(String tenant, String path) {
        return tenant + SEPARATOR + path;
    }

    /** Takes the lock for {@code ttl}: {@code {path, token, expiresAt}}. Fails {@code busy} while someone else holds it. */
    public Future<JsonObject> acquire(String tenant, String path, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
            return Future.failedFuture(HostException.invalid("A lock lasts between 1 ms and " + MAX_TTL.toMinutes() + " minutes; renew it to keep it"));
        }
        long now = System.currentTimeMillis();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        JsonObject lease = new JsonObject().put("tenant", tenant).put("path", path).put("token", token).put("until", now + ttl.toMillis());
        String key = key(tenant, path);
        return locks.where("tenant", tenant).compose(held -> held.size() >= MAX_PER_TENANT && held.stream().noneMatch(l -> path.equals(l.getString("path")))
                        ? Future.failedFuture(HostException.limit("A tenant can hold at most " + MAX_PER_TENANT + " locks"))
                        : locks.putIfAbsent(key, lease))
                .compose(taken -> taken ? Future.succeededFuture(Optional.of(lease))
                        // Held, but maybe expired: take it over only if it still is when we write.
                        : locks.update(key, current -> current.getLong("until", 0L) < now ? Optional.of(lease) : Optional.empty()))
                .compose(won -> won.isPresent()
                        ? Future.succeededFuture(view(won.get()))
                        : locks.get(key).compose(current -> Future.failedFuture(new HostException("Error", "busy", path + " is locked until "
                                + java.time.Instant.ofEpochMilli(current == null ? now : current.getLong("until", now))))));
    }

    /** Extends a lock its holder still has. */
    public Future<JsonObject> renew(String tenant, String path, String token, Duration ttl) {
        long now = System.currentTimeMillis();
        return locks.update(key(tenant, path), current -> token.equals(current.getString("token")) && current.getLong("until", 0L) >= now
                        ? Optional.of(current.put("until", now + Math.min(ttl.toMillis(), MAX_TTL.toMillis())))
                        : Optional.empty())
                .compose(renewed -> renewed.isPresent() ? Future.succeededFuture(view(renewed.get()))
                        : Future.failedFuture(new HostException("Error", "not_found", "You no longer hold the lock on " + path)));
    }

    /** Releases a lock its holder has; completes with whether it did. */
    public Future<Boolean> release(String tenant, String path, String token) {
        String key = key(tenant, path);
        return locks.get(key).compose(current -> current != null && token.equals(current.getString("token"))
                ? locks.remove(key).map(removed -> removed != null)
                : Future.succeededFuture(false));
    }

    private static JsonObject view(JsonObject lease) {
        return new JsonObject().put("path", lease.getString("path")).put("token", lease.getString("token")).put("expiresAt", lease.getLong("until"));
    }
}
