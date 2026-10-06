package automately.core.runtime;

import io.vertx.core.Future;

/** A script that has been started. */
public interface RunningExecution {

    Future<ExecutionResult> result();

    /** Stops the script as soon as possible; {@link #result()} then completes as failed with the reason. */
    void cancel(String reason);
}
