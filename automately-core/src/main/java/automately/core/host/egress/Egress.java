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
 * The one gate every connection a script causes goes through (D-108). It resolves the name, checks each address,
 * and answers the one to connect to, so the address checked is the address used (D-026).
 *
 * <p>For each address, in order: a cluster node is refused, on every port (with the development switch on, only on
 * the node's own ports); a public address is allowed; a private or reserved one is allowed only by the development
 * switch or a rule in the tenant's {@link TenantEgress policy}.
 */
public final class Egress {

    /** Why a connection was refused, as the API, the metric and the script's error name it. */
    public enum Reason {
        CLUSTER_NODE("cluster_node", "one of the cluster's own nodes, which scripts can never reach"),
        PRIVATE("private_address", "a private or reserved address, and the tenant's egress policy doesn't allow it");

        public final String code;
        final String explanation;

        Reason(String code, String explanation) {
            this.code = code;
            this.explanation = explanation;
        }
    }

    /** The address to connect to, or why there's none. */
    public record Verdict(InetAddress address, Reason refused) {
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

    /** A gate that knows this node only, by its loopback and wildcard addresses, and no tenant rules: for tests. */
    public static Egress standalone(Vertx vertx, EgressPolicy policy) {
        return new Egress(vertx, policy.allowPrivate(), new ClusterAddresses(vertx, null, Set.of()), EgressPolicies.NONE);
    }

    public boolean allowPrivate() {
        return allowPrivate;
    }

    /**
     * Resolves {@code host} and picks the address {@code tenant}'s script may connect to on {@code port}. Fails with a
     * {@link HostException} coded {@code dns_failed} or {@code egress_denied}; {@code what} names the caller in the
     * message ("fetch", "http.request" and so on).
     */
    public Future<InetAddress> address(String tenant, String host, int port, String what) {
        return cluster.current().compose(nodes -> vertx.<List<InetAddress>>executeBlocking(() -> {
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                throw new HostException("TypeError", "dns_failed", "Could not resolve " + host);
            }
        }, false).compose(addresses -> {
            Verdict first = decide(host, addresses, port, nodes, allowPrivate, TenantEgress.none(tenant));
            // The tenant's rules are read only when one could change the answer: some address is private and not a node's.
            if (first.address() != null || tenant == null || !anyPrivate(addresses, nodes)) {
                return settle(first, tenant, host, port, what);
            }
            return policies.of(tenant).compose(policy -> settle(decide(host, addresses, port, nodes, allowPrivate, policy), tenant, host, port, what));
        }));
    }

    private Future<InetAddress> settle(Verdict verdict, String tenant, String host, int port, String what) {
        if (verdict.address() != null) {
            return Future.succeededFuture(verdict.address());
        }
        automately.core.metrics.Metrics.of(vertx).egressDenied(tenant == null ? "-" : tenant, verdict.refused().code);
        return Future.failedFuture(new HostException("TypeError", "egress_denied", what + " to " + host + ":" + port
                + " is not allowed: it resolves to " + verdict.refused().explanation));
    }

    private static boolean anyPrivate(List<InetAddress> addresses, ClusterAddresses.Snapshot nodes) {
        return addresses.stream().anyMatch(a -> !nodes.isNode(a) && !nodes.isNode(checked(a)) && !EgressPolicy.isPublic(a));
    }

    /** The address judged: the IPv4 address an IPv6 form carries, or the address itself. */
    static InetAddress checked(InetAddress address) {
        InetAddress embedded = EgressPolicy.embeddedIpv4(address);
        return embedded == null ? address : embedded;
    }

    /** The first address of {@code addresses} that may be reached, in their order; pure, for testing. */
    public static Verdict decide(String host, List<InetAddress> addresses, int port, ClusterAddresses.Snapshot nodes,
                                 boolean allowPrivate, TenantEgress policy) {
        Reason refused = null;
        for (InetAddress address : addresses) {
            InetAddress checked = checked(address);
            if (nodes.isNode(address) || nodes.isNode(checked)) {
                if (!allowPrivate || nodes.ports().contains(port)) {
                    refused = Reason.CLUSTER_NODE;
                    continue;
                }
            }
            if (EgressPolicy.isPublic(checked) || allowPrivate || policy.allows(host, checked, port)) {
                return new Verdict(address, null);
            }
            if (refused == null) {
                refused = Reason.PRIVATE;
            }
        }
        return new Verdict(null, refused == null ? Reason.PRIVATE : refused);
    }
}
