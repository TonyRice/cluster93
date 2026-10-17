package cluster93.core.host.egress;

/**
 * Where a decision's outcomes are counted (D-173). On a node that is its metrics; in a worker it is the budget the
 * node allocated and the worker enforces (so the same gate can run in either placement and still be accounted for).
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
}
