package automately.core.host.egress;

import automately.core.host.EgressPolicy;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * The one gate every connection a script causes goes through (D-108, D-111). It resolves the name, checks each address,
 * and answers the one to connect to, so the address checked is the address used (D-026).
 *
 * <p>For each address, in order: a cluster node is refused, on every port (with the development switch on, only on the
 * node's own ports); then a cluster deny rule refuses; a tenant deny rule refuses; a tenant allow rule allows; a cluster
 * allow rule allows; the development switch allows anything else that isn't public; and the posture decides the rest
 * (the tenant's, else the cluster's): {@code public} allows public addresses, {@code allow-list} refuses.
 */
public final class Egress {

    /** Why a connection was refused, as the API, the metric and the script's error name it. */
    public enum Reason {
        CLUSTER_NODE("cluster_node", "it resolves to one of the cluster's own nodes, which scripts can never reach"),
        CLUSTER_RULE("cluster_rule", "a cluster rule denies it"),
        TENANT_RULE("tenant_rule", "the tenant's egress policy denies it"),
        PRIVATE("private_address", "it resolves to a private or reserved address, and the egress policy doesn't allow it"),
        NOT_ALLOWED("not_allowed", "no rule allows it, and this tenant's egress policy allows only what its rules list");

        public final String code;
        final String explanation;

        Reason(String code, String explanation) {
            this.code = code;
            this.explanation = explanation;
        }
    }

    /**
     * The address to connect to, or why there's none.
     *
     * @param rule the rule that refused it, if one did
     */
    public record Verdict(InetAddress address, Reason refused, EgressRule rule) {
    }

    private final Vertx vertx;
    private final boolean allowPrivate;
    private final ClusterAddresses cluster;
    private final EgressPolicies policies;

    /**
     * @param allowPrivate the development switch, {@code AUTOMATELY_EGRESS_ALLOW_PRIVATE}
     */
    public Egress(Vertx vertx, boolean allowPrivate, ClusterAddresses cluster, EgressPolicies policies) {
        this.vertx = vertx;
        this.allowPrivate = allowPrivate;
        this.cluster = cluster;
        this.policies = policies;
    }

    /** A gate that knows this node only, by its loopback and wildcard addresses, and the default policies: for tests. */
    public static Egress standalone(Vertx vertx, EgressPolicy policy) {
        return new Egress(vertx, policy.allowPrivate(), new ClusterAddresses(vertx, null, Set.of()), EgressPolicies.NONE);
    }

    public boolean allowPrivate() {
        return allowPrivate;
    }

    /** The cluster's egress policy, for its limits (D-116). */
    public Future<ClusterEgress> cluster() {
        return policies.cluster();
    }

    /** As {@link #address(String, String, int, EgressProtocol, String)} for an HTTP request. */
    public Future<InetAddress> address(String tenant, String host, int port, String what) {
        return address(tenant, host, port, EgressProtocol.HTTP, what);
    }

    /**
     * Resolves {@code host} and picks the address {@code tenant}'s script may connect to on {@code port}. Fails with a
     * {@link HostException} coded {@code dns_failed} or {@code egress_denied}; {@code what} names the caller in the
     * message ("fetch", "http.request" and so on).
     */
    public Future<InetAddress> address(String tenant, String host, int port, EgressProtocol protocol, String what) {
        return cluster.current().compose(nodes -> policies.cluster().compose(clusterPolicy -> policies.of(tenant)
                .compose(tenantPolicy -> vertx.<List<InetAddress>>executeBlocking(() -> {
                    try {
                        return List.of(InetAddress.getAllByName(host));
                    } catch (UnknownHostException e) {
                        throw new HostException("TypeError", "dns_failed", "Could not resolve " + host);
                    }
                }, false).compose(addresses -> settle(decide(host, addresses, port, protocol, nodes, allowPrivate, clusterPolicy,
                        tenantPolicy), tenant, host, port, what)))));
    }

    private Future<InetAddress> settle(Verdict verdict, String tenant, String host, int port, String what) {
        if (verdict.address() != null) {
            return Future.succeededFuture(verdict.address());
        }
        automately.core.metrics.Metrics.of(vertx).egressDenied(tenant == null ? "-" : tenant, verdict.refused().code);
        String rule = verdict.rule() == null ? "" : " (rule \"" + verdict.rule().to() + "\""
                + (verdict.rule().note() == null ? "" : ": " + verdict.rule().note()) + ")";
        return Future.failedFuture(new HostException("TypeError", "egress_denied", what + " to " + host + ":" + port
                + " is not allowed: " + verdict.refused().explanation + rule));
    }

    /** The address judged: the IPv4 address an IPv6 form carries, or the address itself. */
    static InetAddress checked(InetAddress address) {
        InetAddress embedded = EgressPolicy.embeddedIpv4(address);
        return embedded == null ? address : embedded;
    }

    /** As {@link #decide(String, List, int, EgressProtocol, ClusterAddresses.Snapshot, boolean, ClusterEgress, TenantEgress)}
     * for an HTTP request under the default cluster policy. */
    public static Verdict decide(String host, List<InetAddress> addresses, int port, ClusterAddresses.Snapshot nodes,
                                 boolean allowPrivate, TenantEgress policy) {
        return decide(host, addresses, port, EgressProtocol.HTTP, nodes, allowPrivate, ClusterEgress.DEFAULT, policy);
    }

    /** The first address of {@code addresses} that may be reached, in their order; pure, for testing. */
    public static Verdict decide(String host, List<InetAddress> addresses, int port, EgressProtocol protocol,
                                 ClusterAddresses.Snapshot nodes, boolean allowPrivate, ClusterEgress cluster, TenantEgress tenant) {
        AddressSets sets = cluster.sets();
        Posture posture = tenant.posture() != null ? tenant.posture() : cluster.posture();
        Verdict refused = null;
        for (InetAddress address : addresses) {
            Verdict verdict = judge(host, address, checked(address), port, protocol, nodes, allowPrivate, cluster, tenant, sets, posture);
            if (verdict.address() != null) {
                return verdict;
            }
            if (refused == null || verdict.refused() == Reason.CLUSTER_NODE) {
                refused = verdict;
            }
        }
        return refused != null ? refused : new Verdict(null, posture == Posture.PUBLIC ? Reason.PRIVATE : Reason.NOT_ALLOWED, null);
    }

    private static Verdict judge(String host, InetAddress address, InetAddress checked, int port, EgressProtocol protocol,
                                 ClusterAddresses.Snapshot nodes, boolean allowPrivate, ClusterEgress cluster, TenantEgress tenant,
                                 AddressSets sets, Posture posture) {
        if ((nodes.isNode(address) || nodes.isNode(checked)) && (!allowPrivate || nodes.ports().contains(port))) {
            return new Verdict(null, Reason.CLUSTER_NODE, null);
        }
        EgressRule rule = first(cluster.rules(), EgressRule.Action.DENY, host, address, checked, port, protocol, sets);
        if (rule != null) {
            return new Verdict(null, Reason.CLUSTER_RULE, rule);
        }
        rule = first(tenant.rules(), EgressRule.Action.DENY, host, address, checked, port, protocol, sets);
        if (rule != null) {
            return new Verdict(null, Reason.TENANT_RULE, rule);
        }
        if (first(tenant.rules(), EgressRule.Action.ALLOW, host, address, checked, port, protocol, sets) != null
                || first(cluster.rules(), EgressRule.Action.ALLOW, host, address, checked, port, protocol, sets) != null) {
            return new Verdict(address, null, null);
        }
        boolean isPublic = AddressSets.isPublic(checked);
        if (allowPrivate && !isPublic) {
            return new Verdict(address, null, null);
        }
        if (posture == Posture.ALLOW_LIST) {
            return new Verdict(null, Reason.NOT_ALLOWED, null);
        }
        return isPublic ? new Verdict(address, null, null) : new Verdict(null, Reason.PRIVATE, null);
    }

    private static EgressRule first(List<EgressRule> rules, EgressRule.Action action, String host, InetAddress address,
                                    InetAddress checked, int port, EgressProtocol protocol, AddressSets sets) {
        for (EgressRule rule : rules) {
            if (rule.action() == action && rule.matches(host, address, checked, port, protocol, sets)) {
                return rule;
            }
        }
        return null;
    }
}
