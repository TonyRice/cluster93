package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ignite registers a binary schema with the whole cluster the first time a node sends it. If that first time is while
 * the node is leaving (Vert.x removing its event-bus subscriptions on shutdown), the survivors wait 30 s for a schema
 * that never arrives, holding a striped thread. So every schema must be registered at start, and nothing a node does
 * afterwards, including stopping, may introduce a new one (D-042).
 */
class BinarySchemaTest {

    private static final String TENANT = "acme";

    @Test
    void noBinarySchemaIsFirstSeenAfterStartup() throws Exception {
        RunningNode nodeA = await(Automately.start(config("node-a")));
        TestAuth.use(nodeA);
        RunningNode nodeB = await(Automately.start(config("node-b")));
        Vertx clientVertx = Vertx.vertx();
        WebClient client = WebClient.create(clientVertx);
        try {
            awaitJson(client, nodeA, "/v1/nodes", json -> json.getJsonArray("nodes").size() == 2);
            Map<String, Integer> atStart = nodeA.cluster().binarySchemas();

            // A bit of everything, on node B: jobs, kv with and without expiry, increments, bus, files, a service.
            HttpResponse<Buffer> service = await(client.put(nodeB.httpPort(), "localhost", "/v1/services/echo").putHeader("Authorization", TestAuth.bearer(TENANT))
                    .sendJsonObject(new JsonObject().put("script", """
                            export default async (input, ctx) => {
                              await bus.handle('echo', async (m) => m);
                              await ctx.saveCheckpoint({ started: true });
                              return ctx.stopRequested;
                            }
                            """)));
            assertThat(service.statusCode()).as(service.bodyAsString()).isEqualTo(201);
            awaitJson(client, nodeB, "/v1/services/echo", s -> "RUNNING".equals(s.getString("status")));
            String job = await(client.post(nodeB.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(TENANT))
                    .sendJsonObject(new JsonObject().put("script", """
                            export default async (input, ctx) => {
                              await kv.set('plain', { a: 1 });
                              await kv.set('expiring', 'x', { ttlMs: 60000 });
                              await kv.increment('count');
                              await kv.namespace('other').set('k', [1, 2]);
                              await files.write('notes/a.txt', 'hello');
                              await files.write('notes/a.txt', 'replaced');
                              await ctx.step('one', async () => 1);
                              await bus.publish('news', { hi: true });
                              return await bus.request('echo', { ping: 1 });
                            }
                            """))).bodyAsJsonObject().getString("id");
            JsonObject done = awaitJson(client, nodeB, "/v1/jobs/" + job, j -> !"QUEUED".equals(j.getString("status")) && !"RUNNING".equals(j.getString("status")));
            assertThat(done.getString("status")).as(done.encode()).isEqualTo("SUCCEEDED");
            await(client.post(nodeB.httpPort(), "localhost", "/v1/services/echo/stop").putHeader("Authorization", TestAuth.bearer(TENANT)).send());
            awaitJson(client, nodeB, "/v1/services/echo", s -> "STOPPED".equals(s.getString("status")));

            await(nodeB.stop());
            Thread.sleep(500);

            assertThat(nodeA.cluster().binarySchemas()).as("binary schemas first seen after startup").isEqualTo(atStart);
        } finally {
            await(clientVertx.close());
            await(nodeA.stop());
        }
    }

    private static ServerConfig config(String nodeId) {
        return ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "4"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "128"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_CLUSTERED", "true"),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "28701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1")));
    }

    private static JsonObject awaitJson(WebClient client, RunningNode node, String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<Buffer> response = await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
            if (response.statusCode() == 200) {
                last = response.bodyAsJsonObject();
                if (condition.test(last)) {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + last);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
