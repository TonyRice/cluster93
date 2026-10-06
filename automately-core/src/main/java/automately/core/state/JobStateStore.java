package automately.core.state;

import io.vertx.core.Future;

/**
 * Checkpoints and step results, stored per attempt (D-010). A resuming attempt starts by copying the
 * latest earlier attempt's state into its own key. A stale attempt can keep writing to its own key,
 * but nothing reads it any more, so no locking is needed.
 */
public interface JobStateStore {

    /** Largest single checkpoint or step result, in bytes of encoded JSON. */
    int MAX_VALUE_BYTES = 64 * 1024;

    /** Largest total state per job and attempt, in bytes of encoded JSON. */
    int MAX_TOTAL_BYTES = 1024 * 1024;

    /** The attempt's state, or {@link JobState#EMPTY}. */
    Future<JobState> load(String jobId, int attempt);

    /**
     * Seeds {@code attempt}'s state from the most recent earlier attempt that recorded any (an attempt that
     * crashed before it got to copy is skipped), and returns what was copied.
     */
    Future<JobState> resumeFrom(String jobId, int attempt);

    /** Fails with {@link StateException} ({@code LIMIT}) when over a size limit. */
    Future<JobState> saveCheckpoint(String jobId, int attempt, Object value);

    /** Fails with {@link StateException} ({@code LIMIT}) when over a size limit. */
    Future<JobState> completeStep(String jobId, int attempt, String name, Object result, long now);
}
