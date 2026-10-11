package automately.runner.protocol.server;

import automately.core.host.NodeHostApis;
import automately.core.host.SharedDataKvStore;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.ScriptRuntime;
import automately.core.runtime.api.HostApis;
import automately.runner.protocol.client.RemoteHostApis;
import automately.runner.protocol.client.WorkerClientConnection;
import automately.runner.protocol.client.WorkerDaemon;
import automately.runner.protocol.message.HelloMessage;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NodeWorkerChannelTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Simple in-memory duplex stream pair for testing. */
    static class DuplexPipe {
        final FakeStream nodeToWorker = new FakeStream();
        final FakeStream workerToNode = new FakeStream();

        static class FakeStream implements ReadStream<Buffer>, WriteStream<Buffer> {
            private Handler<Buffer> handler;
            private Handler<Throwable> exceptionHandler;

            @Override
            public FakeStream handler(Handler<Buffer> handler) {
                this.handler = handler;
                return this;
            }

            @Override
            public FakeStream exceptionHandler(Handler<Throwable> handler) {
                this.exceptionHandler = handler;
                return this;
            }

            @Override
            public Future<Void> write(Buffer data) {
                if (handler != null) {
                    handler.handle(data);
                }
                return Future.succeededFuture();
            }

            @Override public FakeStream pause() { return this; }
            @Override public FakeStream resume() { return this; }
            @Override public FakeStream fetch(long amount) { return this; }
            @Override public FakeStream endHandler(Handler<Void> endHandler) { return this; }
            @Override public Future<Void> end() { return Future.succeededFuture(); }
            @Override public FakeStream setWriteQueueMaxSize(int maxSize) { return this; }
            @Override public boolean writeQueueFull() { return false; }
            @Override public FakeStream drainHandler(Handler<Void> handler) { return this; }
        }
    }

    @Test
    void completesHelloWelcomeHandshake() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-1", pipe.nodeToWorker, pipe.workerToNode);
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);

        Promise<JsonObject> welcomeReceived = Promise.promise();
        workerConn.setFrameHandler(frame -> {
            if (frame.type().name().equals("WELCOME")) {
                welcomeReceived.complete(frame.json());
            }
        });

        HelloMessage hello = new HelloMessage("javet", "2.0.0", 12345L);
        workerConn.send(hello.toFrame());

        HelloMessage receivedHello = nodeChannel.awaitHello().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(receivedHello.engine()).isEqualTo("javet");
        assertThat(receivedHello.pid()).isEqualTo(12345L);

        JsonObject welcome = welcomeReceived.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(welcome.getString("workerId")).isEqualTo("worker-1");
    }

    @Test
    void dispatchesRpcCallsFromWorkerToNodeHostApis() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-1", pipe.nodeToWorker, pipe.workerToNode);
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);

        // Bind worker to tenant-a
        nodeChannel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        // Set up real node host APIs with SharedDataKvStore
        SharedDataKvStore kv = new SharedDataKvStore(vertx);
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, kv, null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "tenant-a", "job-1", false);

        // Start execution slot on nodeChannel
        Execution execution = new Execution("job-1", "tenant-a", "export default () => 42;", new JsonObject(),
                Duration.ofMinutes(1), JobContext.firstAttempt("job-1", automately.core.job.OrphanPolicy.FAIL));

        List<String> logLines = new ArrayList<>();
        Future<ExecutionResult> runFuture = nodeChannel.run(1, execution, hostApis, (level, line) -> logLines.add(line), automately.core.runtime.StateSink.DISCARD);

        // Now from the worker side, use RemoteHostApis
        RemoteHostApis remoteHost = new RemoteHostApis(workerConn, "job-1");

        // 1. KV set
        remoteHost.kv().set("cache", "greeting", "hello world", null).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        // 2. KV get
        Object got = remoteHost.kv().get("cache", "greeting").toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(got).isEqualTo("hello world");

        // 3. Pure local crypto execution on worker (0 RPCs, fast)
        byte[] hash = remoteHost.crypto().digest("sha256", "test".getBytes());
        assertThat(hash).isNotNull().hasSize(32);
    }

    @Test
    void rejectsUnauthorizedRpcMethod() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-1", pipe.nodeToWorker, pipe.workerToNode);
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);

        nodeChannel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "tenant-a", "job-1", false);

        Execution execution = new Execution("job-1", "tenant-a", "export default () => 42;", new JsonObject(),
                Duration.ofMinutes(1), JobContext.firstAttempt("job-1", automately.core.job.OrphanPolicy.FAIL));
        nodeChannel.run(1, execution, hostApis, (l, line) -> {}, automately.core.runtime.StateSink.DISCARD);

        // Try calling an illegal method "os.exec"
        automately.runner.protocol.message.CallMessage illegalCall =
                new automately.runner.protocol.message.CallMessage(1, "job-1", "os.exec", new JsonObject().put("cmd", "rm -rf /"));

        Promise<Void> errorPromise = Promise.promise();
        workerConn.call(illegalCall).onFailure(err -> errorPromise.complete());

        errorPromise.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void runsFullDaemonExecutionRoundtrip() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-1", pipe.nodeToWorker, pipe.workerToNode);
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);

        // Mock runtime on worker side
        ScriptRuntime mockRuntime = (execution, logs, state, host) -> {
            logs.log("INFO", "Starting script execution");
            state.saveCheckpoint("step-1-complete");
            return new RunningExecution() {
                @Override
                public Future<ExecutionResult> result() {
                    return Future.succeededFuture(ExecutionResult.succeeded(new JsonObject().put("answer", 42)));
                }

                @Override public void stop(String reason, Duration grace) {}
                @Override public void cancel(String reason) {}
            };
        };

        WorkerDaemon daemon = new WorkerDaemon(workerConn, mockRuntime, "mock-engine");
        daemon.start();

        // Node receives HELLO and binds worker
        nodeChannel.awaitHello().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        nodeChannel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<String> logsCaptured = new ArrayList<>();
        List<Object> stateCaptured = new ArrayList<>();
        Execution execution = new Execution("job-daemon-1", "tenant-a", "export default () => 42;", new JsonObject(),
                Duration.ofMinutes(1), JobContext.firstAttempt("job-daemon-1", automately.core.job.OrphanPolicy.FAIL));

        NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "tenant-a", "job-daemon-1", false);

        ExecutionResult result = nodeChannel.run(1, execution, hostApis,
                (level, line) -> logsCaptured.add(line),
                new automately.core.runtime.StateSink() {
                    @Override public void saveCheckpoint(Object val) { stateCaptured.add(val); }
                    @Override public void completeStep(String name, Object res) {}
                }).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(automately.core.job.JobStatus.SUCCEEDED);
        assertThat(result.output()).isInstanceOf(JsonObject.class);
        assertThat(((JsonObject) result.output()).getInteger("answer")).isEqualTo(42);
        assertThat(logsCaptured).contains("Starting script execution");
        assertThat(stateCaptured).contains("step-1-complete");
    }
}
