package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-tenant rate limits on a real node: a minute's worth, then 429 with the headers to back off by. */
class RateLimitTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "limits"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "30"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "1"),
                Map.entry("C93_MAX_HEAP_MB", "64")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        await(clientVertx.close());
        await(node.stop());
    }

    private static HttpResponse<Buffer> get(String bearer) throws Exception {
        return await(client.get(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer).send());
    }

    @Test
    void aTenantGetsItsBudgetThen429() throws Exception {
        String busy = TestAuth.bearer("busy");
        String quiet = TestAuth.bearer("quiet");

        HttpResponse<Buffer> first = get(busy);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.getHeader("RateLimit-Policy")).isEqualTo("\"tenant\";q=30;w=60");
        assertThat(first.getHeader("RateLimit")).startsWith("\"tenant\";r=29;t=");
        for (int i = 1; i < 30; i++) {
            assertThat(get(busy).statusCode()).as("request " + i).isEqualTo(200);
        }

        HttpResponse<Buffer> limited = get(busy);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("rate_limited");
        assertThat(Integer.parseInt(limited.getHeader("Retry-After"))).isBetween(1, 3);

        assertThat(get(quiet).statusCode()).as("other tenants are unaffected").isEqualTo(200);
        assertThat(await(client.get(node.httpPort(), "localhost", "/v1/tenants").putHeader("Authorization", TestAuth.ADMIN).send())
                .statusCode()).as("the admin isn't limited").isEqualTo(200);

        Thread.sleep(2_100);
        assertThat(get(busy).statusCode()).as("the bucket refills").isEqualTo(200);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
