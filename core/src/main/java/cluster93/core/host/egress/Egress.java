package cluster93.core.host.egress;

import cluster93.core.host.EgressPolicy;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

/**
 * The node's placement of the gate (D-108, D-111, D-173): it resolves the name, asks {@link Decision} which address the
 * tenant may reach, and counts a refusal. The decision itself is pure and lives in {@link Decision}; a worker's
 * placement resolves and connects itself and calls the same decision.
 */
public final class Egress implements Gate {

    private final PolicySource source;
    private final AccountingSink accounting;
    private final cluster93.core.host.dns.DnsResolver resolver;
    private final List<String> internalSuffixes;

    /**
     * The seam (D-173): the decision's inputs come from {@code source}, its outcomes go to {@code accounting}, and the
     * gate itself is the same code in either placement.
     */
    public Egress(PolicySource source, AccountingSink accounting, cluster93.core.host.dns.DnsResolver resolver) {
        this.source = source;
        this.accounting = accounting;
        this.resolver = resolver;
        List<String> suffixes = new java.util.ArrayList<>(Decision.INTERNAL_SUFFIXES);
        for (String domain : source.internalDomains()) {
            String lower = domain.trim().toLowerCase(java.util.Locale.ROOT);
            lower = lower.startsWith("*.") ? lower.substring(2) : lower.startsWith(".") ? lower.substring(1) : lower;
            if (!lower.isEmpty()) {
                suffixes.add(lower);
            }
        }
        this.internalSuffixes = List.copyOf(suffixes);
    }

    /**
     * The node's placement, with the node's own resolver and no extra internal zones.
     *
     * @param allowPrivate the development switch, {@code C93_EGRESS_ALLOW_PRIVATE}
     */
    public Egress(Vertx vertx, boolean allowPrivate, ClusterAddresses cluster, EgressPolicies policies) {
        this(vertx, allowPrivate, cluster, policies, new cluster93.core.host.dns.DnsResolver(vertx, List.of()), List.of());
    }

    /**
     * The node's placement: the durable records, the live registry and this node's configuration (D-173).
     *
     * @param resolver the one resolver for scripts (D-122)
     * @param internalDomains {@code C93_EGRESS_INTERNAL_DOMAINS}: more internal zones, as {@code corp.example}
     */
    public Egress(Vertx vertx, boolean allowPrivate, ClusterAddresses cluster, EgressPolicies policies,
                  cluster93.core.host.dns.DnsResolver resolver, List<String> internalDomains) {
        this(new NodePolicySource(cluster, policies, allowPrivate, internalDomains), new MetricsAccounting(vertx), resolver);
    }

    /** A gate that knows this node only, by its loopback and wildcard addresses, and the default policies: for tests. */
    public static Egress standalone(Vertx vertx, EgressPolicy policy) {
        return new Egress(new NodePolicySource(new ClusterAddresses(vertx, null, Set.of()), EgressPolicies.NONE,
                policy.allowPrivate(), List.of()), new MetricsAccounting(vertx),
                new cluster93.core.host.dns.DnsResolver(vertx, List.of()));
    }

    @Override
    public AccountingSink accounting() {
        return accounting;
    }

    public boolean allowPrivate() {
        return source.allowPrivate();
    }

    /** The cluster's egress policy, for its limits (D-116). */
    @Override
    public Future<ClusterEgress> cluster() {
        return source.cluster();
    }

    /** As {@link #address(String, String, int, EgressProtocol, String)} for an HTTP request. */
    @Override
    public Future<InetAddress> address(String tenant, String host, int port, String what) {
        return address(tenant, host, port, EgressProtocol.HTTP, what);
    }

    /**
     * Resolves {@code host} and picks the address {@code tenant}'s script may connect to on {@code port}. Fails with a
     * {@link HostException} coded {@code dns_failed} or {@code egress_denied}; {@code what} names the caller in the
     * message ("fetch", "http.request" and so on).
     */
    @Override
    public Future<InetAddress> address(String tenant, String host, int port, EgressProtocol protocol, String what) {
        return source.nodes().compose(nodes -> source.cluster().compose(clusterPolicy -> source.tenant(tenant)
                .compose(tenantPolicy -> resolver.addresses(host)
                        .recover(err -> Future.failedFuture(new HostException("TypeError", "dns_failed", "Could not resolve " + host)))
                        .compose(addresses -> settle(Decision.decide(host, addresses, port, protocol, nodes, source.allowPrivate(), clusterPolicy,
                                tenantPolicy), tenant, host, port, what)))));
    }

    /** The resolver the gate and {@code dns} share (D-122). */
    @Override
    public cluster93.core.host.dns.DnsResolver resolver() {
        return resolver;
    }

    /**
     * What {@code tenant}'s {@code dns} may show, under the policies as they are now (D-122): which addresses and names.
     */
    @Override
    public Future<Decision.DnsView> dnsView(String tenant) {
        return source.nodes().compose(nodes -> source.cluster().compose(clusterPolicy -> source.tenant(tenant)
                .map(tenantPolicy -> new Decision.DnsView(nodes, source.allowPrivate(), clusterPolicy, tenantPolicy, internalSuffixes))));
    }

    /**
     * A DNS server a script chose ({@code setServers}): the gate checks it as a {@code dns} connection, so a public
     * resolver is fine and one on a private network needs a rule (Tony, 2026-10-09).
     */
    @Override
    public Future<java.net.InetSocketAddress> dnsServer(String tenant, java.net.InetSocketAddress server) {
        return address(tenant, server.getAddress().getHostAddress(), server.getPort(), EgressProtocol.DNS, "dns")
                .map(address -> new java.net.InetSocketAddress(address, server.getPort()));
    }

    /** The address the tenant may connect to, or the coded failure a script sees (D-108), counted against the tenant. */
    private Future<InetAddress> settle(Decision.Verdict verdict, String tenant, String host, int port, String what) {
        if (verdict.address() != null) {
            return Future.succeededFuture(verdict.address());
        }
        accounting.denied(tenant, verdict.refused().code);
        String rule = verdict.rule() == null ? "" : " (rule \"" + verdict.rule().to() + "\""
                + (verdict.rule().note() == null ? "" : ": " + verdict.rule().note()) + ")";
        return Future.failedFuture(new HostException("TypeError", "egress_denied", what + " to " + host + ":" + port
                + " is not allowed: " + verdict.refused().explanation + rule));
    }
}
