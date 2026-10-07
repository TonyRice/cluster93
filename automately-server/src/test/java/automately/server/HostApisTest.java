package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** kv, bus, fetch and HTTP hosting end to end on a real two-node cluster (docs/scripting/host-apis.md). */
class HostApisTest {

    private static final String TENANT = "acme";
    private static final Map<String, List<String>> TXT_RECORDS = new ConcurrentHashMap<>();

    private static RunningNode nodeA;
    private static RunningNode nodeB;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void startCluster() throws Exception {
        nodeA = await(Automately.start(config("node-a"), vertx -> name -> Future.succeededFuture(TXT_RECORDS.getOrDefault(name, List.of()))));
        TestAuth.use(nodeA);
        nodeB = await(Automately.start(config("node-b"), vertx -> name -> Future.succeededFuture(TXT_RECORDS.getOrDefault(name, List.of()))));
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        awaitJson(nodeA, TENANT, "/v1/nodes", json -> json.getJsonArray("nodes").size() == 2);
    }

    @AfterAll
    static void stopCluster() throws Exception {
        await(clientVertx.close());
        await(nodeB.stop());
        await(nodeA.stop());
    }

    @AfterEach
    void deleteServices() throws Exception {
        for (String tenant : List.of(TENANT, "globex")) {
            for (Object service : get(nodeA, tenant, "/v1/services").bodyAsJsonObject().getJsonArray("services")) {
                await(client.delete(nodeA.httpPort(), "localhost", "/v1/services/" + ((JsonObject) service).getString("name"))
                        .putHeader("Authorization", TestAuth.bearer(tenant)).send());
            }
        }
    }

    private static ServerConfig config(String nodeId) {
        return ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_INGRESS_DOMAIN", "apps.test"),
                Map.entry("AUTOMATELY_CAPACITY", "4"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_STOP_GRACE_MS", "1000"),
                Map.entry("AUTOMATELY_CLUSTERED", "true"),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "20701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1")));
    }

    // --- kv ---

    @Test
    void kvIsSharedAcrossNodesAndIsolatedBetweenTenants() throws Exception {
        JsonObject writer = runJob(nodeA, TENANT, """
                export default async () => {
                  await kv.set('greeting', { text: 'hello' });
                  await kv.namespace('counters').increment('visits', 5);
                  await kv.set('temp', 1, { ttlMs: 100 });
                  return 'written';
                }
                """);
        assertThat(writer.getString("status")).as(writer.encodePrettily()).isEqualTo("SUCCEEDED");
        Thread.sleep(150);

        JsonObject reader = runJob(nodeB, TENANT, """
                export default async () => [
                  await kv.get('greeting'),
                  await kv.namespace('counters').increment('visits'),
                  await kv.get('temp'),
                  (await kv.list()).keys,
                ]
                """);
        JsonObject otherTenant = runJob(nodeB, "globex", "export default () => kv.get('greeting')");

        assertThat(reader.getJsonArray("output")).isEqualTo(new JsonArray()
                .add(new JsonObject().put("text", "hello")).add(6).addNull().add(new JsonArray().add("greeting")));
        assertThat(otherTenant.getValue("output")).isNull();
    }

    // --- bus ---

    @Test
    void aServiceAnswersRequestsFromJobsAndFromHttp() throws Exception {
        putService("calculator", """
                export default async (input, ctx) => {
                  await bus.handle('add', (message) => message.a + message.b);
                  return ctx.stopRequested;
                }
                """);
        awaitServiceRunning("calculator");

        JsonObject job = runJob(nodeB, TENANT, "export default () => bus.request('add', { a: 2, b: 40 })");
        HttpResponse<Buffer> overHttp = await(client.post(nodeB.httpPort(), "localhost", "/v1/bus/add/request")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("a", 1).put("b", 1)));
        HttpResponse<Buffer> wrongTenant = await(client.post(nodeB.httpPort(), "localhost", "/v1/bus/add/request")
                .putHeader("Authorization", TestAuth.bearer("globex")).sendJsonObject(new JsonObject().put("a", 1).put("b", 1)));

        assertThat(job.getValue("output")).isEqualTo(42);
        assertThat(overHttp.bodyAsJsonObject()).isEqualTo(new JsonObject().put("value", 2));
        assertThat(wrongTenant.statusCode()).as("another tenant's topic of the same name has no handler").isEqualTo(404);
    }

    @Test
    void publishedMessagesReachSubscribers() throws Exception {
        putService("tally", """
                export default async (input, ctx) => {
                  await bus.subscribe('orders', async (order) => { await kv.increment('orders-seen', order.items); });
                  return ctx.stopRequested;
                }
                """);
        awaitServiceRunning("tally");
        Thread.sleep(300);

        for (int items : new int[]{1, 2, 3}) {
            assertThat(await(client.post(nodeB.httpPort(), "localhost", "/v1/bus/orders").putHeader("Authorization", TestAuth.bearer(TENANT))
                    .sendJsonObject(new JsonObject().put("items", items))).statusCode()).isEqualTo(202);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Object seen = null;
        while (System.nanoTime() < deadline) {
            seen = runJob(nodeA, TENANT, "export default () => kv.get('orders-seen')").getValue("output");
            if (Integer.valueOf(6).equals(seen)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(seen).isEqualTo(6);
    }

    // --- fetch ---

    @Test
    void fetchToPrivateAddressesIsRefusedByDefault() throws Exception {
        JsonObject job = runJob(nodeA, TENANT, """
                export default async () => {
                  try { await fetch('http://127.0.0.1:%d/health'); return 'reached'; } catch (e) { return [e.name, e.code]; }
                }
                """.formatted(nodeA.httpPort()));

        assertThat(job.getJsonArray("output")).isEqualTo(new JsonArray().add("TypeError").add("egress_denied"));
    }

    // --- HTTP hosting ---

    static final String WEB = """
            export default async (input, ctx) => {
              await ctx.serve(async (req) => {
                if (req.path === '/hello') return `hello from run ${ctx.service.run}`;
                if (req.path === '/echo') return { status: 201, headers: { 'x-served-by': 'automately' }, body: { method: req.method, q: req.query.q, body: await req.json() } };
                return { status: 404, body: { error: 'no route' } };
              });
              return ctx.stopRequested;
            }
            """;

    @Test
    void servesHttpThroughAnyNodesIngress() throws Exception {
        JsonObject service = putService("web", WEB);
        assertThat(service.getJsonArray("hosts")).isEqualTo(new JsonArray().add("web--acme.apps.test"));
        awaitServiceRunning("web");

        for (RunningNode node : List.of(nodeA, nodeB)) {
            HttpResponse<Buffer> hello = await(ingress(node, "web--acme.apps.test", "GET", "/hello").send());
            assertThat(hello.statusCode()).as(hello.bodyAsString()).isEqualTo(200);
            assertThat(hello.bodyAsString()).isEqualTo("hello from run 1");
            assertThat(hello.getHeader("content-type")).isEqualTo("text/plain; charset=utf-8");
        }
        // HTTP/2 (cleartext, prior knowledge): the host arrives as :authority, not a Host header.
        io.vertx.core.http.HttpClient h2 = clientVertx.createHttpClient(new io.vertx.core.http.HttpClientOptions()
                .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        String overH2 = await(h2.request(new io.vertx.core.http.RequestOptions()
                        .setServer(io.vertx.core.net.SocketAddress.inetSocketAddress(nodeA.ingressPort(), "127.0.0.1"))
                        .setHost("web--acme.apps.test").setPort(nodeA.ingressPort()).setURI("/hello"))
                .compose(req -> req.send().compose(res -> {
                    assertThat(res.version()).isEqualTo(io.vertx.core.http.HttpVersion.HTTP_2);
                    return res.body();
                }))).toString();
        assertThat(overH2).isEqualTo("hello from run 1");
        await(h2.close());

        HttpResponse<Buffer> echo = await(ingress(nodeB, "web--acme.apps.test:8000", "POST", "/echo?q=search")
                .sendJsonObject(new JsonObject().put("n", 1)));
        assertThat(echo.statusCode()).isEqualTo(201);
        assertThat(echo.getHeader("x-served-by")).isEqualTo("automately");
        assertThat(echo.bodyAsJsonObject()).isEqualTo(new JsonObject().put("method", "POST").put("q", "search").put("body", new JsonObject().put("n", 1)));
    }

    @Test
    void unknownHostsAre404AndStoppedServicesAre503() throws Exception {
        assertThat(await(ingress(nodeA, "nothing--acme.apps.test", "GET", "/").send()).statusCode()).isEqualTo(404);
        assertThat(await(ingress(nodeA, "not-a-route.example.com", "GET", "/").send()).statusCode()).isEqualTo(404);

        putService("web", WEB);
        awaitServiceRunning("web");
        await(client.post(nodeA.httpPort(), "localhost", "/v1/services/web/stop").putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        awaitJson(nodeA, TENANT, "/v1/services/web", s -> s.getValue("currentJobId") == null);

        HttpResponse<Buffer> stopped = await(ingress(nodeA, "web--acme.apps.test", "GET", "/hello").send());
        assertThat(stopped.statusCode()).isEqualTo(503);
        assertThat(stopped.getHeader("retry-after")).isEqualTo("1");
    }

    @Test
    void customDomainsRouteOnlyAfterTxtVerification() throws Exception {
        putService("web", WEB);
        awaitServiceRunning("web");

        HttpResponse<Buffer> claimed = await(client.post(nodeA.httpPort(), "localhost", "/v1/services/web/domains")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("domain", "Shop.Example.com")));
        assertThat(claimed.statusCode()).as(claimed.bodyAsString()).isEqualTo(201);
        JsonObject verification = claimed.bodyAsJsonObject().getJsonObject("verification");
        assertThat(verification.getString("name")).isEqualTo("_automately.shop.example.com");

        assertThat(await(ingress(nodeB, "shop.example.com", "GET", "/hello").send()).statusCode()).as("not verified yet").isEqualTo(404);
        assertThat(post(nodeA, TENANT, "/v1/services/web/domains/shop.example.com/verify").statusCode()).isEqualTo(409);

        HttpResponse<Buffer> stolen = await(client.post(nodeA.httpPort(), "localhost", "/v1/services/web/domains")
                .putHeader("Authorization", TestAuth.bearer("globex")).sendJsonObject(new JsonObject().put("domain", "shop.example.com")));
        assertThat(stolen.statusCode()).as("globex has no service named web").isEqualTo(404);

        TXT_RECORDS.put("_automately.shop.example.com", List.of(verification.getString("value")));
        HttpResponse<Buffer> verified = post(nodeA, TENANT, "/v1/services/web/domains/shop.example.com/verify");
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(verified.bodyAsJsonObject().getBoolean("verified")).isTrue();

        HttpResponse<Buffer> served = await(ingress(nodeB, "shop.example.com", "GET", "/hello").send());
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(get(nodeA, TENANT, "/v1/services/web").bodyAsJsonObject().getJsonArray("hosts")).contains("shop.example.com");

        // Deleting the service releases the domain: it stops routing, and a new "web" doesn't inherit it.
        await(client.delete(nodeA.httpPort(), "localhost", "/v1/services/web").putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        assertThat(await(ingress(nodeB, "shop.example.com", "GET", "/hello").send()).statusCode()).isEqualTo(404);
        assertThat(putService("web", WEB).getJsonArray("hosts")).isEqualTo(new JsonArray().add("web--acme.apps.test"));
    }

    @Test
    void namesWithDoubleHyphensAreRejected() throws Exception {
        assertThat(await(client.put(nodeA.httpPort(), "localhost", "/v1/services/my--web").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", WEB))).statusCode()).isEqualTo(400);
        // Tenant names become part of hostnames too, so the admin can't create one with "--".
        assertThat(await(client.post(nodeA.httpPort(), "localhost", "/v1/tenants").putHeader("Authorization", TestAuth.ADMIN)
                .sendJsonObject(new JsonObject().put("name", "a--b"))).statusCode()).isEqualTo(400);
    }

    // --- helpers ---

    private static HttpRequest<Buffer> ingress(RunningNode node, String host, String method, String path) {
        return client.request(io.vertx.core.http.HttpMethod.valueOf(method), node.ingressPort(), "localhost", path).putHeader("Host", host);
    }

    private static JsonObject putService(String name, String script) throws Exception {
        HttpResponse<Buffer> response = await(client.put(nodeA.httpPort(), "localhost", "/v1/services/" + name)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("script", script)));
        assertThat(response.statusCode()).as(response.bodyAsString()).isIn(200, 201);
        return response.bodyAsJsonObject();
    }

    private static void awaitServiceRunning(String name) throws Exception {
        awaitJson(nodeA, TENANT, "/v1/services/" + name, s -> "RUNNING".equals(s.getString("status")));
        Thread.sleep(200); // let the run register its handlers
    }

    private static JsonObject runJob(RunningNode node, String tenant, String script) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(tenant))
                .sendJsonObject(new JsonObject().put("script", script).put("timeoutMs", 10_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        String id = submitted.bodyAsJsonObject().getString("id");
        return awaitJson(node, tenant, "/v1/jobs/" + id, j -> !List.of("QUEUED", "RUNNING").contains(j.getString("status")));
    }

    private static HttpResponse<Buffer> get(RunningNode node, String tenant, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(tenant)).send());
    }

    private static HttpResponse<Buffer> post(RunningNode node, String tenant, String path) throws Exception {
        return await(client.post(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(tenant)).send());
    }

    private static JsonObject awaitJson(RunningNode node, String tenant, String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = get(node, tenant, path).bodyAsJsonObject();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
