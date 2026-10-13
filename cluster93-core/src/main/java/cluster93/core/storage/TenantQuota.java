package cluster93.core.storage;

import cluster93.core.runtime.HostException;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tenant's storage quota ({@code C93_TENANT_STORAGE_MB}), counting files, {@code kv} and queued messages together
 * (D-043, D-061).
 *
 * <p>An upload asks for {@link #remaining} once, fresh, and is cut off when it goes past it. {@code kv} writes are
 * small and frequent, so {@link #ensureFits} works from a usage figure at most {@link #FRESH} old plus whatever
 * this node has admitted since, instead of summing the tenant's storage on every write. The cost: writes on other
 * nodes in that window aren't seen, so a tenant can overshoot by what it writes cluster-wide in {@link #FRESH}.
 */
public final class TenantQuota {

    public static final Duration FRESH = Duration.ofSeconds(2);

    /** A tenant's stored bytes across every store that counts. */
    @FunctionalInterface
    public interface Usage {
        Future<Long> of(String tenant);
    }

    private record Snapshot(long used, long takenAt) {
    }

    private final long bytes;
    private final Usage usage;
    private final long freshNanos;
    private final Map<String, Snapshot> recent = new ConcurrentHashMap<>();

    public TenantQuota(long bytes, Usage usage) {
        this(bytes, usage, FRESH);
    }

    TenantQuota(long bytes, Usage usage, Duration fresh) {
        this.bytes = bytes;
        this.usage = usage;
        this.freshNanos = fresh.toNanos();
    }

    public static TenantQuota unlimited() {
        return new TenantQuota(Long.MAX_VALUE, tenant -> Future.succeededFuture(0L));
    }

    public long bytes() {
        return bytes;
    }

    /** Bytes the tenant can still store, from a fresh count. Zero or less means it's full. */
    public Future<Long> remaining(String tenant) {
        return usage.of(tenant).map(used -> {
            recent.put(tenant, new Snapshot(used, System.nanoTime()));
            return bytes - used;
        });
    }

    /** Fails with {@link #exceeded} if {@code more} bytes wouldn't fit; otherwise counts them as admitted. */
    public Future<Void> ensureFits(String tenant, long more) {
        Snapshot snapshot = recent.get(tenant);
        Future<Long> used = snapshot != null && System.nanoTime() - snapshot.takenAt() < freshNanos
                ? Future.succeededFuture(snapshot.used())
                : remaining(tenant).map(left -> bytes - left);
        return used.compose(current -> {
            if (current + more > bytes) {
                return Future.failedFuture(exceeded(bytes));
            }
            recent.computeIfPresent(tenant, (t, s) -> new Snapshot(s.used() + more, s.takenAt()));
            return Future.succeededFuture();
        });
    }

    /**
     * Counts bytes stored without {@link #ensureFits} (a finished upload) in the recent figure, so the quick check
     * doesn't hand the same room to kv. Counting too much is safe: a refusal is rechecked from a fresh count.
     */
    public void added(String tenant, long bytes) {
        recent.computeIfPresent(tenant, (t, s) -> new Snapshot(s.used() + bytes, s.takenAt()));
    }

    public static HostException exceeded(long bytes) {
        return HostException.limit("Storage quota of " + bytes + " bytes reached (files, kv and queued messages together)");
    }

    public static boolean isExceeded(Throwable error) {
        return error instanceof HostException host && "limit_exceeded".equals(host.code()) && String.valueOf(host.getMessage()).contains("quota");
    }
}
