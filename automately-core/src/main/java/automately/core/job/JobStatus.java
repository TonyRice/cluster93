package automately.core.job;

public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** The node running the job disappeared and the job was not continued (see {@link OrphanPolicy}). */
    ORPHANED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT || this == ORPHANED;
    }
}
