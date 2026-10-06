package automately.core.job;

import java.util.Locale;

/** What happens to a job when the node running it disappears. See docs/scripting/job-context.md. */
public enum OrphanPolicy {
    /** End as {@link JobStatus#ORPHANED}; never re-run. The default, because re-running can repeat side effects. */
    FAIL,
    /** Start a new attempt from the beginning. */
    RETRY,
    /** Start a new attempt that receives the last checkpoint and the completed steps. */
    RESUME;

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int MAX_ATTEMPTS_LIMIT = 10;

    public String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the API form ({@code "fail"}, {@code "retry"}, {@code "resume"}); returns null for anything else. */
    public static OrphanPolicy fromApi(String value) {
        for (OrphanPolicy policy : values()) {
            if (policy.apiName().equals(value)) {
                return policy;
            }
        }
        return null;
    }
}
