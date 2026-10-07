package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The MCP server on a real node (D-047): the protocol, then each kind of tool doing real work. */
class McpTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static String bearer;
    private static int ids;

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "mcp"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_INGRESS_DOMAIN", "apps.test"),
                Map.entry("AUTOMATELY_CAPACITY", "4"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64")))));
        TestAuth.use(node);
        bearer = TestAuth.bearer("agent");
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        await(clientVertx.close());
        await(node.stop());
    }

    private static HttpResponse<Buffer> send(String auth, Object message) throws Exception {
        var request = client.post(node.httpPort(), "localhost", "/mcp").putHeader("Content-Type", "application/json")
                .putHeader("Accept", "application/json, text/event-stream");
        if (auth != null) {
            request.putHeader("Authorization", auth);
        }
        return await(request.sendBuffer(io.vertx.core.json.Json.encodeToBuffer(message)));
    }

    private static JsonObject rpc(String method, JsonObject params) throws Exception {
        HttpResponse<Buffer> response = send(bearer, new JsonObject().put("jsonrpc", "2.0").put("id", ++ids).put("method", method).put("params", params));
        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(200);
        JsonObject answer = response.bodyAsJsonObject();
        assertThat(answer.getInteger("id")).isEqualTo(ids);
        return answer;
    }

    private static JsonObject tool(String name, JsonObject arguments) throws Exception {
        JsonObject answer = rpc("tools/call", new JsonObject().put("name", name).put("arguments", arguments));
        assertThat(answer.containsKey("result")).as(answer.encode()).isTrue();
        return answer.getJsonObject("result");
    }

    private static JsonObject ok(String name, JsonObject arguments) throws Exception {
        JsonObject result = tool(name, arguments);
        assertThat(result.getBoolean("isError")).as(name + ": " + result.encode()).isFalse();
        return result.getJsonObject("structuredContent");
    }

    @Test
    void theHandshakeAndTheProtocolsEdges() throws Exception {
        JsonObject init = rpc("initialize", new JsonObject().put("protocolVersion", "2025-06-18")
                .put("capabilities", new JsonObject()).put("clientInfo", new JsonObject().put("name", "test").put("version", "1")))
                .getJsonObject("result");
        assertThat(init.getString("protocolVersion")).isEqualTo("2025-06-18");
        assertThat(init.getJsonObject("serverInfo").getString("name")).isEqualTo("automately");
        assertThat(init.getJsonObject("capabilities").containsKey("tools")).isTrue();
        assertThat(init.getString("instructions")).contains("export default async (input, ctx)");
        assertThat(rpc("initialize", new JsonObject().put("protocolVersion", "1999-01-01")).getJsonObject("result")
                .getString("protocolVersion")).as("an unknown version gets the newest").isEqualTo("2025-11-25");

        assertThat(send(bearer, new JsonObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")).statusCode()).isEqualTo(202);
        assertThat(rpc("ping", new JsonObject()).getJsonObject("result")).isEqualTo(new JsonObject());
        assertThat(rpc("nope", new JsonObject()).getJsonObject("error").getInteger("code")).isEqualTo(-32601);
        assertThat(rpc("tools/call", new JsonObject().put("name", "nope")).getJsonObject("error").getInteger("code")).isEqualTo(-32602);
        assertThat(send(bearer, new JsonArray().add(new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping")))
                .bodyAsJsonObject().getJsonObject("error").getInteger("code")).as("batches").isEqualTo(-32600);

        HttpResponse<Buffer> anonymous = send(null, new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"));
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(send(TestAuth.ADMIN, new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping")).statusCode()).isEqualTo(403);
        assertThat(await(client.get(node.httpPort(), "localhost", "/mcp").send()).statusCode()).isEqualTo(405);

        List<String> names = rpc("tools/list", new JsonObject()).getJsonObject("result").getJsonArray("tools").stream()
                .map(t -> ((JsonObject) t).getString("name")).toList();
        assertThat(names).contains("run_job", "deploy_service", "service_logs", "write_file", "read_file", "request", "queue_push", "queue_stats");
    }

    @Test
    void theScriptingGuidesAreResources() throws Exception {
        JsonArray resources = rpc("resources/list", new JsonObject()).getJsonObject("result").getJsonArray("resources");
        assertThat(resources.stream().map(r -> ((JsonObject) r).getString("uri")).toList())
                .contains("automately://docs/host-apis", "automately://docs/job-context", "automately://docs/services");

        JsonObject read = rpc("resources/read", new JsonObject().put("uri", "automately://docs/host-apis")).getJsonObject("result");
        assertThat(read.getJsonArray("contents").getJsonObject(0).getString("text")).contains("kv.set").contains("ctx.serve");
        assertThat(rpc("resources/read", new JsonObject().put("uri", "file:///etc/passwd")).getJsonObject("error").getInteger("code")).isEqualTo(-32602);
    }

    @Test
    void runJobReturnsTheOutputAndLogs() throws Exception {
        JsonObject job = ok("run_job", new JsonObject()
                .put("script", "export default async (input) => { console.log('adding', input.a, input.b); return input.a + input.b; }")
                .put("input", new JsonObject().put("a", 2).put("b", 3)));

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getInteger("output")).isEqualTo(5);
        assertThat(job.getJsonArray("logs").getList()).contains("adding 2 3");
        assertThat(ok("get_job", new JsonObject().put("id", job.getString("id"))).getInteger("output")).isEqualTo(5);

        JsonObject failed = ok("run_job", new JsonObject().put("script", "export default () => { throw new Error('boom'); }"));
        assertThat(failed.getString("status")).isEqualTo("FAILED");
        assertThat(failed.getString("error")).contains("boom");
    }

    @Test
    void toolsHonourTheTokensScopes() throws Exception {
        String readOnly = mint("jobs:read");
        HttpResponse<Buffer> response = send(readOnly, new JsonObject().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call")
                .put("params", new JsonObject().put("name", "run_job").put("arguments", new JsonObject().put("script", "export default () => 1"))));

        JsonObject result = response.bodyAsJsonObject().getJsonObject("result");
        assertThat(result.getBoolean("isError")).isTrue();
        assertThat(result.getJsonArray("content").getJsonObject(0).getString("text")).contains("jobs:write");
    }

    @Test
    void anAgentDeploysAServiceAndCallsIt() throws Exception {
        JsonObject service = ok("deploy_service", new JsonObject().put("name", "greeter").put("script", """
                export default async (input, ctx) => {
                  console.log('greeter up');
                  await bus.handle('greet', async ({ name }) => `hi ${name}`);
                  await ctx.serve(async (req) => ({ body: { hello: req.query.name ?? 'world' } }));
                  return ctx.stopRequested;
                }
                """));
        assertThat(service.getJsonArray("hosts").getList()).contains("greeter--agent.apps.test");
        awaitRunning("greeter");

        // Through the ingress, as the world would call it.
        JsonObject served = retryIngress("greeter--agent.apps.test", "/?name=tony");
        assertThat(served.getString("hello")).isEqualTo("tony");

        assertThat(ok("service_status", new JsonObject().put("name", "greeter")).getString("status")).isEqualTo("RUNNING");
        assertThat(ok("service_logs", new JsonObject().put("name", "greeter")).getJsonArray("logs").getList()).contains("greeter up");
        assertThat(ok("request", new JsonObject().put("topic", "greet").put("message", new JsonObject().put("name", "agent")))
                .getString("value")).isEqualTo("hi agent");
        assertThat(ok("publish", new JsonObject().put("topic", "news").put("message", "x")).getString("published")).isEqualTo("news");
        assertThat(ok("queue_push", new JsonObject().put("queue", "jobs-to-do").put("message", new JsonObject().put("n", 1))).getString("id"))
                .isNotBlank();
        assertThat(ok("queue_stats", new JsonObject().put("queue", "jobs-to-do")).getInteger("ready")).isEqualTo(1);
        assertThat(ok("queue_dead", new JsonObject().put("queue", "jobs-to-do")).getJsonArray("messages")).isEmpty();

        ok("write_file", new JsonObject().put("path", "notes/todo.md").put("content", "- ship it").put("contentType", "text/markdown"));
        assertThat(ok("read_file", new JsonObject().put("path", "notes/todo.md")).getString("content")).isEqualTo("- ship it");
        assertThat(ok("list_files", new JsonObject().put("prefix", "notes/")).getJsonArray("files")).hasSize(1);
        ok("delete_file", new JsonObject().put("path", "notes/todo.md"));
        assertThat(tool("read_file", new JsonObject().put("path", "notes/todo.md")).getBoolean("isError")).isTrue();

        ok("stop_service", new JsonObject().put("name", "greeter"));
        ok("delete_service", new JsonObject().put("name", "greeter"));
        assertThat(tool("service_status", new JsonObject().put("name", "greeter")).getBoolean("isError")).isTrue();
    }

    // --- helpers ---

    private static String mint(String scope) throws Exception {
        HttpResponse<Buffer> minted = await(client.post(node.httpPort(), "localhost", "/v1/tenants/agent/tokens")
                .putHeader("Authorization", TestAuth.ADMIN)
                .sendJsonObject(new JsonObject().put("name", "narrow").put("scopes", new JsonArray().add(scope))));
        return "Bearer " + minted.bodyAsJsonObject().getString("token");
    }

    private static void awaitRunning(String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if ("RUNNING".equals(ok("service_status", new JsonObject().put("name", name)).getString("status"))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(name + " never ran");
    }

    private static JsonObject retryIngress(String host, String path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            HttpResponse<Buffer> response = await(client.get(node.ingressPort(), "localhost", path).putHeader("Host", host).send());
            if (response.statusCode() == 200) {
                return response.bodyAsJsonObject();
            }
            assertThat(System.nanoTime()).as("served: " + response.statusCode()).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
