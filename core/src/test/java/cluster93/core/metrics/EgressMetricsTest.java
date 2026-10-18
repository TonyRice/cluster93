package cluster93.core.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a refusal is counted as, once a worker can refuse on its own (D-176). The two series have to stay apart: "this
 * node refused it" and "a worker refused it" are different facts, and a worker placement is only auditable if an
 * operator can still tell which one happened.
 */
class EgressMetricsTest {

    private Vertx vertx;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        registry = new SimpleMeterRegistry();
        Metrics.install(vertx, registry);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A worker's refusals land in their own series, next to the node's own (D-176)")
    void aWorkersRefusalsAreTheirOwnSeries() {
        Metrics metrics = Metrics.of(vertx);

        metrics.egressDenied("acme", "connect_rate");
        metrics.workerEgressDenied("acme", "connect_rate", 3);
        metrics.workerEgressDenied("acme", "not_allowed", 1);

        assertThat(registry.get("c93.egress.denied").tag("tenant", "acme").tag("reason", "connect_rate")
                .counter().count()).as("what this node refused itself").isEqualTo(1.0);
        assertThat(registry.get("c93.worker.egress.denied").tag("tenant", "acme").tag("reason", "connect_rate")
                .counter().count()).as("what a worker said it refused, counted in its own series").isEqualTo(3.0);
        assertThat(registry.get("c93.worker.egress.denied").tag("tenant", "acme").tag("reason", "not_allowed")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("c93.egress.denied").tag("tenant", "acme").tag("reason", "not_allowed").counter())
                .as("the node counted no such refusal, so its series does not even exist").isNull();
    }
}
