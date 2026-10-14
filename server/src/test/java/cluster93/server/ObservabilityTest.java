package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Metrics on the management port and readiness (phase 5): real work shows up in Prometheus. */
class ObservabilityTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "observed"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "2"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
    }

    private static String scrape() throws Exception {
        HttpResponse<Buffer> metrics = await(client.get(node.metricsPort(), "localhost", "/metrics").send());
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.getHeader("Content-Type")).startsWith("text/plain");
        return metrics.bodyAsString();
    }

    @Test
    void workShowsUpInPrometheus() throws Exception {
        String bearer = TestAuth.bearer("watched");
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer)
                .sendJsonObject(new JsonObject().put("script", "export default () => 'measured'")));
        String id = submitted.bodyAsJsonObject().getString("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && !"SUCCEEDED".equals(await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id)
                .putHeader("Authorization", bearer).send()).bodyAsJsonObject().getString("status"))) {
            Thread.sleep(100);
        }
        // Over the limit of 2 a minute by now: the next call is refused and counted.
        assertThat(await(client.get(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer).send()).statusCode()).isEqualTo(429);
        Thread.sleep(1_100);

        String metrics = scrape();
        if (System.getenv("C93_DUMP_METRICS") != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("C93_DUMP_METRICS")), metrics);
        }
        assertThat(metrics)
                .contains("c93_jobs_submitted_total{kind=\"job\",node=\"observed\"} 1.0")
                .contains("c93_jobs_duration_seconds_count{node=\"observed\",status=\"SUCCEEDED\"} 1")
                .contains("c93_jobs_queue_wait_seconds_count{node=\"observed\"} 1")
                .contains("c93_isolates_start_seconds_count{node=\"observed\"} 1")
                .contains("c93_jobs_running{node=\"observed\"} 0.0")
                .contains("c93_api_rate_limited_total{node=\"observed\",tenant=\"watched\"}")
                .contains("c93_cluster_nodes{node=\"observed\"} 1.0")
                .contains("c93_cluster_leader{node=\"observed\"} 1.0")
                .contains("vertx_http_server_requests_total")
                .contains("jvm_memory_used_bytes");
        assertThat(metrics).as("no per-job or per-client labels").doesNotContain(id).doesNotContain("remote=");
    }

    @Test
    void readyOnceJoined() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        HttpResponse<Buffer> ready;
        do {
            ready = await(client.get(node.httpPort(), "localhost", "/ready").send());
            Thread.sleep(100);
        } while (ready.statusCode() != 200 && System.nanoTime() < deadline);
        assertThat(ready.statusCode()).as(ready.bodyAsString()).isEqualTo(200);
        assertThat(ready.bodyAsJsonObject().getString("status")).isEqualTo("READY");
        assertThat(await(client.get(node.metricsPort(), "localhost", "/nope").send()).statusCode()).isEqualTo(404);
    }

    @Test
    void jsonLogsCarryTheirKeyValues() throws Exception {
        ch.qos.logback.classic.LoggerContext context = new ch.qos.logback.classic.LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(ObservabilityTest.class.getResource("/logback-json.xml"));
        var appender = (ch.qos.logback.core.OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>)
                context.getLogger("ROOT").getAppender("out");

        // What the configured encoder makes of a log call with key-value pairs, like NodeVerticle's "job ended".
        ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent("test",
                context.getLogger("cluster93.test"), ch.qos.logback.classic.Level.INFO, "Job {} ended", null, new Object[] {"j-1"});
        event.addKeyValuePair(new org.slf4j.event.KeyValuePair("jobId", "j-1"));
        event.addKeyValuePair(new org.slf4j.event.KeyValuePair("tenant", "acme"));
        String encoded = new String(appender.getEncoder().encode(event), java.nio.charset.StandardCharsets.UTF_8).strip();
        context.stop();

        assertThat(appender.getEncoder()).isInstanceOf(ch.qos.logback.classic.encoder.JsonEncoder.class);
        JsonObject line = new JsonObject(encoded);
        assertThat(line.getString("level")).isEqualTo("INFO");
        assertThat(encoded).contains("\"jobId\"").contains("\"j-1\"").contains("\"tenant\"").contains("\"acme\"");
        assertThat(encoded.lines().count()).as("one line per event").isEqualTo(1);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
