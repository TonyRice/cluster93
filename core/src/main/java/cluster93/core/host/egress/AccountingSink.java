package cluster93.core.host.egress;

/**
 * Where a decision's outcomes are counted (D-173, D-176). On a node that is its metrics; in a worker it is the worker's
 * own tally, which the node is told about in batches — so the same gate can run in either placement and still be
 * accounted for.
 */
public interface AccountingSink {

    /** A sink that counts nothing: for tests, and for a placement with no accounting yet. */
    AccountingSink NONE = (tenant, reason) -> { };

    /**
     * One refused connection.
     *
     * @param tenant the tenant it was refused for
     * @param reason the {@link Decision.Reason#code} that refused it
     */
    void denied(String tenant, String reason);

    /**
     * One connection opened from a script. A node counts it as a metric; a worker tallies it locally, because the node
     * can no longer see the connections it does not make (D-173).
     *
     * @param tls whether the connection was a {@code tls} one rather than a plain socket
     */
    default void opened(String tenant, boolean tls) {
    }

    /**
     * Bytes moved by a script's connection.
     *
     * @param direction {@code in} or {@code out}
     */
    default void bytes(String tenant, String direction, long length) {
    }
}
