package cluster93.core.host.egress;

import cluster93.core.node.NodeInfo;
import cluster93.core.node.NodeRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The addresses of the cluster's own nodes, which scripts may never reach (D-108): every address a live node
 * advertises, and every address of this node's network interfaces. Refreshed at most every {@link #REFRESH_MS}.
 */
public final class ClusterAddresses {

    static final long REFRESH_MS = 10_000;

    /**
     * @param addresses the nodes' addresses
     * @param ports     this node's own ports; with the development switch on, a node is refused only on these
     */
    public record Snapshot(Set<InetAddress> addresses, Set<Integer> ports) {

        public static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of());

        public Snapshot {
            addresses = Set.copyOf(addresses);
            ports = Set.copyOf(ports);
        }

        /** Whether the address is a node's: any of them, or this machine's wildcard and loopback addresses. */
        public boolean isNode(InetAddress address) {
            return address.isAnyLocalAddress() || address.isLoopbackAddress() || addresses.contains(address);
        }
    }

    private final Vertx vertx;
    private final NodeRegistry nodes;
    private final Set<Integer> ports;
    private volatile Snapshot snapshot;
    private volatile long refreshedAt;

    /**
     * @param nodes the live nodes, or null to know only this one
     * @param ports this node's own listening ports
     */
    public ClusterAddresses(Vertx vertx, NodeRegistry nodes, Set<Integer> ports) {
        this.vertx = vertx;
        this.nodes = nodes;
        this.ports = Set.copyOf(ports);
    }

    public Future<Snapshot> current() {
        Snapshot known = snapshot;
        if (known != null && System.currentTimeMillis() - refreshedAt < REFRESH_MS) {
            return Future.succeededFuture(known);
        }
        Future<List<NodeInfo>> live = nodes == null ? Future.succeededFuture(List.of()) : nodes.liveNodes();
        return live.otherwise(err -> List.of()).compose(list -> vertx.executeBlocking(() -> {
            Set<InetAddress> addresses = new HashSet<>(ownAddresses());
            for (NodeInfo node : list) {
                String host = hostOf(node.endpoint());
                if (host == null) {
                    continue;
                }
                try {
                    Collections.addAll(addresses, InetAddress.getAllByName(host));
                } catch (UnknownHostException e) {
                    // A node whose name doesn't resolve can't be reached by that name either.
                }
            }
            Snapshot fresh = new Snapshot(addresses, ports);
            snapshot = fresh;
            refreshedAt = System.currentTimeMillis();
            return fresh;
        }, false));
    }

    static String hostOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        int colon = endpoint.lastIndexOf(':');
        String host = colon > 0 && endpoint.indexOf(']') < colon ? endpoint.substring(0, colon) : endpoint;
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static Set<InetAddress> ownAddresses() {
        Set<InetAddress> own = new HashSet<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    // A link-local IPv6 address carries its interface as a scope; compare it without.
                    own.add(InetAddress.getByAddress(address.getAddress()));
                }
            }
        } catch (SocketException | UnknownHostException e) {
            // Without the list, loopback and wildcard addresses are still refused by Snapshot.isNode.
        }
        return own;
    }
}
