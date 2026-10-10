package automately.runtime.js;

import automately.core.runtime.ScriptRuntime;

import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.NodeHostApis;
import automately.core.host.ServeRegistry;
import automately.core.host.SharedDataKvStore;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.StateSink;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The global WebSocket and the ws-style upgrade on node:http (D-125), in every build, against a local WebSocket server
 * with compression. RealNpmPackagesTest's wsclient and wsglobal fixtures check the same against real Node.
 */
class NodeWebSocketTest {

    private static Vertx vertx;
    private static ScriptRuntime runtime;
    private static HttpServer server;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        runtime = EngineUnderTest.create(vertx, 64, 2);
        server = vertx.createHttpServer(new HttpServerOptions().setPerMessageWebSocketCompressionSupported(true)
                        .setWebSocketSubProtocols(List.of("chat")))
                .webSocketHandler(ws -> {
                    ws.textMessageHandler(text -> {
                        if (text.equals("close-me")) {
                            ws.close((short) 4001, "bye");
                        } else {
                            ws.writeTextMessage("echo " + text);
                        }
                    });
                    ws.binaryMessageHandler(ws::writeBinaryMessage);
                })
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void theGlobalWebSocketTalksAndClosesAsNodesDoes() throws Exception {
        JsonArray out = run("""
                export default async () => {
                  const events = [];
                  const ws = new WebSocket('ws://127.0.0.1:%d/room', ['chat']);
                  ws.binaryType = 'arraybuffer';
                  const done = new Promise((resolve) => {
                    ws.onopen = () => { events.push(['open', ws.protocol, ws.extensions]); ws.send('hi'); };
                    ws.onmessage = (e) => {
                      events.push(['message', typeof e.data === 'string' ? e.data : new Uint8Array(e.data).join(',')]);
                      if (e.data === 'echo hi') ws.send(new Uint8Array([1, 2, 3]));
                      else ws.send('close-me');
                    };
                    ws.onerror = (e) => events.push(['error', e.message]);
                    ws.onclose = (e) => { events.push(['close', e.code, e.reason, e.wasClean]); resolve(); };
                  });
                  await done;
                  return events;
                };
                """.formatted(server.actualPort()));

        assertThat(out.encode()).isEqualTo("[[\"open\",\"chat\",\"permessage-deflate\"],[\"message\",\"echo hi\"],"
                + "[\"message\",\"1,2,3\"],[\"close\",4001,\"bye\",true]]");
    }

    private static JsonArray run(String script) throws Exception {
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(true)),
                new ServeRegistry());
        Execution execution = new Execution("job-1", "tenant-a", script, new JsonObject(), Duration.ofSeconds(20),
                JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        List<String> logs = new CopyOnWriteArrayList<>();
        ExecutionResult result = runtime.execute(execution, (level, line) -> logs.add(level + " " + line), StateSink.DISCARD,
                        NodeHostApis.factory(node).create("tenant-a", "job-1", false))
                .result().toCompletionStage().toCompletableFuture().get(40, TimeUnit.SECONDS);
        assertThat(result.error()).as(String.join("\n", logs)).isNull();
        assertThat(result.output()).as(String.join("\n", logs)).isInstanceOf(JsonArray.class);
        return (JsonArray) result.output();
    }
}
