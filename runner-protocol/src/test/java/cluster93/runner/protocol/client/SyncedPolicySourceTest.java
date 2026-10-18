package cluster93.runner.protocol.client;

import cluster93.core.host.egress.Decision;
import cluster93.core.host.egress.EgressLimits;
import cluster93.core.host.egress.EgressProtocol;
import cluster93.core.host.egress.Placement;
import cluster93.runner.protocol.message.PolicyMessage;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a worker decides on when the node decides nothing (D-173): the synced policy, and how it fails when there
 * isn't a fresh one. These are the local checks a worker placement runs on its own resolved addresses.
 */
class SyncedPolicySourceTest {

    private static final String TENANT = "acme";
    private static final InetAddress PUBLIC_IP = ip("93.184.216.34");
    private static final InetAddress PRIVATE_IP = ip("10.0.5.20");

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A node's policy: default-deny cluster posture, one tenant rule for a public API, and the cluster's own nodes. */
    private static PolicyMessage aPolicy(long version, long expiresInMs) {
        return aPolicy(version, expiresInMs, null);
    }

    /** The same, with the node's allocation for this worker (D-176), or none. */
    private static PolicyMessage aPolicy(long version, long expiresInMs, JsonObject budget) {
        return new PolicyMessage(TENANT, Placement.WORKER, version, expiresInMs, false, List.of("corp.example"),
                new JsonObject().put("posture", "allow-list").put("rules", new JsonArray())
                        .put("limits", new JsonObject().put("connectsPerMinute", 120).put("socketsPerExecution", 16)
                                .put("socketsPerTenantPerNode", 256)),
                new JsonObject().put("rules", new JsonArray()
                        .add(new JsonObject().put("action", "allow").put("to", "api.stripe.com").put("ports", new JsonArray().add(443)))),
                List.of("10.0.0.5"), List.of(9393, 9394), budget);
    }

    /** The decision a worker's gate would make with this source, for a host that resolves to {@code address}. */
    private Decision.Verdict decide(SyncedPolicySource source, String host, InetAddress address, int port) {
        return decision(source, host, address, port).join();
    }

    private java.util.concurrent.CompletableFuture<Decision.Verdict> decision(SyncedPolicySource source, String host,
                                                                             InetAddress address, int port) {
        return source.nodes()
                .compose(nodes -> source.cluster()
                        .compose(cluster -> source.tenant(TENANT)
                                .map(tenant -> Decision.decide(host, List.of(address), port, EgressProtocol.HTTP,
                                        nodes, source.allowPrivate(), cluster, tenant))))
                .toCompletionStage().toCompletableFuture();
    }

    @Test
    @DisplayName("With nothing synced, a worker refuses everything — including a public address the cluster allows")
    void nothingSyncedRefusesEverything() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);

        assertThat(source.fresh()).isFalse();
        assertThat(source.placement()).isEqualTo(Placement.WORKER);
        assertThat(source.allowPrivate()).as("the development switch is part of the policy, not of the worker").isFalse();
        Decision.Verdict verdict = decide(source, "api.stripe.com", PUBLIC_IP, 443);
        assertThat(verdict.address()).isNull();
        assertThat(verdict.refused()).isEqualTo(Decision.Reason.NOT_ALLOWED);
    }

    @Test
    @DisplayName("A synced policy is the one the decision runs: its rules allow, and its node addresses are refused")
    void aSyncedPolicyDecides() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        assertThat(source.apply(aPolicy(1L, 60_000L))).isTrue();

        assertThat(source.fresh()).isTrue();
        assertThat(source.internalDomains()).containsExactly("corp.example");
        assertThat(decide(source, "api.stripe.com", PUBLIC_IP, 443).address()).isEqualTo(PUBLIC_IP);
        assertThat(decide(source, "api.stripe.com", PUBLIC_IP, 80).refused())
                .as("the rule names port 443 only").isEqualTo(Decision.Reason.NOT_ALLOWED);
        assertThat(decide(source, "db.internal", PRIVATE_IP, 5432).refused())
                .as("nothing allows it, and the posture is default-deny").isEqualTo(Decision.Reason.NOT_ALLOWED);
        assertThat(decide(source, "node", ip("10.0.0.5"), 9394).refused())
                .as("the cluster's own addresses came with the policy").isEqualTo(Decision.Reason.CLUSTER_NODE);
    }

    @Test
    @DisplayName("An expired policy refuses everything, so a worker that lost the node stops deciding (D-173)")
    void anExpiredPolicyRefusesEverything() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        source.apply(aPolicy(1L, 30_000L));
        assertThat(decide(source, "api.stripe.com", PUBLIC_IP, 443).address()).isEqualTo(PUBLIC_IP);

        now.addAndGet(30_001L);

        assertThat(source.fresh()).isFalse();
        assertThat(decide(source, "api.stripe.com", PUBLIC_IP, 443).refused()).isEqualTo(Decision.Reason.NOT_ALLOWED);
        assertThat(source.nodes().result()).as("the deny list is kept: keeping it refuses more, not less")
                .isNotNull();
        assertThat(decide(source, "node", ip("10.0.0.5"), 9394).refused()).isEqualTo(Decision.Reason.CLUSTER_NODE);
    }

    @Test
    @DisplayName("A policy with no expiry is not usable at all (D-173)")
    void noExpiryMeansNoTrust() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        source.apply(aPolicy(1L, 0L));

        assertThat(source.fresh()).isFalse();
        assertThat(decide(source, "api.stripe.com", PUBLIC_IP, 443).refused()).isEqualTo(Decision.Reason.NOT_ALLOWED);
    }

    @Test
    @DisplayName("An older version changes nothing, so a delayed update cannot revive a policy (D-173)")
    void anOlderVersionIsIgnored() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        assertThat(source.apply(aPolicy(5L, 60_000L))).isTrue();
        assertThat(source.apply(aPolicy(4L, 60_000L))).as("not newer").isFalse();
        assertThat(source.apply(new PolicyMessage(TENANT, Placement.WORKER, 5L, 60_000L, false, List.of(),
                new JsonObject().put("posture", "allow-list"), new JsonObject(), List.of(), List.of())))
                .as("the same version is not newer either").isFalse();
        assertThat(decide(source, "api.stripe.com", PUBLIC_IP, 443).address())
                .as("the policy in force is still the one with the rule").isEqualTo(PUBLIC_IP);
    }

    @Test
    @DisplayName("A policy for another tenant is refused, so a re-bound worker cannot keep the old tenant's rules (D-173)")
    void anotherTenantsPolicyIsRefused() {        SyncedPolicySource source = new SyncedPolicySource(now::get);
        source.apply(new PolicyMessage("globex", Placement.WORKER, 1L, 60_000L, false, List.of(),
                new JsonObject().put("posture", "allow-list"), new JsonObject().put("rules", new JsonArray()
                .add(new JsonObject().put("action", "allow").put("to", "api.stripe.com").put("ports", new JsonArray().add(443)))),
                List.of(), List.of()));

        assertThat(source.fresh()).as("it was accepted as a policy, but it is not this worker's tenant's").isTrue();
        Decision.Verdict verdict = decide(source, "api.stripe.com", PUBLIC_IP, 443);
        assertThat(verdict.address()).as("globex's rules must not authorise acme's run").isNull();
        assertThat(verdict.refused()).isEqualTo(Decision.Reason.NOT_ALLOWED);
    }

    @Test
    @DisplayName("The placement is the tenant's, and it changes when the node says so — a re-bound worker switches sides (D-173 item 8)")
    void thePlacementFollowsThePolicyInForce() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        assertThat(source.fresh()).as("nothing has been synced yet, so the worker decides nothing by itself").isFalse();

        // A tenant the node decides for: the worker is told so, and hands its network calls up.
        source.apply(new PolicyMessage(TENANT, Placement.NODE, 1L, 60_000L, false, List.of(),
                new JsonObject().put("posture", "allow-list"), new JsonObject(), List.of(), List.of()));
        assertThat(source.placement()).as("the node decides for this tenant").isEqualTo(Placement.NODE);
        assertThat(source.fresh()).as("and it is still a policy the worker holds, fresh and readable").isTrue();

        // The same connection re-bound to a tenant it decides for (D-172): the next payload is what moves it back.
        source.apply(new PolicyMessage(TENANT, Placement.WORKER, 2L, 60_000L, false, List.of(),
                new JsonObject().put("posture", "allow-list"), new JsonObject(), List.of(), List.of()));
        assertThat(source.placement()).as("re-bound, and deciding again").isEqualTo(Placement.WORKER);
    }

    @Test
    @DisplayName("The limits this worker enforces are the share the node allocated it (D-176)")
    void theAllocationIsWhatTheWorkerEnforces() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        JsonObject allocation = new JsonObject().put("connectsPerMinute", 60).put("socketsPerExecution", 16)
                .put("socketsPerTenantPerNode", 128);
        source.apply(aPolicy(1L, 60_000L, allocation));

        assertThat(source.cluster().result().limits()).as("a client reads its limits from the cluster policy it is given")
                .isEqualTo(new EgressLimits(60, 16, 128));
        assertThat(source.budget()).isEqualTo(new EgressLimits(60, 16, 128));
        assertThat(source.policy().clusterPolicy().getJsonObject("limits"))
                .as("what the node sent is still the cluster's own policy: only the worker's enforcement is divided")
                .isEqualTo(new EgressLimits(120, 16, 256).toJson());
    }

    @Test
    @DisplayName("With no allocation, the cluster's own limits stand: what a node that predates one means (D-176)")
    void noAllocationLeavesTheClustersLimits() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        source.apply(aPolicy(1L, 60_000L));

        assertThat(source.budget()).as("nothing was allocated").isNull();
        assertThat(source.cluster().result().limits()).isEqualTo(new EgressLimits(120, 16, 256));
    }

    @Test
    @DisplayName("An allocation this worker cannot read neither kills it nor widens what it may do (D-176)")
    void anUnreadableAllocationIsIgnored() {
        SyncedPolicySource source = new SyncedPolicySource(now::get);
        source.apply(aPolicy(1L, 60_000L, new JsonObject().put("connectsPerMinute", 0)));

        assertThat(source.fresh()).as("the policy itself was applied").isTrue();
        assertThat(source.budget()).as("the unreadable share is no share").isNull();
        assertThat(source.cluster().result().limits()).as("the cluster's numbers are what is left standing")
                .isEqualTo(new EgressLimits(120, 16, 256));
        assertThat(decide(source, "api.stripe.com", PUBLIC_IP, 443).address()).as("and its rules still decide")
                .isEqualTo(PUBLIC_IP);
    }
}
