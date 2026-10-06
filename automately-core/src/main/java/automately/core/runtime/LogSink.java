package automately.core.runtime;

/** Receives console output from a running script. May be called from any thread. */
@FunctionalInterface
public interface LogSink {

    void log(String level, String line);
}
