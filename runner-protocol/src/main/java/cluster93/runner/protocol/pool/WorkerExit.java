package cluster93.runner.protocol.pool;

/**
 * Turns a dead worker's exit status into the words a tenant should see (D-159).
 *
 * <p>When a worker dies mid-run the channel can only report that the pipe ended — "connection closed unexpectedly
 * (EOF)" — which tells a tenant nothing about <em>why</em>. The process exit code does: a worker the kernel killed
 * under its cgroup memory limit (137, SIGKILL), one that segfaulted (139, SIGSEGV), or one that simply returned a
 * code. This names that cause once, so the pool's log and the job's failure agree.
 */
public final class WorkerExit {

    private WorkerExit() {
    }

    /**
     * A tenant-facing phrase for a worker that has died, or {@code null} while its process is still running or when
     * it exited cleanly — a worker we asked to stop is not a crash and must not be reported as one.
     *
     * @param process the worker's process, or null
     * @return the phrase, or null when there is nothing to attribute
     */
    public static String describe(Process process) {
        if (process == null) {
            return null;
        }
        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return null;
        }
        return describe(exitCode);
    }

    /**
     * The phrase for an exit code: {@code null} for a clean exit (0), which is not a crash.
     *
     * <p>137 (128 + SIGKILL) is what the kernel's OOM killer sends a worker that exceeds its cgroup limit, and also
     * what a plain {@code kill -9} looks like; the two cannot be told apart from the code alone, so the phrase names
     * both rather than guessing.
     */
    public static String describe(int exitCode) {
        return switch (exitCode) {
            case 0 -> null;
            case 137 -> "killed: out of memory or force-killed (SIGKILL)";
            case 139 -> "crashed: segmentation fault (SIGSEGV)";
            default -> "exited with code " + exitCode;
        };
    }

    /**
     * The reason to show a tenant for a run that produced no result (D-159): when the worker process is gone its exit
     * status says why, and only when it is not is the run's own cause the best answer.
     *
     * @param process the worker's process, or null
     * @param cause   the failure the run reported, or null
     * @return a tenant-facing failure reason
     */
    public static String runFailure(Process process, Throwable cause) {
        String exit = describe(process);
        if (exit != null) {
            return "Worker " + exit;
        }
        String detail = (cause == null || cause.getMessage() == null) ? "worker error" : cause.getMessage();
        return "Worker error: " + detail;
    }
}
