package automately.core.runtime;

import io.vertx.core.Future;

import java.time.Duration;

/** A script that has been started. */
public interface RunningExecution {

    Future<ExecutionResult> result();

    /**
     * Asks the script to stop: {@code ctx.stopRequested} resolves and the script has {@code grace} to
     * return before it's terminated. The run ends as {@code CANCELLED} either way.
     */
    void stop(String reason, Duration grace);

    /** Terminates the script now; {@link #result()} completes as {@code CANCELLED} with the reason. */
    void cancel(String reason);
}
