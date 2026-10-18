package cluster93.core.host.egress;

import cluster93.core.host.EgressPolicy;

import java.net.InetAddress;
import java.util.List;

/**
 * The egress decision itself, pure (D-108, D-111, D-173): the addresses and the policy go in, the address to connect
 * to comes out. It resolves nothing, dials nothing and counts nothing — those belong to whoever holds the connection,
 * which is why this is the part that can run in a worker as well as on a node.
 *
 * <p>For each address, in order: a cluster node is refused, on every port (with the development switch on, only on the
 * node's own ports); then a cluster deny rule refuses; a tenant deny rule refuses; a tenant allow rule allows; a cluster
 * allow rule allows; the development switch allows anything else that isn't public; and the posture decides the rest
 * (the tenant's, else the cluster's): {@code public} allows public addresses, {@code allow-list} refuses.
 */
public final class Decision {

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

    private Decision() {
    }

    /** As {@link #decide(String, List, int, EgressProtocol, NodeAddresses, boolean, ClusterEgress, TenantEgress)}
     * for an HTTP request under the default cluster policy. */
    public static Verdict decide(String host, List<InetAddress> addresses, int port, NodeAddresses nodes,
                                 boolean allowPrivate, TenantEgress policy) {
        return decide(host, addresses, port, EgressProtocol.HTTP, nodes, allowPrivate, ClusterEgress.DEFAULT, policy);
    }

    /** The first address of {@code addresses} that may be reached, in their order; pure, for testing. */
    public static Verdict decide(String host, List<InetAddress> addresses, int port, EgressProtocol protocol,
                                 NodeAddresses nodes, boolean allowPrivate, ClusterEgress cluster, TenantEgress tenant) {
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
                                 NodeAddresses nodes, boolean allowPrivate, ClusterEgress cluster, TenantEgress tenant,
                                 AddressSets sets, Posture posture) {
        boolean local = NodeAddresses.isLocal(address) || NodeAddresses.isLocal(checked);
        boolean nodeAddress = nodes.isNode(address) || nodes.isNode(checked);
        boolean nodePort = nodes.ports().contains(port);
        if (local) {
            // Here: refused by default, and a tenant may be allowed it (D-174) — but never on this node's own ports,
            // which are the cluster's own services (the API, the ingress, the internal endpoint, metrics, Ignite).
            if (nodePort || !(tenant.allowLocal() || allowPrivate)) {
                return new Verdict(null, Reason.CLUSTER_NODE, null);
            }
        } else if (nodeAddress && (!allowPrivate || nodePort)) {
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
        // The development switch opens every private address; a tenant's own grant opens only "here" (D-174), which is
        // still a private address, so it needs this line too — and nothing else.
        if ((allowPrivate || (local && tenant.allowLocal())) && !isPublic) {
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

    /** What one tenant's {@code dns} may show (§8.2, D-122). */
    public record DnsView(NodeAddresses nodes, boolean allowPrivate, ClusterEgress cluster, TenantEgress tenant,
                          List<String> internalSuffixes) {

        /** Whether {@code address} (answered for {@code host}) may be shown: the tenant could connect to it somewhere. */
        public boolean visible(String host, InetAddress address) {
            return Decision.visible(host, address, nodes, allowPrivate, cluster, tenant);
        }

        /**
         * Whether {@code name} is hidden: it's in an internal zone (single-label, a special-use suffix or one the
         * operator lists), no allow rule names it, and the development switch is off.
         */
        public boolean hidden(String name) {
            if (allowPrivate || !Decision.internal(name, internalSuffixes)) {
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
    public static boolean visible(String host, InetAddress address, NodeAddresses nodes, boolean allowPrivate,
                                  ClusterEgress cluster, TenantEgress tenant) {
        InetAddress checked = checked(address);
        if (NodeAddresses.isLocal(address) || NodeAddresses.isLocal(checked)) {
            return tenant.allowLocal() || allowPrivate;
        }
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

    /** The address judged: the IPv4 address an IPv6 form carries, or the address itself. */
    static InetAddress checked(InetAddress address) {
        InetAddress embedded = EgressPolicy.embeddedIpv4(address);
        return embedded == null ? address : embedded;
    }
}
