package cluster93.core.host.egress;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One worker's share of a per-node allowance (D-176): the arithmetic that keeps <em>n</em> workers of one tenant
 * inside the limits the cluster set for it on this node, rather than handing each of them the whole allowance.
 */
class EgressLimitsTest {

    @Test
    @DisplayName("A tenant's workers together get the node's allowance, not one allowance each (D-176)")
    void workersShareThePerNodeAllowance() {
        EgressLimits node = new EgressLimits(120, 16, 256);

        EgressLimits one = node.perWorker(2);
        assertThat(one.connectsPerMinute()).as("2 workers at 60 each is the 120 the cluster set for the node").isEqualTo(60);
        assertThat(one.socketsPerTenantPerNode()).as("and 2 × 128 is the 256 it set").isEqualTo(128);
        assertThat(one.socketsPerExecution()).as("a script never spans workers, so this one is one worker's already")
                .isEqualTo(16);

        assertThat(one.connectsPerMinute() * 2).isEqualTo(node.connectsPerMinute());
        assertThat(one.socketsPerTenantPerNode() * 2).isEqualTo(node.socketsPerTenantPerNode());
    }

    @Test
    @DisplayName("The only worker a tenant can have enforces exactly the cluster's numbers")
    void oneWorkerEnforcesTheWholeAllowance() {
        EgressLimits node = new EgressLimits(120, 16, 256);

        assertThat(node.perWorker(1)).isSameAs(node);
        assertThat(node.perWorker(0)).as("a nonsensical count is one worker, not a division by zero").isEqualTo(node);
        assertThat(node.perWorker(-3)).isEqualTo(node);
    }

    @Test
    @DisplayName("A share floors at one, because a worker that may open nothing cannot do its work (D-165, D-176)")
    void aShareFloorsAtOne() {
        EgressLimits tiny = new EgressLimits(3, 16, 3);

        EgressLimits shared = tiny.perWorker(8);
        assertThat(shared.connectsPerMinute()).as("3 divided by 8, floored").isEqualTo(1);
        assertThat(shared.socketsPerTenantPerNode()).isEqualTo(1);

        EgressLimits none = new EgressLimits(1, 1, 1).perWorker(4);
        assertThat(none.connectsPerMinute()).as("an allowance that rounds away is still one connection")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("The limits a policy carries are the ones a worker reads back, allocation or none (D-176)")
    void limitsSurviveThePolicy() {
        EgressLimits share = new EgressLimits(60, 16, 128);

        assertThat(EgressLimits.fromJson(share.toJson())).isEqualTo(share);
        assertThat(EgressLimits.fromJson(null)).as("a policy with no limits is the cluster's default")
                .isEqualTo(EgressLimits.DEFAULT);
    }

    @Test
    @DisplayName("An unreadable allocation is refused, not guessed at")
    void anUnreadableAllocationThrows() {
        assertThatThrownBy(() -> EgressLimits.fromJson(new JsonObject().put("connectsPerMinute", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
