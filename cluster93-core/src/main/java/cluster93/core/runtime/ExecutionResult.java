package cluster93.core.runtime;

import cluster93.core.job.JobStatus;

/**
 * How a script run ended. A failing, timed-out or cancelled script is a normal result, not a failed
 * future; a failed future means the runtime itself broke.
 */
public record ExecutionResult(JobStatus status, Object output, String error) {

    public static ExecutionResult succeeded(Object output) {
        return new ExecutionResult(JobStatus.SUCCEEDED, output, null);
    }

    public static ExecutionResult failed(String error) {
        return new ExecutionResult(JobStatus.FAILED, null, error);
    }

    public static ExecutionResult timedOut(String error) {
        return new ExecutionResult(JobStatus.TIMED_OUT, null, error);
    }

    public static ExecutionResult cancelled(String reason) {
        return new ExecutionResult(JobStatus.CANCELLED, null, reason);
    }

    /** The same outcome, recorded as a cancellation: what a run that was asked to stop ends as. */
    public ExecutionResult asCancelled(String reason) {
        return new ExecutionResult(JobStatus.CANCELLED, output, error == null ? reason : reason + "; " + error);
    }
}
