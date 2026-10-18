package cluster93.core.host.egress;

import io.vertx.core.Vertx;

/**
 * The node's accounting placement (D-173): a refusal is counted against the tenant with its reason, as it always was
 * (D-108). A worker's placement keeps its own tally instead ({@code WorkerAccounting}), which the node is told about in
 * batches and counts in a series of its own, so a refusal the node did not make is still visible (D-176).
 */
public final class MetricsAccounting implements AccountingSink {

    private final Vertx vertx;

    public MetricsAccounting(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public void denied(String tenant, String reason) {
        cluster93.core.metrics.Metrics.of(vertx).egressDenied(tenant == null ? "-" : tenant, reason);
    }

    @Override
    public void opened(String tenant, boolean tls) {
        cluster93.core.metrics.Metrics.of(vertx).socketOpened(tenant == null ? "-" : tenant, tls);
    }

    @Override
    public void bytes(String tenant, String direction, long length) {
        cluster93.core.metrics.Metrics.of(vertx).egressBytes(tenant == null ? "-" : tenant, direction, length);
    }
}
