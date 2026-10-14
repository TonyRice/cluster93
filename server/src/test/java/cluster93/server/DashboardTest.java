package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every metric the Grafana dashboard queries exists (phase 5): a dashboard that silently shows "No data" because a
 * metric was renamed fails here instead. Runs the work that produces each one on a real (clustered) node, then scrapes.
 */
class DashboardTest {

    private static final Path DASHBOARD = Path.of("..", "deploy", "observability", "grafana", "dashboards", "cluster93.json");
    private static final Pattern METRIC = Pattern.compile("\\b((?:c93|vertx|jvm|process|system)_[a-z_]+)\\b");

    @Test
    void everyMetricTheDashboardUsesExists() throws Exception {
        Set<String> used = new TreeSet<>();
        JsonObject dashboard = new JsonObject(Files.readString(DASHBOARD));
        for (Object panel : dashboard.getJsonArray("panels")) {
            for (Object target : ((JsonObject) panel).getJsonArray("targets", new JsonArray())) {
                Matcher matcher = METRIC.matcher(((JsonObject) target).getString("expr"));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        assertThat(used).hasSizeGreaterThan(15);

        RunningNode node = await(Cluster93.start(ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("C93_NODE_ID", "dash"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "40"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_TENANT_STORAGE_MB", "1"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", "32701"),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1"))))));
        Vertx clientVertx = Vertx.vertx();
        WebClient client = WebClient.create(clientVertx);
        try {
            TestAuth.use(node);
            String bearer = TestAuth.bearer("dash");
            // A job, a service run, a stored file and a refused one: each family of meters gets a sample.
            await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer)
                    .sendJsonObject(new JsonObject().put("script", "export default () => 1")));
            await(client.put(node.httpPort(), "localhost", "/v1/services/svc").putHeader("Authorization", bearer)
                    .sendJsonObject(new JsonObject().put("script", "export default async (input, ctx) => ctx.stopRequested")));
            await(client.put(node.httpPort(), "localhost", "/v1/files/small.txt").putHeader("Authorization", bearer).sendBuffer(Buffer.buffer("x")));
            // A queue message pushed and consumed, and a callback to an address the egress policy refuses.
            await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer)
                    .sendJsonObject(new JsonObject().put("script", """
                            export default async () => {
                              const q = bus.queue('dash');
                              await q.push(1);
                              await new Promise((resolve) => q.consume(async () => { setTimeout(resolve, 50); }));
                            }
                            """).put("callback", new JsonObject().put("url", "http://169.254.169.254/hook"))));
            await(client.put(node.httpPort(), "localhost", "/v1/files/big.bin").putHeader("Authorization", bearer)
                    .sendBuffer(Buffer.buffer(new byte[2 * 1024 * 1024])));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            String scrape;
            Set<String> missing;
            do {
                // Polling the API also runs into the rate limit, which is one of the meters.
                await(client.get(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer).send());
                Thread.sleep(200);
                scrape = await(client.get(node.metricsPort(), "localhost", "/metrics").send()).bodyAsString();
                missing = new TreeSet<>();
                for (String metric : used) {
                    if (!scrape.contains("\n" + metric + "{") && !scrape.contains("\n" + metric + " ")) {
                        missing.add(metric);
                    }
                }
            } while (!missing.isEmpty() && System.nanoTime() < deadline);

            assertThat(missing).as("metrics the dashboard queries that the node doesn't export").isEmpty();
        } finally {
            if (clientVertx != null) { await(clientVertx.close()); }
            if (node != null) { await(node.stop()); }
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
