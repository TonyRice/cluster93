package cluster93.core.host.egress;

import cluster93.core.host.dns.DnsResolver;
import io.vertx.core.Future;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * What the network clients need from whoever decides egress (D-173): resolve a name and answer the address that may be
 * connected to, the DNS view, and the cluster's limits. Deliberately small, and deliberately not `Egress`: the clients
 * run wherever the connection is made — in a node's process, or in a worker's — and they should depend on the decision,
 * not on the node's way of assembling it.
 *
 * <p>{@link Egress} is the node's implementation; a worker's is its own, over the rules the node synced.
 */
public interface Gate {

    /** As {@link #address(String, String, int, EgressProtocol, String)} for an HTTP request. */
    Future<InetAddress> address(String tenant, String host, int port, String what);

    /**
     * Resolves {@code host} and picks the address {@code tenant}'s script may connect to on {@code port}. Fails with a
     * coded error the script sees ({@code dns_failed}, {@code egress_denied}); {@code what} names the caller.
     */
    Future<InetAddress> address(String tenant, String host, int port, EgressProtocol protocol, String what);

    /** What {@code tenant}'s {@code dns} may show (D-122). */
    Future<Decision.DnsView> dnsView(String tenant);

    /** A DNS server a script chose: checked as a {@code dns} connection, so a private one needs a rule (D-122). */
    Future<InetSocketAddress> dnsServer(String tenant, InetSocketAddress server);

    /** The resolver the gate and {@code dns} share, and the one the clients' own lookups use (D-122). */
    DnsResolver resolver();

    /** The cluster's policy, for its socket and request limits (D-116). */
    Future<ClusterEgress> cluster();

    /**
     * Where this placement counts what it did (D-173): a node's metrics, or a worker's own tallies. The clients report
     * through it rather than reaching a metrics registry, which is what keeps them usable in both placements.
     */
    AccountingSink accounting();
}
