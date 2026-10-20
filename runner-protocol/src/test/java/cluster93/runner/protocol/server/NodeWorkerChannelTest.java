package cluster93.runner.protocol.server;

import cluster93.core.host.EgressPolicy;
import cluster93.core.host.FetchClient;
import cluster93.core.host.NodeHostApis;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.RunningExecution;
import cluster93.core.runtime.ScriptRuntime;
import cluster93.core.runtime.api.HostApis;
import cluster93.runner.protocol.client.RemoteHostApis;
import cluster93.runner.protocol.client.WorkerClientConnection;
import cluster93.runner.protocol.client.WorkerDaemon;
import cluster93.runner.protocol.message.HelloMessage;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                Duration.ofMinutes(1), JobContext.firstAttempt("job-1", cluster93.core.job.OrphanPolicy.FAIL));

        List<String> logLines = new ArrayList<>();
        Future<ExecutionResult> runFuture = nodeChannel.run(1, execution, hostApis, (level, line) -> logLines.add(line), cluster93.core.runtime.StateSink.DISCARD);

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
    void aRefusedConnectReachesTheWorkerInTheNodesErrorShape() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-1", pipe.nodeToWorker, pipe.workerToNode);
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);

        // The worker's side as a daemon runs it, with a runtime that hands the test the host APIs the daemon built for
        // the run, so the connect can be made the way a script makes it.
        java.util.concurrent.atomic.AtomicReference<HostApis> workerHostApis = new java.util.concurrent.atomic.AtomicReference<>();
        ScriptRuntime mockRuntime = (execution, logs, state, host) -> {
            workerHostApis.set(host);
            return new RunningExecution() {
                @Override public Future<ExecutionResult> result() { return Promise.<ExecutionResult>promise().future(); }
                @Override public void stop(String reason, Duration grace) {}
                @Override public void cancel(String reason) {}
            };
        };
        new WorkerDaemon(workerConn, mockRuntime, "mock-engine").start();
        nodeChannel.awaitHello().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        nodeChannel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        // A node that decides this tenant's egress itself (the `node` placement, D-178) and refuses loopback. Its
        // refusal has to reach the worker as the same TypeError/egress_denied a gate on the worker's side produces —
        // not as a bare stream reset with the gate's words buried in it, which is how it read before D-179 and which
        // made the placement observable from a script (D-173).
        NodeHostApis hostApis = new NodeHostApis(new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx),
                new FetchClient(vertx, new EgressPolicy(false)), null), "tenant-a", "job-1", false);
        Execution execution = new Execution("job-1", "tenant-a", "export default () => 42;", new JsonObject(),
                Duration.ofMinutes(1), JobContext.firstAttempt("job-1", cluster93.core.job.OrphanPolicy.FAIL));
        // Not awaited: the runtime above holds the run open, which is what a run is — and the RUN frame is handled
        // synchronously over this in-memory pipe.
        nodeChannel.run(1, execution, hostApis, (level, line) -> { }, cluster93.core.runtime.StateSink.DISCARD);

        HostApis worker = workerHostApis.get();
        assertThat(worker).as("the node asked this worker to run the job").isNotNull();
        var refused = worker.sockets().connect(new JsonObject().put("host", "127.0.0.1").put("port", 9));

        assertThatThrownBy(() -> refused.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(HostException.class)
                .satisfies(failure -> {
                    HostException refusal = (HostException) failure;
                    assertThat(refusal.errorName()).as("the gate's error name, not the adapter's default")
                            .isEqualTo("TypeError");
                    assertThat(refusal.code()).as("and the code a script reads").isEqualTo("egress_denied");
                    assertThat(refusal.getMessage()).as("the gate's own words, unwrapped").contains("is not allowed")
                            .doesNotContain("Stream reset");
                });
    }

    @Test
    void aFetchBodyThatFailsMidStreamKeepsItsNameAndCode() throws Exception {
        // A peer that declares 64 bytes, sends 16, and closes. The fetch itself succeeds — its headers arrived — and
        // its body fails afterwards, when the response stream is already open. That is the one failure D-181 could not
        // carry: there is no second stream to report on, so before D-184 the worker read a bare 502 reset and the
        // script saw Error/host_error with "Stream reset" in it, while the same fetch made on the worker's side was
        // TypeError/network_error. The placement must not show through here either.
        java.net.ServerSocket peer = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        Thread serving = new Thread(() -> {
            try (java.net.Socket socket = peer.accept()) {
                var out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: 64\r\nConnection: close\r\n\r\n"
                        + "0123456789abcdef").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (java.io.IOException ignored) {
                // The test's own close, or the client's once the failure is all it needs.
            }
        }, "mid-body-peer");
        serving.setDaemon(true);
        serving.start();
        try {
            DuplexPipe pipe = new DuplexPipe();
            NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-1", pipe.nodeToWorker, pipe.workerToNode);
            WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);

            java.util.concurrent.atomic.AtomicReference<HostApis> workerHostApis =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ScriptRuntime mockRuntime = (execution, logs, state, host) -> {
                workerHostApis.set(host);
                return new RunningExecution() {
                    @Override public Future<ExecutionResult> result() { return Promise.<ExecutionResult>promise().future(); }
                    @Override public void stop(String reason, Duration grace) {}
                    @Override public void cancel(String reason) {}
                };
            };
            new WorkerDaemon(workerConn, mockRuntime, "mock-engine").start();
            nodeChannel.awaitHello().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            nodeChannel.bind("tenant-a", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            // The node makes this fetch (the `node` placement): loopback is allowed for the test's own peer.
            NodeHostApis hostApis = new NodeHostApis(new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx),
                    new FetchClient(vertx, new EgressPolicy(true)), null), "tenant-a", "job-1", false);
            Execution execution = new Execution("job-1", "tenant-a", "export default () => 42;", new JsonObject(),
                    Duration.ofMinutes(1), JobContext.firstAttempt("job-1", cluster93.core.job.OrphanPolicy.FAIL));
            nodeChannel.run(1, execution, hostApis, (level, line) -> { }, cluster93.core.runtime.StateSink.DISCARD);

            HostApis worker = workerHostApis.get();
            assertThat(worker).as("the node asked this worker to run the job").isNotNull();
            var response = worker.fetch().fetch(new JsonObject()
                            .put("url", "http://127.0.0.1:" + peer.getLocalPort() + "/"))
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(response.meta().getInteger("status")).as("the response itself arrived").isEqualTo(200);

            java.util.concurrent.CompletableFuture<Throwable> outcome = new java.util.concurrent.CompletableFuture<>();
            response.body().handler(chunk -> { }, () -> outcome.complete(null), outcome::complete);
            response.body().resume();

            Throwable failure = outcome.get(10, TimeUnit.SECONDS);
            assertThat(failure).as("the body was cut short, so it reported a failure").isNotNull();
            assertThat(failure).isInstanceOf(HostException.class);
            assertThat(((HostException) failure).errorName()).as("the name a script reads").isEqualTo("TypeError");
            assertThat(((HostException) failure).code()).as("and the code").isEqualTo("network_error");
            assertThat(failure.getMessage()).as("the failure's own words, not the reset's")
                    .contains("fetch failed").doesNotContain("Stream reset");
        } finally {
            peer.close();
        }
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
                Duration.ofMinutes(1), JobContext.firstAttempt("job-1", cluster93.core.job.OrphanPolicy.FAIL));
        nodeChannel.run(1, execution, hostApis, (l, line) -> {}, cluster93.core.runtime.StateSink.DISCARD);

        // Try calling an illegal method "os.exec"
        cluster93.runner.protocol.message.CallMessage illegalCall =
                new cluster93.runner.protocol.message.CallMessage(1, "job-1", "os.exec", new JsonObject().put("cmd", "rm -rf /"));

        Promise<Void> errorPromise = Promise.promise();
        workerConn.call(illegalCall).onFailure(err -> errorPromise.complete());

        errorPromise.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void reportsWhatAWorkerRefusedOnItsOwnToTheNodeSink() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        List<String> refusals = new ArrayList<>();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-7", pipe.nodeToWorker, pipe.workerToNode)
                .egressReports((workerId, tenant, reason, count) ->
                        refusals.add(workerId + " " + tenant + " " + reason + " x" + count));
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);
        nodeChannel.bind("acme", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        // A worker that decided egress itself says what it turned away (D-176): the node made none of these connections.
        workerConn.send(new cluster93.runner.protocol.message.AccountMessage("acme",
                java.util.Map.of("connect_rate", 3L, "not_allowed", 1L)).toFrame());

        assertThat(refusals).containsExactlyInAnyOrder("worker-7 acme connect_rate x3", "worker-7 acme not_allowed x1");
    }

    @Test
    void ignoresAnEgressReportForATenantTheWorkerIsNotBoundTo() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        List<String> refusals = new ArrayList<>();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-7", pipe.nodeToWorker, pipe.workerToNode)
                .egressReports((workerId, tenant, reason, count) -> refusals.add(tenant + " " + reason));
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);
        nodeChannel.bind("acme", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        // A re-bound worker (D-172) could still be finishing a run under the tenant it just left. Its refusals must not
        // be counted against whoever holds it now.
        workerConn.send(new cluster93.runner.protocol.message.AccountMessage("globex",
                java.util.Map.of("connect_rate", 3L)).toFrame());

        assertThat(refusals).isEmpty();
    }

    @Test
    void dropsReportsWhenNoSinkIsSet() throws Exception {
        DuplexPipe pipe = new DuplexPipe();
        NodeWorkerChannel nodeChannel = new NodeWorkerChannel("worker-7", pipe.nodeToWorker, pipe.workerToNode);
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);
        nodeChannel.bind("acme", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        // The channel's default: a worker's report is data off a connection, and a node with nothing listening for it
        // must be unbothered rather than die on it.
        workerConn.send(new cluster93.runner.protocol.message.AccountMessage("acme",
                java.util.Map.of("connect_rate", 1L)).toFrame());

        assertThat(nodeChannel.boundTenantId()).isEqualTo("acme");
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
                Duration.ofMinutes(1), JobContext.firstAttempt("job-daemon-1", cluster93.core.job.OrphanPolicy.FAIL));

        NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "tenant-a", "job-daemon-1", false);

        ExecutionResult result = nodeChannel.run(1, execution, hostApis,
                (level, line) -> logsCaptured.add(line),
                new cluster93.core.runtime.StateSink() {
                    @Override public void saveCheckpoint(Object val) { stateCaptured.add(val); }
                    @Override public void completeStep(String name, Object res) {}
                }).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(cluster93.core.job.JobStatus.SUCCEEDED);
        assertThat(result.output()).isInstanceOf(JsonObject.class);
        assertThat(((JsonObject) result.output()).getInteger("answer")).isEqualTo(42);
        assertThat(logsCaptured).contains("Starting script execution");
        assertThat(stateCaptured).contains("step-1-complete");
    }
}
