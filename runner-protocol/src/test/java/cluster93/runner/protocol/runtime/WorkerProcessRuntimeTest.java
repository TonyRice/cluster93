package cluster93.runner.protocol.runtime;

import cluster93.core.job.JobStatus;
import cluster93.core.runtime.ExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run whose worker died should tell the tenant <em>why</em>, not just that the pipe ended (D-159).
 */
class WorkerProcessRuntimeTest {

    @Test
    @DisplayName("A run that failed because its worker died names the exit cause, not the EOF (D-159)")
    void aFailedRunIsAttributedToItsDeadWorker() {
        FakeProcess dead = new FakeProcess(137);
        dead.destroy();
        ExecutionResult result = WorkerProcessRuntime.attributeWorkerDeath(dead,
                ExecutionResult.failed("Worker process died: Worker process connection closed unexpectedly (EOF)"));

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error()).isEqualTo("Worker killed: out of memory or force-killed (SIGKILL)");
    }

    @Test
    @DisplayName("A script that threw on a live worker keeps its own error (D-159)")
    void aScriptFailureIsNotAttributedToTheWorker() {
        ExecutionResult scriptFailure = ExecutionResult.failed("TypeError: nope");

        // The worker is still running, so there is no exit cause — an ordinary failure must survive untouched.
        assertThat(WorkerProcessRuntime.attributeWorkerDeath(new FakeProcess(0), scriptFailure)).isSameAs(scriptFailure);
    }

    @Test
    @DisplayName("A successful run is never re-labelled, even if the worker then exits (D-159)")
    void aSucceededRunIsNeverRelabelled() {
        FakeProcess dead = new FakeProcess(137);
        dead.destroy();
        ExecutionResult ok = ExecutionResult.succeeded("done");

        assertThat(WorkerProcessRuntime.attributeWorkerDeath(dead, ok)).isSameAs(ok);
    }

    /** A process that is alive until {@link #destroy()}ed, then reports its exit code — like a real one. */
    private static final class FakeProcess extends Process {
        private final int exitCode;
        private volatile boolean alive = true;

        FakeProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exitCode; }
        @Override public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process has not exited");
            }
            return exitCode;
        }
        @Override public void destroy() { alive = false; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return 1L; }
    }
}
