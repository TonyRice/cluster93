package cluster93.server;

import cluster93.core.callback.Callbacks;
import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Completion callbacks end to end (D-062): signed POSTs to a local receiver, retries, ctx.setCallback, rotation. */
class CallbacksTest {

    private static final String TENANT = "hooks";

    /** What the receiver got: path, headers and body of each request. */
    record Received(String path, MultiMap headers, String body) {
    }

    private static RunningNode node;
    private static Vertx receiverVertx;
    private static HttpServer receiver;
    private static WebClient client;
    private static final List<Received> received = new CopyOnWriteArrayList<>();
    /** How many requests to a path fail with 500 before it answers 200. */
    private static final Map<String, AtomicInteger> failFirst = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "hooks"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                // The receiver is on localhost, which the egress policy refuses unless told otherwise.
                Map.entry("C93_EGRESS_ALLOW_PRIVATE", "true")))));
        TestAuth.use(node);
        receiverVertx = Vertx.vertx();
        client = WebClient.create(receiverVertx);
        receiver = await(receiverVertx.createHttpServer().requestHandler(req -> req.body().onSuccess(body -> {
            received.add(new Received(req.path(), MultiMap.caseInsensitiveMultiMap().addAll(req.headers()), body.toString()));
            AtomicInteger failures = failFirst.get(req.path());
            req.response().setStatusCode(failures != null && failures.getAndDecrement() > 0 ? 500 : 200).end();
        })).listen(0, "127.0.0.1"));
    }

    @AfterAll
    static void stop() throws Exception {
        await(receiverVertx.close());
        await(node.stop());
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + receiver.actualPort() + path;
    }

    @Test
    void aFinishedJobPostsItsSignedResultWithItsData() throws Exception {
        String jobId = submit(new JsonObject().put("script", "export default (input) => ({ total: input.a * 2 })")
                .put("input", new JsonObject().put("a", 21))
                .put("callback", new JsonObject().put("url", url("/done")).put("data", new JsonObject().put("order", 7))));

        Received hook = awaitReceived("/done", 1).getFirst();
        JsonObject body = new JsonObject(hook.body());
        assertThat(body.getString("jobId")).isEqualTo(jobId);
        assertThat(body.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(body.getJsonObject("output")).isEqualTo(new JsonObject().put("total", 42));
        assertThat(body.getJsonObject("data")).isEqualTo(new JsonObject().put("order", 7));
        assertSigned(hook);

        JsonObject callback = awaitJob(jobId, job -> "DELIVERED".equals(job.getJsonObject("callback").getString("status")))
                .getJsonObject("callback");
        assertThat(callback.getInteger("attempts")).isEqualTo(1);
        assertThat(callback.getLong("deliveredAt")).isNotNull();
    }

    @Test
    void aFailingReceiverIsRetried() throws Exception {
        failFirst.put("/flaky", new AtomicInteger(1));
        String jobId = submit(new JsonObject().put("script", "export default () => { throw new Error('nope'); }")
                .put("callback", new JsonObject().put("url", url("/flaky"))));

        List<Received> hooks = awaitReceived("/flaky", 2);
        assertThat(hooks.get(1).headers().get("Cluster93-Attempt")).isEqualTo("2");
        assertThat(new JsonObject(hooks.get(1).body()).getString("status")).as("failed jobs call back too").isEqualTo("FAILED");
        JsonObject callback = awaitJob(jobId, job -> "DELIVERED".equals(job.getJsonObject("callback").getString("status")))
                .getJsonObject("callback");
        assertThat(callback.getInteger("attempts")).isEqualTo(2);
    }

    @Test
    void aScriptCanSetItsOwnCallback() throws Exception {
        String script = "export default async (input, ctx) => { await ctx.setCallback('" + url("/from-script") + "', { why: 'asked' }); return 'ok'; }";
        String jobId = submit(new JsonObject().put("script", script));

        JsonObject body = new JsonObject(awaitReceived("/from-script", 1).getFirst().body());
        assertThat(body.getString("jobId")).isEqualTo(jobId);
        assertThat(body.getJsonObject("data")).isEqualTo(new JsonObject().put("why", "asked"));
    }

    @Test
    void rotatingTheSecretChangesTheSignature() throws Exception {
        String before = secret("GET", "/v1/callbacks/secret");
        String rotated = secret("POST", "/v1/callbacks/secret/rotate");
        assertThat(rotated).isNotEqualTo(before).startsWith("whsec_");
        assertThat(secret("GET", "/v1/callbacks/secret")).isEqualTo(rotated);

        submit(new JsonObject().put("script", "export default () => 1").put("callback", new JsonObject().put("url", url("/rotated"))));
        assertSigned(awaitReceived("/rotated", 1).getFirst());
    }

    @Test
    void badCallbacksAreRefusedAtSubmission() throws Exception {
        for (JsonObject callback : List.of(new JsonObject().put("url", "ftp://example.com/"), new JsonObject().put("url", "not a url"),
                new JsonObject().put("data", 1), new JsonObject().put("url", url("/big")).put("data", "x".repeat(Callbacks.MAX_DATA_BYTES)))) {
            HttpResponse<Buffer> refused = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(TENANT))
                    .sendJsonObject(new JsonObject().put("script", "export default () => 1").put("callback", callback)));
            assertThat(refused.statusCode()).as(callback.encode()).isEqualTo(400);
        }
    }

    private static void assertSigned(Received hook) throws Exception {
        String header = hook.headers().get(Callbacks.SIGNATURE_HEADER);
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        long t = Long.parseLong(header.substring(2, header.indexOf(',')));
        assertThat(header).isEqualTo(Callbacks.signature(secret("GET", "/v1/callbacks/secret"), t, hook.body()));
    }

    private static String secret(String method, String path) throws Exception {
        HttpResponse<Buffer> response = await(client.request(io.vertx.core.http.HttpMethod.valueOf(method), node.httpPort(), "localhost", path)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(200);
        return response.bodyAsJsonObject().getString("secret");
    }

    private static String submit(JsonObject job) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(job));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject().getString("id");
    }

    private static List<Received> awaitReceived(String path, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<Received> matching = received.stream().filter(r -> r.path().equals(path)).toList();
            if (matching.size() >= count) {
                return matching;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The receiver never got " + count + " requests to " + path + "; got " + received);
    }

    private static JsonObject awaitJob(String id, java.util.function.Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonObject job = null;
        while (System.nanoTime() < deadline) {
            job = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id).putHeader("Authorization", TestAuth.bearer(TENANT)).send())
                    .bodyAsJsonObject();
            if (job.getJsonObject("callback") != null && condition.test(job)) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition never met; last: " + (job == null ? null : job.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
