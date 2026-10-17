package cluster93.core.host.egress;

import io.vertx.core.Future;

import java.util.List;

/**
 * The node's placement (D-173): the policy comes from the durable records (D-111), the cluster's own addresses from
 * the live node registry, and the switch and zones from this node's configuration. The worker's placement is the same
 * {@link PolicySource} contract fed by synced rules instead.
 */
public final class NodePolicySource implements PolicySource {

    private final ClusterAddresses cluster;
    private final EgressPolicies policies;
    private final boolean allowPrivate;
    private final List<String> internalDomains;

    public NodePolicySource(ClusterAddresses cluster, EgressPolicies policies, boolean allowPrivate,
                           List<String> internalDomains) {
        this.cluster = cluster;
        this.policies = policies;
        this.allowPrivate = allowPrivate;
        this.internalDomains = List.copyOf(internalDomains == null ? List.of() : internalDomains);
    }

    @Override
    public boolean allowPrivate() {
        return allowPrivate;
    }

    @Override
    public List<String> internalDomains() {
        return internalDomains;
    }

    @Override
    public Future<ClusterEgress> cluster() {
        return policies.cluster();
    }

    @Override
    public Future<TenantEgress> tenant(String tenant) {
        return policies.of(tenant);
    }

    @Override
    public Future<NodeAddresses> nodes() {
        return cluster.current();
    }
}
