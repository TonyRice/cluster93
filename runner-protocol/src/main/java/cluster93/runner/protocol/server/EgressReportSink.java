package cluster93.runner.protocol.server;

/**
 * Where a worker's own accounting goes on the node (D-176). The node decides egress itself in one placement and hands
 * that job to a worker in the other, so in the second it counts what the worker refused only because the worker says
 * so. This is the node's side of that: its metrics, and whatever else it wants to know.
 */
@FunctionalInterface
public interface EgressReportSink {

    /** A sink that counts nothing: a channel with no node behind it, and tests. */
    EgressReportSink NONE = (workerId, tenantId, reason, count) -> { };

    /**
     * One reason a worker refused connections for, as it reported it.
     *
     * @param workerId the worker that refused them; not a metric tag — workers come and go — but the thing to name in
     *                 a log line
     * @param tenantId the tenant they were refused for
     * @param reason   the {@code Decision.Reason#code} the worker refused them with
     * @param count    how many it refused for that reason since its last report
     */
    void refused(String workerId, String tenantId, String reason, long count);
}
