package cluster93.core.host.egress;

import cluster93.core.identity.Records;
import io.vertx.core.Future;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cluster's egress policy and each tenant's (D-108, D-111), kept durably. Every connection reads both, so each node
 * caches them. A change is announced on the event bus and every node drops its copy; the cache also expires after
 * {@link #CACHE_MS}, so a missed announcement delays a change by that much at most. The API reads past the cache.
 */
public final class EgressPolicies {

    public static final Records.Kind KIND = new Records.Kind("EGRESS_POLICIES", List.of());
    public static final Records.Kind CLUSTER_KIND = new Records.Kind("EGRESS_CLUSTER", List.of());
    static final String CLUSTER_KEY = "cluster";
    /** Where changes are announced: the tenant's name, or the empty string for the cluster's policy. */
    static final String CHANGED = "cluster93.egress.changed";
    static final long CACHE_MS = 5_000;

    /** No rules beyond the defaults: for nodes and tests without durable records. */
    public static final EgressPolicies NONE = new EgressPolicies(null, null, null);

    private record Cached<T>(T value, long at) {
    }

    private final Records tenants;
    private final Records cluster;
    private final io.vertx.core.Vertx vertx;
    private final Map<String, Cached<TenantEgress>> tenantCache = new ConcurrentHashMap<>();
    private volatile Cached<ClusterEgress> clusterCache;
    /** Bumped by every change, so a read that started before one doesn't cache what it read. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    /** @param vertx where changes are announced and heard, or null for a node of one (tests) */
    public EgressPolicies(Records tenants, Records cluster, io.vertx.core.Vertx vertx) {
        this.tenants = tenants;
        this.cluster = cluster;
        this.vertx = vertx;
        if (vertx != null) {
            vertx.eventBus().<String>consumer(CHANGED, message -> forget(message.body()));
        }
    }

    private void forget(String tenant) {
        generation.incrementAndGet();
        if (tenant == null || tenant.isEmpty()) {
            clusterCache = null;
        } else {
            tenantCache.remove(tenant);
        }
    }

    private void announce(String tenant) {
        forget(tenant);
        if (vertx != null) {
            vertx.eventBus().publish(CHANGED, tenant);
        }
    }

    /** The tenant's policy, or {@link TenantEgress#none} if an operator never set one. */
    public Future<TenantEgress> of(String tenant) {
        if (tenants == null || tenant == null) {
            return Future.succeededFuture(TenantEgress.none(tenant));
        }
        Cached<TenantEgress> known = tenantCache.get(tenant);
        if (known != null && fresh(known.at())) {
            return Future.succeededFuture(known.value());
        }
        return load(tenant);
    }

    /** The tenant's policy as stored now, past the cache (for the API). */
    public Future<TenantEgress> load(String tenant) {
        if (tenants == null || tenant == null) {
            return Future.succeededFuture(TenantEgress.none(tenant));
        }
        long started = generation.get();
        return tenants.get(tenant).map(json -> {
            TenantEgress policy = json == null ? TenantEgress.none(tenant) : TenantEgress.fromJson(tenant, json);
            if (generation.get() == started) {
                tenantCache.put(tenant, new Cached<>(policy, System.nanoTime()));
            }
            return policy;
        });
    }

    /** The cluster's policy, or {@link ClusterEgress#DEFAULT} if an operator never set one. */
    public Future<ClusterEgress> cluster() {
        if (cluster == null) {
            return Future.succeededFuture(ClusterEgress.DEFAULT);
        }
        Cached<ClusterEgress> known = clusterCache;
        if (known != null && fresh(known.at())) {
            return Future.succeededFuture(known.value());
        }
        return loadCluster();
    }

    /** The cluster's policy as stored now, past the cache (for the API). */
    public Future<ClusterEgress> loadCluster() {
        if (cluster == null) {
            return Future.succeededFuture(ClusterEgress.DEFAULT);
        }
        long started = generation.get();
        return cluster.get(CLUSTER_KEY).map(json -> {
            ClusterEgress policy = json == null ? ClusterEgress.DEFAULT : ClusterEgress.fromJson(json);
            if (generation.get() == started) {
                clusterCache = new Cached<>(policy, System.nanoTime());
            }
            return policy;
        });
    }

    public Future<Void> put(TenantEgress policy) {
        if (tenants == null) {
            return Future.failedFuture(new IllegalStateException("This node keeps no egress policies"));
        }
        Future<Void> written = policy.isEmpty() ? tenants.remove(policy.tenant()).mapEmpty() : tenants.put(policy.tenant(), policy.toJson());
        return written.onComplete(done -> announce(policy.tenant()));
    }

    public Future<Void> putCluster(ClusterEgress policy) {
        if (cluster == null) {
            return Future.failedFuture(new IllegalStateException("This node keeps no egress policies"));
        }
        return cluster.put(CLUSTER_KEY, policy.toJson()).onComplete(done -> announce(""));
    }

    /** Stores {@code policy} only if the cluster has none yet (the seed file, at first start); answers whether it did. */
    public Future<Boolean> seedCluster(ClusterEgress policy) {
        if (cluster == null) {
            return Future.succeededFuture(false);
        }
        return cluster.putIfAbsent(CLUSTER_KEY, policy.toJson()).onComplete(done -> announce(""));
    }

    private static boolean fresh(long at) {
        return System.nanoTime() - at < CACHE_MS * 1_000_000;
    }
}
