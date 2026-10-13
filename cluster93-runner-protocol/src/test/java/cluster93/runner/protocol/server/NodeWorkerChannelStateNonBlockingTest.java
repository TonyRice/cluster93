package cluster93.runner.protocol.server;

import cluster93.core.host.NodeHostApis;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.StateSink;
import cluster93.runner.protocol.flow.CreditManager;
import cluster93.runner.protocol.frame.FrameCodec;
import cluster93.runner.protocol.message.StateMessage;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State commits block on the durable store; the channel must run them off the Vert.x event loop so a slow write can't
 * stall the worker's frames (D-128).
 */
class NodeWorkerChannelStateNonBlockingTest {

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
    @DisplayName("A STATE frame's commit runs off the event loop")
    void stateCommitRunsOffTheEventLoop() throws Exception {
        PipeStream toWorker = new PipeStream();
        PipeStream fromWorker = new PipeStream();
        NodeWorkerChannel channel = new NodeWorkerChannel("worker-state", toWorker, fromWorker, new CreditManager(),
                NodeRpcAuthorizer.ALLOW_ALL, vertx);
        channel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        String jobId = "job-state";
        Execution execution = new Execution(jobId, "tenant-a", "export default () => 1;", new JsonObject(),
                Duration.ofMinutes(1), JobContext.firstAttempt(jobId, OrphanPolicy.FAIL));
        NodeHostApis hostApis = new NodeHostApis(new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), null, null),
                "tenant-a", jobId, false);

        Promise<Boolean> onEventLoop = Promise.promise();
        StateSink sink = new StateSink() {
            @Override
            public void saveCheckpoint(Object value) {
                onEventLoop.tryComplete(Context.isOnEventLoopThread());
            }

            @Override
            public void completeStep(String name, Object result) {
                onEventLoop.tryComplete(Context.isOnEventLoopThread());
            }
        };
        channel.run(1, execution, hostApis, (level, line) -> { }, sink);

        Buffer frame = FrameCodec.encode(StateMessage.checkpoint(1, jobId, "checkpoint-value").toFrame());
        AtomicReference<Throwable> deliveryError = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        vertx.runOnContext(v -> {
            try {
                fromWorker.deliver(frame);
            } catch (Throwable t) {
                deliveryError.set(t);
            } finally {
                delivered.countDown();
            }
        });
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(deliveryError.get()).isNull();

        Boolean ranOnEventLoop = onEventLoop.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(ranOnEventLoop).as("the commit must run on a worker thread, not the event loop").isFalse();
    }
}
