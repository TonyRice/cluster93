package cluster93.core.host.egress;

import java.net.InetAddress;
import java.util.Set;

/**
 * The cluster's own node addresses and this node's ports, as a decision sees them (D-108). Pure, and deliberately
 * separate from {@link ClusterAddresses}, which reads the live registry: this is what a {@link PolicySource} hands
 * over, so one decision runs whether the addresses came from the registry (the node's placement) or from the rules
 * the node synced to a worker (D-173).
 *
 * @param addresses the nodes' addresses
 * @param ports     this node's own ports; with the development switch on, a node is refused only on these
 */
public record NodeAddresses(Set<InetAddress> addresses, Set<Integer> ports) {

    public static final NodeAddresses EMPTY = new NodeAddresses(Set.of(), Set.of());

    public NodeAddresses {
        addresses = Set.copyOf(addresses);
        ports = Set.copyOf(ports);
    }

    /** Whether the address is one the cluster advertises: a node's own address, as the policy named it. */
    public boolean isNode(InetAddress address) {
        return addresses.contains(address);
    }

    /**
     * Whether the address is <b>here</b> — this machine's wildcard or loopback, which is the node's own address on a
     * node and the container's own on a worker (D-174). Kept apart from {@link #isNode} because the two are decided
     * differently: the cluster's addresses are refused whatever a tenant grants, and "here" is a tenant's choice.
     */
    public static boolean isLocal(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress();
    }
}
