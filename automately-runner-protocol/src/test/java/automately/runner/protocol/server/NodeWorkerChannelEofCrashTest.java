package automately.runner.protocol.server;

import automately.core.host.NodeHostApis;
import automately.core.host.SharedDataKvStore;
import automately.core.job.JobStatus;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.StateSink;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker whose pipe closes unexpectedly (EOF) must fail its in-flight run at once, instead of leaving the node
 * waiting on a promise that never completes (D-128).
 */
class NodeWorkerChannelEofCrashTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("An EOF on the worker pipe fails the active run with ExecutionResult.failed")
    void eofFailsTheActiveRun() throws Exception {
        PipeStream toWorker = new PipeStream();
        PipeStream fromWorker = new PipeStream();
        NodeWorkerChannel channel = new NodeWorkerChannel("worker-eof", toWorker, fromWorker);

        channel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        Execution execution = new Execution("job-eof", "tenant-a", "export default () => 1;", new JsonObject(),
                Duration.ofMinutes(1), JobContext.firstAttempt("job-eof", OrphanPolicy.FAIL));
        NodeHostApis hostApis = new NodeHostApis(new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), null, null),
                "tenant-a", "job-eof", false);

        var run = channel.run(1, execution, hostApis, (level, line) -> { }, StateSink.DISCARD);

        // The worker dies without ever answering the RUN.
        fromWorker.simulateEnd();

        ExecutionResult result = run.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error()).contains("Worker process died");
    }

    @Test
    @DisplayName("A transport error fails the active run too")
    void transportErrorFailsTheActiveRun() throws Exception {
        PipeStream toWorker = new PipeStream();
        PipeStream fromWorker = new PipeStream();
        NodeWorkerChannel channel = new NodeWorkerChannel("worker-err", toWorker, fromWorker);
        channel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        Execution execution = new Execution("job-err", "tenant-a", "export default () => 1;", new JsonObject(),
                Duration.ofMinutes(1), JobContext.firstAttempt("job-err", OrphanPolicy.FAIL));
        NodeHostApis hostApis = new NodeHostApis(new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), null, null),
                "tenant-a", "job-err", false);

        var run = channel.run(1, execution, hostApis, (level, line) -> { }, StateSink.DISCARD);
        fromWorker.simulateError(new java.io.IOException("broken pipe"));

        ExecutionResult result = run.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error()).contains("Worker process died");
    }
}
