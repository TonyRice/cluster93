package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Streaming through the real ingress (docs/design/durability-and-streaming.md, milestone 2). Requests enter on
 * node B's ingress and are served by a service on node A, so the proxy between nodes is exercised.
 */
class StreamingTest {

    private static final String TENANT = "acme";
    private static final int MB = 1024 * 1024;

    private static RunningNode nodeA;
    private static RunningNode nodeB;
    private static Vertx clientVertx;
    private static WebClient client;
    private static HttpClient raw;

    @BeforeAll
    static void startCluster() throws Exception {
        nodeA = await(Automately.start(config("node-a")));
        TestAuth.use(nodeA);
        nodeB = await(Automately.start(config("node-b")));
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        raw = clientVertx.createHttpClient();
        awaitJson("/v1/nodes", json -> json.getJsonArray("nodes").size() == 2);
    }

    @AfterAll
    static void stopCluster() throws Exception {
        await(clientVertx.close());
        await(nodeB.stop());
        await(nodeA.stop());
    }

    @AfterEach
    void deleteServices() throws Exception {
        for (Object service : get("/v1/services").bodyAsJsonObject().getJsonArray("services")) {
            await(client.delete(nodeA.httpPort(), "localhost", "/v1/services/" + ((JsonObject) service).getString("name"))
                    .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        }
    }

    private static ServerConfig config(String nodeId) {
        return ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_INGRESS_DOMAIN", "apps.test"),
                Map.entry("AUTOMATELY_INGRESS_IDLE_TIMEOUT_MS", "1500"),
                Map.entry("AUTOMATELY_MAX_REQUEST_BYTES", String.valueOf(64L * MB)),
                Map.entry("AUTOMATELY_CAPACITY", "4"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "128"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_STOP_GRACE_MS", "1000"),
                Map.entry("AUTOMATELY_CLUSTERED", "true"),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "24701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1"))));
    }

    /** Defines a service that serves {@code handler} and waits until it runs on node A (requests then come in through B). */
    private static void serve(String name, String handler) throws Exception {
        HttpResponse<Buffer> created = await(client.put(nodeA.httpPort(), "localhost", "/v1/services/" + name)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("script", """
                        export default async (input, ctx) => {
                          await ctx.serve(%s);
                          return ctx.stopRequested;
                        }
                        """.formatted(handler))));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        awaitJson("/v1/services/" + name, s -> "RUNNING".equals(s.getString("status")));
        Thread.sleep(300);
    }

    private static RequestOptions ingress(String service, HttpMethod method, String path) {
        return new RequestOptions().setMethod(method).setHost("127.0.0.1").setPort(nodeB.ingressPort()).setURI(path)
                .putHeader("Host", service + "--" + TENANT + ".apps.test");
    }

    @Test
    void aLargeUploadStreamsThroughWithoutBuffering() throws Exception {
        serve("sink", """
                async (req) => {
                  let bytes = 0, sum = 0, chunks = 0;
                  for await (const part of req.body) {
                    chunks++;
                    // Sample every 4096th byte of the whole upload (positions run across chunk boundaries).
                    for (let i = (4096 - (bytes % 4096)) % 4096; i < part.length; i += 4096) sum = (sum + part[i]) % 1000000007;
                    bytes += part.length;
                  }
                  return { body: { bytes, sum, chunks } };
                }
                """);
        byte[] payload = new byte[48 * MB];
        long expectedSum = 0;
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        for (int i = 0; i < payload.length; i += 4096) {
            expectedSum = (expectedSum + (payload[i] & 0xff)) % 1_000_000_007;
        }

        String body = await(raw.request(ingress("sink", HttpMethod.POST, "/upload"))
                .compose(req -> req.send(Buffer.buffer(payload)))
                .compose(HttpClientResponse::body)).toString();

        JsonObject result = new JsonObject(body);
        assertThat(result.getLong("bytes")).isEqualTo(payload.length);
        assertThat(result.getLong("sum")).isEqualTo(expectedSum);
        assertThat(result.getInteger("chunks")).as("arrived as many chunks, not one buffer").isGreaterThan(10);
    }

    @Test
    void aLargeDownloadStreamsOut() throws Exception {
        serve("source", """
                async () => ({
                  headers: { 'content-type': 'application/octet-stream' },
                  body: (function* () {
                    const chunk = new Uint8Array(64 * 1024).fill(7);
                    for (let i = 0; i < 768; i++) yield chunk;
                  })(),
                })
                """);
        AtomicLong received = new AtomicLong();

        HttpClientResponse response = await(raw.request(ingress("source", HttpMethod.GET, "/download")).compose(req -> req.send()).map(StreamingTest::paused));
        Promise<Void> done = Promise.promise();
        response.handler(chunk -> received.addAndGet(chunk.length()));
        response.endHandler(v -> done.complete());
        response.resume();
        await(done.future());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.getHeader("transfer-encoding")).isEqualTo("chunked");
        assertThat(received.get()).isEqualTo(768L * 64 * 1024);
    }

    @Test
    void serverSentEventsStreamAndStopWhenTheClientLeaves() throws Exception {
        serve("events", """
                async () => ({
                  headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' },
                  body: (async function* () {
                    try {
                      for (let i = 1; ; i++) {
                        yield `data: ${i}\\n\\n`;
                        await new Promise((r) => setTimeout(r, 50));
                      }
                    } finally {
                      await kv.set('sse-closed', true);
                    }
                  })(),
                })
                """);
        StringBuilder seen = new StringBuilder();
        Promise<Void> threeEvents = Promise.promise();

        HttpClientResponse response = await(raw.request(ingress("events", HttpMethod.GET, "/stream")).compose(req -> req.send()).map(StreamingTest::paused));
        response.handler(chunk -> {
            seen.append(chunk);
            if (seen.toString().contains("data: 3")) {
                threeEvents.tryComplete();
            }
        });
        response.resume();
        await(threeEvents.future());
        response.request().connection().close();

        assertThat(response.getHeader("content-type")).isEqualTo("text/event-stream");
        assertThat(seen.toString()).startsWith("data: 1\n\ndata: 2\n\ndata: 3\n\n");
        awaitJobOutput("export default () => kv.get('sse-closed')", true);
    }

    @Test
    void aSlowClientHoldsTheServiceBack() throws Exception {
        serve("firehose", """
                async () => {
                  globalThis.written = 0;
                  await bus.handle('firehose-progress', () => globalThis.written);
                  return { body: (async function* () {
                    const chunk = new Uint8Array(64 * 1024);
                    for (let i = 0; i < 4000; i++) { yield chunk; globalThis.written++; }
                  })() };
                }
                """);

        HttpClientResponse response = await(raw.request(ingress("firehose", HttpMethod.GET, "/all")).compose(req -> req.send()).map(StreamingTest::paused));
        Thread.sleep(1500);
        Object progress = await(client.post(nodeB.httpPort(), "localhost", "/v1/bus/firehose-progress/request")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).send()).bodyAsJsonObject().getValue("value");
        response.request().connection().close();

        // Without backpressure the generator would run to all 4000 chunks (250 MB). With it, only what fits in the
        // kernel's socket buffers gets written: loopback auto-tunes those to several MB per socket, across two hops
        // (client to ingress, ingress to node), measured here at about 22 MB. None of that is JVM heap.
        assertThat(((Number) progress).intValue()).as("chunks written while the client wasn't reading").isLessThan(800);
    }

    @Test
    void aHandlerThatNeverAnswersTimesOut() throws Exception {
        serve("silent", "() => new Promise(() => {})");
        long start = System.nanoTime();

        HttpClientResponse response = retryWhileStarting(() -> raw.request(ingress("silent", HttpMethod.GET, "/wait")).compose(req -> req.send()));

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void requestsOverTheSizeLimitAreRefused() throws Exception {
        serve("sink2", "async (req) => { for await (const part of req.body) {} return 'ok'; }");

        HttpClientResponse response = await(raw.request(ingress("sink2", HttpMethod.POST, "/upload")
                        .putHeader("Content-Length", String.valueOf(65L * MB)))
                .compose(req -> req.send(Buffer.buffer(new byte[65 * MB]))).recover(err -> Future.failedFuture(err)));

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void theInternalEndpointRefusesCallersWithoutANodeCertificate() throws Exception {
        String endpoint = get("/v1/nodes").bodyAsJsonObject().getJsonArray("nodes").getJsonObject(0).getString("endpoint");
        int colon = endpoint.lastIndexOf(':');
        RequestOptions request = new RequestOptions().setHost(endpoint.substring(0, colon)).setPort(Integer.parseInt(endpoint.substring(colon + 1)))
                .setURI("/").putHeader("X-Automately-Job", "anything").putHeader("X-Automately-Cluster-Secret", "guess");

        // Plain HTTP, even with a guessed secret, gets nowhere: the endpoint speaks TLS with node certificates only (D-066).
        assertThatThrownBy(() -> await(raw.request(request).compose(req -> req.send())))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
        // TLS without a certificate is refused at the handshake.
        io.vertx.core.http.HttpClient tlsWithoutCertificate = clientVertx.createHttpClient(new io.vertx.core.http.HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false));
        assertThatThrownBy(() -> await(tlsWithoutCertificate.request(request).compose(req -> req.send())))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    // --- helpers ---

    /** Like a real client honouring Retry-After: a run that hasn't registered its handler yet answers 503. */
    private static HttpClientResponse retryWhileStarting(java.util.function.Supplier<Future<HttpClientResponse>> request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            HttpClientResponse response = await(request.get());
            if (response.statusCode() != 503 || System.nanoTime() > deadline) {
                return response;
            }
            await(response.body());
            Thread.sleep(100);
        }
    }

    /** Called on the event loop as the response arrives, before any body chunk can be delivered. */
    private static HttpClientResponse paused(HttpClientResponse response) {
        response.pause();
        return response;
    }

    private static void awaitJobOutput(String script, Object expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Object last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<Buffer> submitted = await(client.post(nodeA.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(TENANT))
                    .sendJsonObject(new JsonObject().put("script", script)));
            String id = submitted.bodyAsJsonObject().getString("id");
            JsonObject job = awaitJson("/v1/jobs/" + id, j -> !List.of("QUEUED", "RUNNING").contains(j.getString("status")));
            last = job.getValue("output");
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Job output never became " + expected + "; last " + last);
    }

    private static HttpResponse<Buffer> get(String path) throws Exception {
        return await(client.get(nodeA.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private static JsonObject awaitJson(String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = get(path).bodyAsJsonObject();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition never met for " + path + "; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }
}
