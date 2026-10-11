package automately.runner.protocol.server;

import automately.runner.protocol.client.WorkerClientConnection;
import automately.runner.protocol.message.CallMessage;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeRpcAuthorizerTest {

    static class DuplexPipe {
        final FakeStream nodeToWorker = new FakeStream();
        final FakeStream workerToNode = new FakeStream();

        static class FakeStream implements ReadStream<Buffer>, WriteStream<Buffer> {
            private Handler<Buffer> handler;

            @Override public FakeStream handler(Handler<Buffer> handler) { this.handler = handler; return this; }
            @Override public FakeStream exceptionHandler(Handler<Throwable> h) { return this; }
            @Override public io.vertx.core.Future<Void> write(Buffer data) {
                if (handler != null) handler.handle(data);
                return io.vertx.core.Future.succeededFuture();
            }
            @Override public FakeStream pause() { return this; }
            @Override public FakeStream resume() { return this; }
            @Override public FakeStream fetch(long amount) { return this; }
            @Override public FakeStream endHandler(Handler<Void> endHandler) { return this; }
            @Override public io.vertx.core.Future<Void> end() { return io.vertx.core.Future.succeededFuture(); }
            @Override public FakeStream setWriteQueueMaxSize(int maxSize) { return this; }
            @Override public boolean writeQueueFull() { return false; }
            @Override public FakeStream drainHandler(Handler<Void> handler) { return this; }
        }
    }

    @Test
    @DisplayName("Authorizer blocks forbidden methods and returns FORBIDDEN error code without dropping connection")
    void authorizerBlocksForbiddenMethods() throws Exception {
        DuplexPipe pipe = new DuplexPipe();

        // Authorizer allowing kv.* but forbidding secrets.*
        NodeRpcAuthorizer authorizer = (tenant, run, method, params) -> !method.startsWith("secrets.");

        NodeWorkerChannel channel = new NodeWorkerChannel("worker-1", pipe.nodeToWorker, pipe.workerToNode,
                new automately.runner.protocol.flow.CreditManager(), authorizer);
        WorkerClientConnection workerConn = new WorkerClientConnection(pipe.workerToNode, pipe.nodeToWorker);

        channel.bind("tenant-alpha", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        // Dummy execution slot
        automately.core.runtime.Execution execution = new automately.core.runtime.Execution(
                "job-1", "tenant-alpha", "export default () => {}", new JsonObject(),
                java.time.Duration.ofMinutes(1), automately.core.runtime.JobContext.firstAttempt("job-1", automately.core.job.OrphanPolicy.FAIL)
        );

        automately.core.runtime.api.HostApis mockHost = new automately.core.runtime.api.HostApis() {
            @Override public automately.core.runtime.api.KvApi kv() {
                return new automately.core.runtime.api.KvApi() {
                    @Override public io.vertx.core.Future<Object> get(String ns, String k) { return io.vertx.core.Future.succeededFuture("ok-val"); }
                    @Override public io.vertx.core.Future<Void> set(String ns, String k, Object v, Long ttl) { return io.vertx.core.Future.succeededFuture(); }
                    @Override public io.vertx.core.Future<Boolean> delete(String ns, String k) { return io.vertx.core.Future.succeededFuture(true); }
                    @Override public io.vertx.core.Future<JsonObject> list(String ns, String p, int l, String c) { return io.vertx.core.Future.succeededFuture(new JsonObject()); }
                    @Override public io.vertx.core.Future<Long> increment(String ns, String k, long b) { return io.vertx.core.Future.succeededFuture(1L); }
                };
            }
            @Override public automately.core.runtime.api.SecretsApi secrets() {
                return name -> io.vertx.core.Future.succeededFuture("super-secret");
            }
            @Override public automately.core.runtime.api.BusApi bus() { return null; }
            @Override public automately.core.runtime.api.QueueApi queue() { return null; }
            @Override public automately.core.runtime.api.JobsApi jobs() { return null; }
            @Override public automately.core.runtime.api.FilesApi files() { return null; }
            @Override public automately.core.runtime.api.FsApi fs() { return null; }
            @Override public automately.core.runtime.api.FetchApi fetch() { return null; }
            @Override public automately.core.runtime.api.SocketApi sockets() { return null; }
            @Override public automately.core.runtime.api.DnsApi dns() { return null; }
            @Override public automately.core.runtime.api.HttpApi http() { return null; }
            @Override public automately.core.runtime.api.CryptoApi crypto() { return null; }
            @Override public automately.core.runtime.api.ZlibApi zlib() { return null; }
            @Override public io.vertx.core.Future<Void> close() { return io.vertx.core.Future.succeededFuture(); }
        };

        channel.run(1, execution, mockHost, (l, line) -> {}, automately.core.runtime.StateSink.DISCARD);

        // 1. Allowed call: kv.get succeeds
        CallMessage allowedCall = new CallMessage(1, "job-1", "kv.get", new JsonObject().put("namespace", "ns").put("key", "k"));
        var allowedResult = workerConn.call(allowedCall).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(allowedResult.value()).isEqualTo("ok-val");

        // 2. Forbidden call: secrets.get fails with FORBIDDEN
        CallMessage forbiddenCall = new CallMessage(2, "job-1", "secrets.get", new JsonObject().put("name", "db-pass"));
        assertThatThrownBy(() -> workerConn.call(forbiddenCall).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(automately.core.runtime.HostException.class)
                .cause()
                .extracting(e -> ((automately.core.runtime.HostException) e).code())
                .isEqualTo("FORBIDDEN");
    }
}
