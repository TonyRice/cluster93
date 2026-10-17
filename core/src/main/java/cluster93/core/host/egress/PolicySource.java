package cluster93.core.host.egress;

import io.vertx.core.Future;

import java.util.List;

/**
 * Where a decision's inputs come from (D-173). The gate's decision is pure — addresses in, verdict out — and this is
 * the seam between it and whoever holds the policy: on a **node**, the durable records, the live node registry and the
 * node's configuration; in a **worker**, the rules the node synced to it. One implementation per placement, one
 * decision, so a rule cannot mean two things.
 */
public interface PolicySource {

    /** The development switch, {@code C93_EGRESS_ALLOW_PRIVATE} (D-108). */
    boolean allowPrivate();

    /** Extra internal zones ({@code C93_EGRESS_INTERNAL_DOMAINS}); the gate adds the built-in ones. */
    List<String> internalDomains();

    /** The cluster's policy, or the default one when an operator never set it. */
    Future<ClusterEgress> cluster();

    /** The tenant's policy, or the empty one when an operator never set it. */
    Future<TenantEgress> tenant(String tenant);

    /** The cluster's own node addresses, which scripts may never reach (D-108). */
    Future<NodeAddresses> nodes();
}
