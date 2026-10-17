package cluster93.server;

import cluster93.core.host.egress.ClusterAddresses;
import cluster93.core.host.egress.EgressPolicies;
import cluster93.runner.protocol.message.PolicyMessage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Builds the egress policy a worker's placement decides under, and keeps sending it (D-173).
 *
 * <p>Three triggers, because three things can make a worker's copy wrong: a rule change (announced on the event bus by
 * {@link EgressPolicies}), a change in the cluster's own addresses (a node joining or leaving), and time — a worker that
 * cannot be reached must stop trusting what it holds, so every policy carries an expiry and the node refreshes on a
 * shorter interval. The manager hands the payload out when a worker is bound; this class says when to send it again.
 *
 * <p>It is a node-side component: the policy comes from the durable records, the cluster's addresses from the live
 * registry, and the switch and zones from this node's configuration.
 */
final class WorkerPolicySync {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerPolicySync.class);

    /** How long a policy may be used without a refresh, and how often it is refreshed (a third of it, so two may be lost). */
    static final long TTL_MS = 90_000;
    static final long REFRESH_MS = TTL_MS / 3;

    private final EgressPolicies policies;
    private final ClusterAddresses cluster;
    private final boolean allowPrivate;
    private final List<String> internalDomains;
    private final String placement;
    private final long ttlMs;
    private final long refreshMs;
    /** What to do when a policy is stale: {@code refreshWorkerPolicies(tenant)}, or null for every tenant. */
    private final Consumer<String> refresh;

    /** Every policy gets a higher version than the last, so a worker can ignore one that arrives out of order. */
    private final AtomicLong version = new AtomicLong();
    private Vertx vertx;
    private long timerId = -1L;

    WorkerPolicySync(EgressPolicies policies, ClusterAddresses cluster, boolean allowPrivate, List<String> internalDomains,
                     String placement, Consumer<String> refresh) {
        this(policies, cluster, allowPrivate, internalDomains, placement, TTL_MS, REFRESH_MS, refresh);
    }

    /** @param refreshMs for tests: the interval the refresh runs on */
    WorkerPolicySync(EgressPolicies policies, ClusterAddresses cluster, boolean allowPrivate, List<String> internalDomains,
                     String placement, long ttlMs, long refreshMs, Consumer<String> refresh) {
        this.policies = policies;
        this.cluster = cluster;
        this.allowPrivate = allowPrivate;
        this.internalDomains = List.copyOf(internalDomains == null ? List.of() : internalDomains);
        this.placement = placement;
        this.ttlMs = ttlMs;
        this.refreshMs = refreshMs;
        this.refresh = refresh;
    }

    /** The payload for one tenant: what the manager hands a worker when it is bound. */
    Future<PolicyMessage> forTenant(String tenant) {
        return cluster.current().compose(nodes -> policies.cluster().compose(clusterPolicy -> policies.of(tenant)
                .map(tenantPolicy -> new PolicyMessage(tenant == null ? "" : tenant, placement, version.incrementAndGet(),
                        ttlMs, allowPrivate, internalDomains, clusterPolicy.toJson(), tenantPolicy.toJson(),
                        nodes.addresses().stream().map(java.net.InetAddress::getHostAddress).sorted().toList(),
                        nodes.ports().stream().sorted().toList()))));
    }

    /** Starts refreshing: the change announcements, and the interval that covers membership, time and a lost policy. */
    void start(Vertx vertx) {
        this.vertx = vertx;
        // Through the policy store, not a second subscription on the event bus: this is a node-local reaction (refresh
        // *this* node's workers), and a consumer on a clustered bus is a cluster-wide registration (D-173).
        policies.onChange(tenant -> {
            boolean clusterWide = tenant == null || tenant.isEmpty();
            LOG.info("Egress policy changed for {}: re-sending it to the workers bound to it",
                    clusterWide ? "the cluster" : tenant);
            refresh.accept(clusterWide ? null : tenant);
        });
        timerId = vertx.setPeriodic(refreshMs, id -> refresh.accept(null));
        LOG.info("Worker egress policies: a worker is handed its rules when it is bound, refreshed every {} ms, and "
                + "valid for {} ms without one", refreshMs, ttlMs);
    }

    void close() {
        if (timerId >= 0L && vertx != null) {
            vertx.cancelTimer(timerId);
            timerId = -1L;
        }
    }
}
