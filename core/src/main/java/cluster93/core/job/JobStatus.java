package cluster93.core.job;

public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** The node running the job disappeared and the job was not continued (see {@link OrphanPolicy}). */
    ORPHANED,
    /** Someone cancelled or stopped the job. Never counted as a failure. */
    CANCELLED;

    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }
}
