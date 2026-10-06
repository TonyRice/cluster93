package automately.core.runtime;

/**
 * Executes one attempt of a job. The successor to the old {@code ExecutionContextFactory}: one
 * implementation per engine, chosen at bootstrap.
 */
public interface ScriptRuntime {

    RunningExecution execute(Execution execution, LogSink logs, StateSink state);
}
