package cluster93.cluster.ignite.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IgniteFuturesTest {

    @Test
    void transientClusterErrorsAreRetried() {
        assertThat(IgniteFutures.isTopologyChange(new IllegalStateException("Remote node has left topology"))).isTrue();
        assertThat(IgniteFutures.isTopologyChange(new RuntimeException("wrapped", new IllegalStateException(
                "Failed to reserve partitions for query (partition of PARTITIONED cache is not found or not in OWNING state)")))).isTrue();
        assertThat(IgniteFutures.isTopologyChange(new IllegalStateException(
                "Table was modified concurrently (please retry the query): KV"))).isTrue();
    }

    @Test
    void otherErrorsAreNot() {
        assertThat(IgniteFutures.isTopologyChange(new IllegalStateException("Failed to parse query"))).isFalse();
        assertThat(IgniteFutures.isTopologyChange(new IllegalStateException(
                "Failed to execute query because cache partition has been lost"))).as("lost data isn't transient").isFalse();
    }
}
