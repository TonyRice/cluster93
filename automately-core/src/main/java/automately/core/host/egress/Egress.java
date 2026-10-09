package automately.core.host.egress;

import automately.core.host.EgressPolicy;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.net.InetAddress;
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

    /** Names no script may resolve unless a rule names them (§8.2): special-use and conventional internal zones. */
    public static final List<String> INTERNAL_SUFFIXES = List.of("local", "internal", "lan", "home.arpa", "localhost", "cluster.local", "svc");

    private final Vertx vertx;
    private final boolean allowPrivate;
    private final ClusterAddresses cluster;
    private final EgressPolicies policies;
    private final automately.core.host.dns.DnsResolver resolver;
    private final List<String> internalSuffixes;

    /**
     * @param allowPrivate the development switch, {@code AUTOMATELY_EGRESS_ALLOW_PRIVATE}
     */
    public Egress(Vertx vertx, boolean allowPrivate, ClusterAddresses cluster, EgressPolicies policies) {
        this(vertx, allowPrivate, cluster, policies, new automately.core.host.dns.DnsResolver(vertx, List.of()), List.of());
    }

    /**
     * @param resolver the one resolver for scripts (D-122)
     * @param internalDomains {@code AUTOMATELY_EGRESS_INTERNAL_DOMAINS}: more internal zones, as {@code corp.example}
     */
    public Egress(Vertx vertx, boolean allowPrivate, ClusterAddresses cluster, EgressPolicies policies,
                  automately.core.host.dns.DnsResolver resolver, List<String> internalDomains) {
        this.resolver = resolver;
        List<String> suffixes = new java.util.ArrayList<>(INTERNAL_SUFFIXES);
        for (String domain : internalDomains) {
            String lower = domain.trim().toLowerCase(java.util.Locale.ROOT);
            lower = lower.startsWith("*.") ? lower.substring(2) : lower.startsWith(".") ? lower.substring(1) : lower;
            if (!lower.isEmpty()) {
                suffixes.add(lower);
            }
        }
        this.internalSuffixes = List.copyOf(suffixes);
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
                .compose(tenantPolicy -> resolver.addresses(host)
                        .recover(err -> Future.failedFuture(new HostException("TypeError", "dns_failed", "Could not resolve " + host)))
                        .compose(addresses -> settle(decide(host, addresses, port, protocol, nodes, allowPrivate, clusterPolicy,
                                tenantPolicy), tenant, host, port, what)))));
    }

    /** The resolver the gate and {@code dns} share (D-122). */
    public automately.core.host.dns.DnsResolver resolver() {
        return resolver;
    }

    /**
     * What {@code tenant}'s {@code dns} may show, under the policies as they are now (D-122): which addresses and names.
     */
    public Future<DnsView> dnsView(String tenant) {
        return cluster.current().compose(nodes -> policies.cluster().compose(clusterPolicy -> policies.of(tenant)
                .map(tenantPolicy -> new DnsView(nodes, allowPrivate, clusterPolicy, tenantPolicy, internalSuffixes))));
    }

    /**
     * A DNS server a script chose ({@code setServers}): the gate checks it as a {@code dns} connection, so a public
     * resolver is fine and one on a private network needs a rule (Tony, 2026-10-09).
     */
    public Future<java.net.InetSocketAddress> dnsServer(String tenant, java.net.InetSocketAddress server) {
        return address(tenant, server.getAddress().getHostAddress(), server.getPort(), EgressProtocol.DNS, "dns")
                .map(address -> new java.net.InetSocketAddress(address, server.getPort()));
    }

    /** What one tenant's {@code dns} may show (§8.2, D-122). */
    public record DnsView(ClusterAddresses.Snapshot nodes, boolean allowPrivate, ClusterEgress cluster, TenantEgress tenant,
                          List<String> internalSuffixes) {

        /** Whether {@code address} (answered for {@code host}) may be shown: the tenant could connect to it somewhere. */
        public boolean visible(String host, InetAddress address) {
            return Egress.visible(host, address, nodes, allowPrivate, cluster, tenant);
        }

        /**
         * Whether {@code name} is hidden: it's in an internal zone (single-label, a special-use suffix or one the
         * operator lists), no allow rule names it, and the development switch is off.
         */
        public boolean hidden(String name) {
            if (allowPrivate || !Egress.internal(name, internalSuffixes)) {
                return false;
            }
            for (List<EgressRule> rules : List.of(tenant.rules(), cluster.rules())) {
                for (EgressRule rule : rules) {
                    if (rule.action() == EgressRule.Action.ALLOW && (rule.kind() == EgressRule.Kind.HOST || rule.kind() == EgressRule.Kind.SUFFIX)
                            && rule.names(name, null, null, cluster.sets())) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** Whether {@code name} is in an internal zone: one label, or under one of {@code suffixes}. */
    static boolean internal(String name, List<String> suffixes) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        lower = lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
        if (!lower.contains(".")) {
            return true;
        }
        for (String suffix : suffixes) {
            if (lower.equals(suffix) || lower.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code dns} may show {@code address} for {@code host} (D-122): a connection to it would be allowed on some
     * port by some protocol. Cluster nodes never, unless the development switch is on; a deny rule hides it only if it
     * applies on every port to every protocol or to dns; an allow rule shows it whatever its ports and protocols; then
     * the development switch and the posture, as for connections.
     */
    public static boolean visible(String host, InetAddress address, ClusterAddresses.Snapshot nodes, boolean allowPrivate,
                                  ClusterEgress cluster, TenantEgress tenant) {
        InetAddress checked = checked(address);
        if (nodes.isNode(address) || nodes.isNode(checked)) {
            return allowPrivate;
        }
        AddressSets sets = cluster.sets();
        for (List<EgressRule> rules : List.of(cluster.rules(), tenant.rules())) {
            for (EgressRule rule : rules) {
                if (rule.hidesFromDns() && rule.names(host, address, checked, sets)) {
                    return false;
                }
            }
        }
        for (List<EgressRule> rules : List.of(tenant.rules(), cluster.rules())) {
            for (EgressRule rule : rules) {
                if (rule.action() == EgressRule.Action.ALLOW && rule.names(host, address, checked, sets)) {
                    return true;
                }
            }
        }
        boolean isPublic = AddressSets.isPublic(checked);
        if (allowPrivate && !isPublic) {
            return true;
        }
        Posture posture = tenant.posture() != null ? tenant.posture() : cluster.posture();
        return posture != Posture.ALLOW_LIST && isPublic;
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
