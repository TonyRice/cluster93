package automately.runner.protocol;

import automately.core.host.NodeHostApis;
import automately.core.host.SharedDataKvStore;
import automately.core.job.JobStatus;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.StateSink;
import automately.runner.protocol.client.WorkerClientConnection;
import automately.runner.protocol.client.WorkerDaemon;
import automately.runner.protocol.server.NodeWorkerChannel;
import automately.runner.protocol.transport.uds.UdsClientTransport;
import automately.runner.protocol.transport.uds.UdsServerTransport;
import automately.runtime.graaljs.GraalJsScriptRuntime;
import automately.runtime.javet.JavetScriptRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests standing up the full protocol architecture (UDS transport,
 * {@link NodeWorkerChannel}, {@link WorkerDaemon}, and {@link automately.runner.protocol.client.RemoteHostApis})
 * executing real JavaScript guest code on both {@link JavetScriptRuntime} (V8) and {@link GraalJsScriptRuntime}.
 */
class WorkerEngineIntegrationTest {

    private Vertx vertx;
    private String socketPath;
    private UdsServerTransport serverTransport;
    private UdsClientTransport clientTransport;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        socketPath = "/tmp/automately-engine-test-" + UUID.randomUUID() + ".sock";
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientTransport != null) {
            clientTransport.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (serverTransport != null) {
            serverTransport.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Files.deleteIfExists(Path.of(socketPath));
    }

    private boolean isUnixDomainSocketSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("mac");
    }

    @Test
    @DisplayName("Executes real JavaScript guest code on Javet (V8) engine over Netty UDS transport with RPC host APIs")
    void testJavetWorkerExecutionOverUds() throws Exception {
        if (!isUnixDomainSocketSupported()) {
            return;
        }

        serverTransport = new UdsServerTransport(vertx, socketPath);
        clientTransport = new UdsClientTransport(vertx);

        CompletableFuture<NodeWorkerChannel> serverChannelFuture = new CompletableFuture<>();
        serverTransport.listen(socket -> {
            NodeWorkerChannel channel = new NodeWorkerChannel("worker-javet-1", socket, socket);
            serverChannelFuture.complete(channel);
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(new File(socketPath).exists(), "UDS socket file should exist");

        var clientSocket = clientTransport.connect(socketPath).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        WorkerClientConnection clientConnection = new WorkerClientConnection(clientSocket, clientSocket);

        // Instantiate real JavetScriptRuntime (V8 isolate)
        JavetScriptRuntime javetRuntime = new JavetScriptRuntime(vertx, 64, 2);
        WorkerDaemon daemon = new WorkerDaemon(clientConnection, javetRuntime, "javet");

        // Start daemon (sends HELLO)
        daemon.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        NodeWorkerChannel serverChannel = serverChannelFuture.get(5, TimeUnit.SECONDS);
        var hello = serverChannel.awaitHello().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("javet", hello.engine());

        // Bind worker to tenant-alpha
        serverChannel.bind("tenant-alpha", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        // Wait briefly for daemon to process BIND frame asynchronously
        for (int i = 0; i < 50 && daemon.boundTenantId() == null; i++) {
            Thread.sleep(20);
        }
        assertEquals("tenant-alpha", daemon.boundTenantId());

        // Set up host APIs on the node side with real SharedDataKvStore
        SharedDataKvStore kvStore = new SharedDataKvStore(vertx);
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, kvStore, null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "tenant-alpha", "job-v8-1", false);

        // JavaScript code utilizing global kv to set and get state, plus console logging and checkpoints
        String script = """
                export default async (input, ctx) => {
                    const session = kv.namespace('session');
                    await session.set('user_token', input.token);
                    const fetchedToken = await session.get('user_token');
                    await ctx.saveCheckpoint('token-stored');
                    console.log('Stored and retrieved token successfully');
                    return { status: 'success', token: fetchedToken, receivedMultiplier: input.multiplier * 2 };
                };
                """;

        JsonObject input = new JsonObject().put("token", "secret-v8-xyz").put("multiplier", 21);
        Execution execution = new Execution("job-v8-1", "tenant-alpha", script, input,
                Duration.ofSeconds(10), JobContext.firstAttempt("job-v8-1", OrphanPolicy.FAIL));

        List<String> capturedLogs = new ArrayList<>();
        List<Object> capturedCheckpoints = new ArrayList<>();

        StateSink stateSink = new StateSink() {
            @Override
            public void saveCheckpoint(Object value) {
                capturedCheckpoints.add(value);
            }

            @Override
            public void completeStep(String name, Object result) {}
        };

        // Run execution over channel
        ExecutionResult result = serverChannel.run(1, execution, hostApis,
                (level, line) -> capturedLogs.add(line), stateSink)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        if (result.status() != JobStatus.SUCCEEDED) {
            System.err.println("Javet execution failed with error: " + result.error());
        }
        assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(result.output()).isInstanceOf(JsonObject.class);
        JsonObject outJson = (JsonObject) result.output();
        assertThat(outJson.getString("status")).isEqualTo("success");
        assertThat(outJson.getString("token")).isEqualTo("secret-v8-xyz");
        assertThat(outJson.getInteger("receivedMultiplier")).isEqualTo(42);

        // Verify state checkpoint reached node
        assertThat(capturedCheckpoints).contains("token-stored");

        // Verify value was actually committed to the node's real KvStore
        Object storedInKv = kvStore.get("tenant-alpha", "session", "user_token")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(storedInKv).isEqualTo("secret-v8-xyz");
    }

    @Test
    @DisplayName("Executes real JavaScript guest code on GraalJS engine over Netty UDS transport with RPC host APIs")
    void testGraalJsWorkerExecutionOverUds() throws Exception {
        if (!isUnixDomainSocketSupported()) {
            return;
        }

        serverTransport = new UdsServerTransport(vertx, socketPath);
        clientTransport = new UdsClientTransport(vertx);

        CompletableFuture<NodeWorkerChannel> serverChannelFuture = new CompletableFuture<>();
        serverTransport.listen(socket -> {
            NodeWorkerChannel channel = new NodeWorkerChannel("worker-graal-1", socket, socket);
            serverChannelFuture.complete(channel);
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(new File(socketPath).exists(), "UDS socket file should exist");

        var clientSocket = clientTransport.connect(socketPath).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        WorkerClientConnection clientConnection = new WorkerClientConnection(clientSocket, clientSocket);

        // Instantiate real GraalJsScriptRuntime (GraalVM polyglot context)
        GraalJsScriptRuntime graalRuntime = new GraalJsScriptRuntime(vertx, 64, 2);
        WorkerDaemon daemon = new WorkerDaemon(clientConnection, graalRuntime, "graaljs");

        // Start daemon (sends HELLO)
        daemon.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        NodeWorkerChannel serverChannel = serverChannelFuture.get(5, TimeUnit.SECONDS);
        var hello = serverChannel.awaitHello().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("graaljs", hello.engine());

        // Bind worker to tenant-beta
        serverChannel.bind("tenant-beta", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        // Wait briefly for daemon to process BIND frame asynchronously
        for (int i = 0; i < 50 && daemon.boundTenantId() == null; i++) {
            Thread.sleep(20);
        }
        assertEquals("tenant-beta", daemon.boundTenantId());

        // Set up host APIs on node side
        SharedDataKvStore kvStore = new SharedDataKvStore(vertx);
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, kvStore, null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "tenant-beta", "job-graal-1", false);

        // JavaScript code utilizing global kv, saveCheckpoint, and input transformations
        String script = """
                export default async (input, ctx) => {
                    const cache = kv.namespace('graal-cache');
                    await cache.set('greeting', input.message);
                    const val = await cache.get('greeting');
                    await ctx.saveCheckpoint('graal-checkpoint-1');
                    return { engine: 'graaljs', echo: val, sum: input.a + input.b };
                };
                """;

        JsonObject input = new JsonObject().put("message", "hello from graaljs").put("a", 100).put("b", 250);
        Execution execution = new Execution("job-graal-1", "tenant-beta", script, input,
                Duration.ofSeconds(10), JobContext.firstAttempt("job-graal-1", OrphanPolicy.FAIL));

        List<Object> capturedCheckpoints = new ArrayList<>();
        StateSink stateSink = new StateSink() {
            @Override
            public void saveCheckpoint(Object value) {
                capturedCheckpoints.add(value);
            }

            @Override
            public void completeStep(String name, Object result) {}
        };

        // Run execution over channel
        ExecutionResult result = serverChannel.run(1, execution, hostApis,
                (level, line) -> {}, stateSink)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(result.output()).isInstanceOf(JsonObject.class);
        JsonObject outJson = (JsonObject) result.output();
        assertThat(outJson.getString("engine")).isEqualTo("graaljs");
        assertThat(outJson.getString("echo")).isEqualTo("hello from graaljs");
        assertThat(outJson.getInteger("sum")).isEqualTo(350);

        // Verify checkpoint reached node
        assertThat(capturedCheckpoints).contains("graal-checkpoint-1");

        // Verify value was stored in node KvStore
        Object storedVal = kvStore.get("tenant-beta", "graal-cache", "greeting")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(storedVal).isEqualTo("hello from graaljs");
    }

    @Test
    @DisplayName("Executes full JavaScript capabilities (streaming files.create, kv, rpc authorization denial) over worker protocol")
    void testFullCapabilitiesOverWorkerProtocol() throws Exception {
        if (!isUnixDomainSocketSupported()) {
            return;
        }

        serverTransport = new UdsServerTransport(vertx, socketPath);
        clientTransport = new UdsClientTransport(vertx);

        CompletableFuture<NodeWorkerChannel> serverChannelFuture = new CompletableFuture<>();
        // Enforce authorization policy: allow kv and files, deny jobs.run
        automately.runner.protocol.server.NodeRpcAuthorizer authorizer = (tenantId, runId, method, params) -> {
            return !method.startsWith("jobs.");
        };

        serverTransport.listen(socket -> {
            NodeWorkerChannel channel = new NodeWorkerChannel("worker-full-1", socket, socket, new automately.runner.protocol.flow.CreditManager(), authorizer);
            serverChannelFuture.complete(channel);
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        var clientSocket = clientTransport.connect(socketPath).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        WorkerClientConnection clientConnection = new WorkerClientConnection(clientSocket, clientSocket);

        JavetScriptRuntime javetRuntime = new JavetScriptRuntime(vertx, 64, 2);
        WorkerDaemon daemon = new WorkerDaemon(clientConnection, javetRuntime, "javet");
        daemon.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        NodeWorkerChannel serverChannel = serverChannelFuture.get(5, TimeUnit.SECONDS);
        serverChannel.awaitHello().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        serverChannel.bind("tenant-full", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 50 && daemon.boundTenantId() == null; i++) {
            Thread.sleep(20);
        }
        assertEquals("tenant-full", daemon.boundTenantId());

        SharedDataKvStore kvStore = new SharedDataKvStore(vertx);
        automately.core.files.FileStore memoryFileStore = new automately.core.files.SharedDataFileStore(vertx);
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, kvStore, null, null, memoryFileStore,
                automately.core.files.StorageCapacity.UNLIMITED,
                automately.core.storage.TenantQuota.unlimited(),
                automately.core.files.Room.fixed(Long.MAX_VALUE),
                null, null, null, null, null, null);
        NodeHostApis hostApis = new NodeHostApis(node, "tenant-full", "job-full-1", false);

        // Guest code testing:
        // 1. files.write streaming upload
        // 2. kv get/set
        // 3. unauthorized call (jobs.get) which authorizer rejects
        String script = """
                export default async (input, ctx) => {
                    // 1. Streaming file write
                    const fileInfo = await files.write('reports/summary.txt', 'Hello distributed workers!\\nLine 2 of streamed data.', { contentType: 'text/plain' });

                    // 2. KV state
                    const store = kv.namespace('metrics');
                    await store.set('status', 'uploaded');
                    const status = await store.get('status');

                    // 3. Attempt forbidden call (jobs.run/jobs.get)
                    let forbiddenCaught = false;
                    try {
                        await jobs.get('non-existent');
                    } catch (e) {
                        forbiddenCaught = (e.code === 'FORBIDDEN');
                    }

                    return {
                        filePath: fileInfo.path,
                        fileSize: fileInfo.size,
                        kvStatus: status,
                        forbiddenCaught: forbiddenCaught
                    };
                };
                """;

        Execution execution = new Execution("job-full-1", "tenant-full", script, new JsonObject(),
                Duration.ofSeconds(10), JobContext.firstAttempt("job-full-1", OrphanPolicy.FAIL));

        ExecutionResult result = serverChannel.run(1, execution, hostApis,
                (lvl, line) -> System.out.println("LOG: " + line), new StateSink() {
                    @Override public void saveCheckpoint(Object value) {}
                    @Override public void completeStep(String name, Object res) {}
                }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        if (result.status() != JobStatus.SUCCEEDED) {
            System.err.println("Test failed with error: " + result.error());
        }
        assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
        JsonObject outJson = (JsonObject) result.output();
        assertThat(outJson.getString("filePath")).isEqualTo("reports/summary.txt");
        assertThat(outJson.getLong("fileSize")).isEqualTo(51L);
        assertThat(outJson.getString("kvStatus")).isEqualTo("uploaded");
        assertThat(outJson.getBoolean("forbiddenCaught")).isTrue();

        // Verify the file was committed in the node's FileStore
        var stat = memoryFileStore.stat("tenant-full", "reports/summary.txt")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(stat).isNotNull();
        assertThat(stat.size()).isEqualTo(51L);
    }
}
