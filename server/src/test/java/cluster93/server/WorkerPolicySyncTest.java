package cluster93.server;

import cluster93.core.host.egress.ClusterAddresses;
import cluster93.core.host.egress.EgressPolicies;
import cluster93.core.host.egress.EgressRule;
import cluster93.core.host.egress.Placement;
import cluster93.core.host.egress.TenantEgress;
import cluster93.core.identity.SharedDataRecords;
import cluster93.runner.protocol.message.PolicyMessage;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The node's side of the rules delivery (D-173): what a worker is told, and when. The payload has to be the policy as
 * an operator set it — a rule lost here is a rule the worker cannot apply — and the three triggers have to fire, or a
 * worker keeps deciding on rules the node has withdrawn.
 */
class WorkerPolicySyncTest {

    private Vertx vertx;
    private EgressPolicies policies;
    private ClusterAddresses cluster;
    private final List<String> refreshed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        policies = new EgressPolicies(new SharedDataRecords(vertx, EgressPolicies.KIND),
                new SharedDataRecords(vertx, EgressPolicies.CLUSTER_KIND), vertx);
        // No registry: the node knows itself, by its loopback and wildcard addresses, which is what a test needs.
        cluster = new ClusterAddresses(vertx, null, Set.of(9393, 9394));
        refreshed.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private WorkerPolicySync sync(long ttlMs, long refreshMs) {
        return new WorkerPolicySync(policies, cluster, false, List.of("corp.example"), Placement.WORKER, ttlMs,
                refreshMs, tenant -> refreshed.add(tenant == null ? "*" : tenant));
    }

    @Test
    @DisplayName("A payload carries the tenant's rules as an operator set them, the cluster's, and its own addresses (D-173)")
    void thePayloadIsThePolicyAsSet() throws Exception {
        TenantEgress acme = TenantEgress.fromJson("acme", new JsonObject().put("posture", "allow-list")
                .put("rules", new JsonArray().add(new JsonObject().put("action", "allow")
                        .put("to", "api.stripe.com").put("ports", new JsonArray().add(443)))));
        policies.put(acme).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        WorkerPolicySync sync = sync(WorkerPolicySync.TTL_MS, WorkerPolicySync.REFRESH_MS);
        PolicyMessage policy = sync.forTenant("acme").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(policy.tenantId()).isEqualTo("acme");
        assertThat(policy.placement()).as("the worker is the placement the node tells a worker about").isEqualTo(Placement.WORKER);
        assertThat(policy.version()).as("a version to ignore stale updates by").isPositive();
        assertThat(policy.expiresInMs()).isEqualTo(WorkerPolicySync.TTL_MS);
        assertThat(policy.allowPrivate()).isFalse();
        assertThat(policy.internalDomains()).containsExactly("corp.example");
        assertThat(policy.nodePorts()).containsExactly(9393, 9394);
        assertThat(policy.nodeAddresses()).as("this node always refuses its own loopback and wildcard addresses").isNotEmpty();

        // What the rules survive the trip *in*: the worker parses them with the same shared code, so a payload it
        // cannot read is a worker that never gets its rules — or one that dies trying.
        assertThatCode(() -> cluster93.core.host.egress.ClusterEgress.fromJson(policy.clusterPolicy()))
                .as("the cluster policy the node sent is one the worker can read")
                .doesNotThrowAnyException();
        assertThatCode(() -> TenantEgress.fromJson("acme", policy.tenantPolicy()))
                .as("and so is the tenant's")
                .doesNotThrowAnyException();

        // The rules survive the trip in the shape the shared code reads back (what the worker does with them).
        TenantEgress readBack = TenantEgress.fromJson("acme", policy.tenantPolicy());
        assertThat(readBack.rules()).hasSize(1);
        EgressRule rule = readBack.rules().getFirst();
        assertThat(rule.action()).isEqualTo(EgressRule.Action.ALLOW);
        assertThat(rule.to()).isEqualTo("api.stripe.com");
        assertThat(readBack.posture()).isEqualTo(cluster93.core.host.egress.Posture.ALLOW_LIST);
    }

    @Test
    @DisplayName("Every payload gets a higher version, so a worker can ignore one that arrives out of order (D-173)")
    void versionsClimb() throws Exception {
        WorkerPolicySync sync = sync(WorkerPolicySync.TTL_MS, WorkerPolicySync.REFRESH_MS);
        long first = sync.forTenant("acme").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).version();
        long second = sync.forTenant("acme").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).version();
        long third = sync.forTenant("globex").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).version();

        assertThat(second).isGreaterThan(first);
        assertThat(third).as("one counter per node, not per tenant, so versions never collide").isGreaterThan(second);
    }

    @Test
    @DisplayName("A rule change re-sends the policy to that tenant's workers, and a cluster change to everyone (D-173)")
    void changesArePushed() throws Exception {
        WorkerPolicySync sync = sync(WorkerPolicySync.TTL_MS, WorkerPolicySync.REFRESH_MS);
        sync.start(vertx);

        // Setting a policy is what an operator does; the announcement reaches this node's listener and its workers.
        policies.put(TenantEgress.fromJson("acme", new JsonObject().put("rules", new JsonArray())))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        waitFor("acme's change to be pushed", () -> refreshed.contains("acme"));

        policies.putCluster(cluster93.core.host.egress.ClusterEgress.DEFAULT)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        waitFor("a cluster-wide change to be pushed to every tenant", () -> refreshed.contains("*"));
    }

    @Test
    @DisplayName("The interval refreshes every bound worker, so a lost node shows up as an expiry, not as silence (D-173)")
    void theIntervalRefreshes() throws Exception {
        WorkerPolicySync sync = sync(300L, 50L);
        sync.start(vertx);

        waitFor("the refresh interval to fire", () -> refreshed.contains("*"));
        sync.close();
        int seen = refreshed.size();
        Thread.sleep(300L);
        assertThat(refreshed).as("a closed sync stops refreshing").hasSize(seen);
    }

    @Test
    @DisplayName("One tenant can be placed the other way, while the node's default stays what it is (D-173 item 8)")
    void aTenantCanBePlacedAgainstTheNodesDefault() throws Exception {
        policies.put(TenantEgress.fromJson("on-node", new JsonObject().put("placement", "node")))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        policies.put(TenantEgress.fromJson("in-worker", new JsonObject().put("placement", "worker")))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // The node decides its tenants in their workers, and one tenant asks for the node instead.
        WorkerPolicySync workerByDefault = sync(WorkerPolicySync.TTL_MS, WorkerPolicySync.REFRESH_MS);
        assertThat(placed(workerByDefault, "on-node")).as("the tenant's own choice wins over the node's")
                .isEqualTo(Placement.NODE);
        assertThat(placed(workerByDefault, "in-worker")).isEqualTo(Placement.WORKER);
        assertThat(placed(workerByDefault, "silent")).as("a tenant that names none follows the node's")
                .isEqualTo(Placement.WORKER);

        // The other direction: a node that decides everything itself, with one tenant placed in its worker. The sync
        // has to exist for this, which is why a node builds it whatever its own placement is.
        WorkerPolicySync nodeByDefault = new WorkerPolicySync(policies, cluster, false, List.of("corp.example"),
                Placement.NODE, WorkerPolicySync.TTL_MS, WorkerPolicySync.REFRESH_MS, tenant -> { });
        assertThat(placed(nodeByDefault, "in-worker")).as("and the same override the other way round")
                .isEqualTo(Placement.WORKER);
        assertThat(placed(nodeByDefault, "silent")).isEqualTo(Placement.NODE);

        // Read per payload rather than remembered, so an operator's change arrives with the next bind (D-172).
        policies.put(TenantEgress.fromJson("in-worker", new JsonObject().put("placement", "node")))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(placed(nodeByDefault, "in-worker")).isEqualTo(Placement.NODE);
    }

    private static Placement placed(WorkerPolicySync sync, String tenant) throws Exception {
        return sync.forTenant(tenant).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).placement();
    }

    @Test
    @DisplayName("A payload carries the share of the node's limits one of that tenant's workers may use (D-176)")
    void thePayloadCarriesTheWorkersShare() throws Exception {
        // Two workers for a tenant, so each enforces half: one worker's full allowance would be twice the cluster's.
        WorkerPolicySync forTwo = new WorkerPolicySync(policies, cluster, false, List.of("corp.example"),
                Placement.WORKER, 2, WorkerPolicySync.TTL_MS, WorkerPolicySync.REFRESH_MS, tenant -> { });
        PolicyMessage shared = forTwo.forTenant("acme").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(cluster93.core.host.egress.EgressLimits.fromJson(shared.budget()))
                .as("the cluster's default 120/16/256, halved for each of the tenant's two workers")
                .isEqualTo(new cluster93.core.host.egress.EgressLimits(60, 16, 128));
        assertThat(cluster93.core.host.egress.ClusterEgress.fromJson(shared.clusterPolicy()).limits())
                .as("the cluster's own policy still says what the cluster set, which is what the next reader needs")
                .isEqualTo(cluster93.core.host.egress.EgressLimits.DEFAULT);

        // One worker for a tenant: no division, and the worker enforces exactly what the cluster set for the node.
        PolicyMessage whole = sync(WorkerPolicySync.TTL_MS, WorkerPolicySync.REFRESH_MS).forTenant("acme")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(cluster93.core.host.egress.EgressLimits.fromJson(whole.budget()))
                .isEqualTo(cluster93.core.host.egress.EgressLimits.DEFAULT);
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }
}
