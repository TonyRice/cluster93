package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
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

/** Jobs that start and follow jobs (D-063), 2017's Cluster93.startJob without blocking. */
class JobsFromScriptsTest {

    private static final String TENANT = "parents";
    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "parents"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                // A chain of waiting parents holds an isolate each.
                Map.entry("C93_CAPACITY", "10"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
    }

    @Test
    void aParentRunsAChildAndAwaitsItsResult() throws Exception {
        JsonObject parent = run(TENANT, """
                export default async () => {
                  const child = await jobs.run(async (input) => ({ doubled: input.n * 2 }), { n: 21 });
                  const result = await child.result();
                  return { child: child.id, result };
                }
                """);

        assertThat(parent.getString("status")).as(parent.encodePrettily()).isEqualTo("SUCCEEDED");
        JsonObject output = parent.getJsonObject("output");
        assertThat(output.getJsonObject("result").getString("status")).isEqualTo("SUCCEEDED");
        assertThat(output.getJsonObject("result").getJsonObject("output")).isEqualTo(new JsonObject().put("doubled", 42));

        JsonObject child = get(TENANT, "/v1/jobs/" + output.getString("child")).bodyAsJsonObject();
        assertThat(child.getString("tenant")).isEqualTo(TENANT);
        assertThat(child.getString("parent")).isEqualTo(parent.getString("id"));
        assertThat(child.getInteger("depth")).isEqualTo(1);
    }

    @Test
    void nestingStopsAtTheDepthLimit() throws Exception {
        String recursive = """
                export default async function level(input) {
                  try {
                    const child = await jobs.run(input.source, { source: input.source, level: input.level + 1 });
                    return (await child.result()).output;
                  } catch (e) {
                    return `${e.code} at level ${input.level}`;
                  }
                }
                """;
        JsonObject root = submitAndWait(TENANT, new JsonObject().put("script", recursive)
                .put("input", new JsonObject().put("source", recursive).put("level", 0)));

        assertThat(root.getString("output")).as(root.encodePrettily()).isEqualTo("limit_exceeded at level 5");
    }

    @Test
    void aParentCanStopAChildAndFollowItsEvents() throws Exception {
        JsonObject parent = run(TENANT, """
                export default async () => {
                  const child = await jobs.run(async (input, ctx) => {
                    await ctx.sleep(300);
                    console.log('child says hi');
                    await ctx.sleep(30000);
                  });
                  const seen = [];
                  await child.events((event) => { if (event.type === 'log') seen.push(event.line); });
                  while (seen.length === 0) await new Promise((resolve) => setTimeout(resolve, 50));
                  await child.stop('no longer needed');
                  const result = await child.result();
                  return { seen, status: result.status, viaGet: (await jobs.get(child.id)).status };
                }
                """);

        JsonObject output = parent.getJsonObject("output");
        assertThat(output.getJsonArray("seen")).isEqualTo(new JsonArray().add("child says hi"));
        assertThat(output.getString("status")).isEqualTo("CANCELLED");
        assertThat(output.getString("viaGet")).isEqualTo("CANCELLED");
    }

    @Test
    void anotherTenantsJobsLookMissing() throws Exception {
        JsonObject other = run("other-tenant", "export default () => 1");

        JsonObject probe = run(TENANT, "export default async () => { "
                + "const found = await jobs.get('" + other.getString("id") + "'); "
                + "try { await jobs.handle('" + other.getString("id") + "').result(); return [found, 'no error']; } "
                + "catch (e) { return [found, e.code]; } }");

        assertThat(probe.getJsonArray("output")).isEqualTo(new JsonArray().addNull().add("not_found"));
    }

    @Test
    void badRunsAreRefused() throws Exception {
        JsonObject probe = run(TENANT, """
                export default async () => {
                  const codes = [];
                  for (const call of [() => jobs.run(''), () => jobs.run('export default () => 1', [1]),
                                      () => jobs.run('export default () => 1', {}, { onOrphan: 'maybe' }),
                                      () => jobs.run('export default () => 1', {}, { callback: { url: 'ftp://x' } })]) {
                    try { await call(); codes.push('started'); } catch (e) { codes.push(e.code); }
                  }
                  return codes;
                }
                """);

        assertThat(probe.getJsonArray("output")).isEqualTo(new JsonArray(List.of("invalid_argument", "invalid_argument", "invalid_argument",
                "invalid_argument")));
    }

    private static JsonObject run(String tenant, String script) throws Exception {
        return submitAndWait(tenant, new JsonObject().put("script", script).put("timeoutMs", 20_000));
    }

    private static JsonObject submitAndWait(String tenant, JsonObject body) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(tenant))
                .sendJsonObject(body.copy().put("timeoutMs", 20_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        String id = submitted.bodyAsJsonObject().getString("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject job;
        do {
            Thread.sleep(50);
            job = get(tenant, "/v1/jobs/" + id).bodyAsJsonObject();
        } while (List.of("QUEUED", "RUNNING").contains(job.getString("status")) && System.nanoTime() < deadline);
        return job;
    }

    private static HttpResponse<Buffer> get(String tenant, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(tenant)).send());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
