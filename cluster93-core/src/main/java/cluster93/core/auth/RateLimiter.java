package cluster93.core.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per tenant (phase 4, milestone 2). Each node enforces its share of the tenant's budget, the budget
 * divided by the live nodes, so no request needs a cluster round-trip. A bucket holds a minute's worth and refills
 * continuously. The cost: a client hitting one node gets that node's share, not the whole budget.
 */
public final class RateLimiter {

    /** The outcome, with what the {@code RateLimit} headers report. */
    public record Decision(boolean allowed, long limit, long remaining, long resetSeconds, long retryAfterSeconds) {
    }

    private static final class Bucket {
        double tokens;
        long refilledAt;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.refilledAt = now;
        }
    }

    private final long perMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile int nodes = 1;

    /** {@code perMinute} 0 turns limiting off. */
    public RateLimiter(long perMinute) {
        this.perMinute = perMinute;
    }

    public boolean enabled() {
        return perMinute > 0;
    }

    public long perMinute() {
        return perMinute;
    }

    /** The live node count, which sets this node's share. */
    public void nodes(int live) {
        this.nodes = Math.max(1, live);
    }

    public Decision acquire(String tenant, long nowNanos) {
        int share = nodes;
        double capacity = Math.max(1.0, (double) perMinute / share);
        double perNano = capacity / 60e9;
        Bucket bucket = buckets.computeIfAbsent(tenant, t -> new Bucket(capacity, nowNanos));
        synchronized (bucket) {
            bucket.tokens = Math.min(capacity, bucket.tokens + (nowNanos - bucket.refilledAt) * perNano);
            bucket.refilledAt = nowNanos;
            boolean allowed = bucket.tokens >= 1;
            if (allowed) {
                bucket.tokens -= 1;
            }
            long remaining = (long) Math.floor(bucket.tokens * share);
            long reset = (long) Math.ceil((capacity - bucket.tokens) / perNano / 1e9);
            long retryAfter = allowed ? 0 : Math.max(1, (long) Math.ceil((1 - bucket.tokens) / perNano / 1e9));
            return new Decision(allowed, perMinute, Math.min(perMinute, remaining), reset, retryAfter);
        }
    }
}
