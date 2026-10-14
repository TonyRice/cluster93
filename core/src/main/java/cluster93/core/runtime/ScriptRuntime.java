package cluster93.core.runtime;

/**
 * Executes one attempt of a job. The successor to the old {@code ExecutionContextFactory}: one
 * implementation per engine, chosen at bootstrap.
 */
public interface ScriptRuntime {

    RunningExecution execute(Execution execution, LogSink logs, StateSink state, cluster93.core.runtime.api.HostApis host);

    /** Runs without host APIs. */
    default RunningExecution execute(Execution execution, LogSink logs, StateSink state) {
        return execute(execution, logs, state, cluster93.core.runtime.api.HostApis.NONE);
    }
}
