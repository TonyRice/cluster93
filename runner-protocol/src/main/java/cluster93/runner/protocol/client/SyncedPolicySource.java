package cluster93.runner.protocol.client;

import cluster93.core.host.egress.ClusterEgress;
import cluster93.core.host.egress.EgressLimits;
import cluster93.core.host.egress.NodeAddresses;
import cluster93.core.host.egress.PolicySource;
import cluster93.core.host.egress.Posture;
import cluster93.core.host.egress.TenantEgress;
import cluster93.runner.protocol.message.PolicyMessage;
import io.vertx.core.Future;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The worker placement's policy (D-173): what the node last synced to this worker, and nothing else.
 *
 * <p>It **fails closed**. Before the first {@link #apply(PolicyMessage)}, and once the policy's
 * {@link PolicyMessage#expiresInMs() expiry} has passed without a refresh, it answers with a policy that allows
 * nothing: a worker that has lost the node must not keep deciding on rules the node may have withdrawn. The cluster's
 * own addresses from the last policy are kept, since they are a deny list — keeping them refuses more, not less.
 *
 * <p>An update older than the one in force is ignored, and a policy addressed to another tenant is refused, so a
 * re-bound worker (D-172) cannot be left deciding under the previous tenant's rules.
 */
public final class SyncedPolicySource implements PolicySource {

    /** The policy that allows nothing: no rules, and a posture that refuses whatever no rule allows. */
    private static final ClusterEgress REFUSING = new ClusterEgress(Posture.ALLOW_LIST, List.of(), java.util.Map.of(),
            EgressLimits.DEFAULT);

    private final LongSupplier clock;
    private volatile PolicyMessage policy;
    private volatile long expiresAt;
    private volatile ClusterEgress cluster = REFUSING;
    private volatile TenantEgress tenant;
    private volatile NodeAddresses nodes = NodeAddresses.EMPTY;

    public SyncedPolicySource() {
        this(System::currentTimeMillis);
    }

    /** @param clock for tests; the deadline is the only thing that depends on time */
    public SyncedPolicySource(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Takes a policy from the node. A version that isn't newer than the one in force changes nothing.
     *
     * @return whether it was applied
     */
    public boolean apply(PolicyMessage message) {
        if (message == null) {
            return false;
        }
        PolicyMessage inForce = policy;
        if (inForce != null && message.version() <= inForce.version()) {
            return false;
        }
        this.cluster = ClusterEgress.fromJson(message.clusterPolicy());
        this.tenant = TenantEgress.fromJson(message.tenantId(), message.tenantPolicy());
        this.nodes = addresses(message.nodeAddresses(), message.nodePorts());
        // A policy with no expiry is not usable: fail closed rather than trust a ruleset that can never go stale.
        this.expiresAt = message.expiresInMs() > 0 ? clock.getAsLong() + message.expiresInMs() : 0L;
        this.policy = message;
        return true;
    }

    /** Whether the policy in force is still fresh; false before the first one, and after its expiry. */
    public boolean fresh() {
        return policy != null && clock.getAsLong() < expiresAt;
    }

    /** The policy in force, or null while this worker has never been given one. */
    public PolicyMessage policy() {
        return policy;
    }

    /** Where egress is decided for the bound tenant, as the node last said; the worker is the default (D-173 item 8). */
    public String placement() {
        PolicyMessage inForce = policy;
        return inForce == null ? PolicyMessage.WORKER : inForce.placement();
    }

    @Override
    public boolean allowPrivate() {
        PolicyMessage inForce = policy;
        return fresh() && inForce.allowPrivate();
    }

    @Override
    public List<String> internalDomains() {
        PolicyMessage inForce = policy;
        return fresh() ? inForce.internalDomains() : List.of();
    }

    @Override
    public Future<ClusterEgress> cluster() {
        return Future.succeededFuture(fresh() ? cluster : REFUSING);
    }

    @Override
    public Future<TenantEgress> tenant(String tenant) {
        PolicyMessage inForce = policy;
        if (!fresh() || inForce == null || inForce.tenantId() == null || !inForce.tenantId().equals(tenant)) {
            // Nothing synced for this tenant, or a policy for another one: allow nothing rather than guess.
            return Future.succeededFuture(TenantEgress.none(tenant));
        }
        return Future.succeededFuture(this.tenant);
    }

    @Override
    public Future<NodeAddresses> nodes() {
        return Future.succeededFuture(nodes);
    }

    /** The addresses a policy named, ignoring any that no longer parse. */
    private static NodeAddresses addresses(List<String> addresses, List<Integer> ports) {
        Set<InetAddress> parsed = new HashSet<>();
        for (String address : addresses == null ? List.<String>of() : addresses) {
            try {
                parsed.add(InetAddress.getByName(address));
            } catch (UnknownHostException e) {
                // A policy is data: one unreadable entry must not throw away the rest, and must not widen anything.
            }
        }
        return new NodeAddresses(parsed, new HashSet<>(ports == null ? List.of() : ports));
    }
}
