package cluster93.core.host;

import cluster93.core.runtime.HostException;
import cluster93.core.runtime.api.FetchApi;
import cluster93.core.runtime.api.HostApis;
import cluster93.core.runtime.api.KvApi;
import cluster93.core.runtime.api.Registration;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The node's host APIs (D-060): validation, limits and tenant scoping, without a script engine. */
class NodeHostApisTest {

    private Vertx vertx;
    private KvStore kv;
    private ServeRegistry serve;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        kv = new SharedDataKvStore(vertx);
        serve = new ServeRegistry();
        server = await(vertx.createHttpServer().requestHandler(req -> {
            switch (req.path()) {
                case "/json" -> req.response().putHeader("content-type", "application/json").end("{\"hello\":\"world\"}");
                case "/echo" -> req.body().onSuccess(body -> req.response().putHeader("x-method", req.method().name())
                        .end(req.getHeader("x-custom") + "|" + body));
                case "/hex" -> req.body().onSuccess(body -> req.response().end(java.util.HexFormat.of().formatHex(body.getBytes())));
                case "/redirect" -> req.response().setStatusCode(302).putHeader("location", "http://169.254.169.254/").end();
                case "/big" -> {
                    // 20 MB in 64 KB chunks, honouring backpressure.
                    req.response().setChunked(true);
                    io.vertx.core.buffer.Buffer chunk = io.vertx.core.buffer.Buffer.buffer("x".repeat(64 * 1024));
                    int[] sent = {0};
                    Runnable[] pump = new Runnable[1];
                    pump[0] = () -> {
                        while (sent[0] < 320 && !req.response().writeQueueFull()) {
                            req.response().write(chunk);
                            sent[0]++;
                        }
                        if (sent[0] == 320) {
                            req.response().end();
                        } else {
                            req.response().drainHandler(v -> pump[0].run());
                        }
                    };
                    pump[0].run();
                }
                case "/slow" -> vertx.setTimer(3_000, id -> req.response().end("late"));
                default -> req.response().setStatusCode(404).end();
            }
        }).listen(0, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private NodeHostApis host(String tenant, boolean allowPrivate) {
        return new NodeHostApis(new NodeHostApis.Node(vertx, kv, new FetchClient(vertx, new EgressPolicy(allowPrivate)), serve), tenant, "job-1", true);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.actualPort() + path;
    }

    // --- kv through the host API ---

    @Test
    void kvOperationsAreScopedToTheTenant() throws Exception {
        await(host("acme", false).kv().set("default", "k", 1, null));

        assertThat(await(host("acme", false).kv().get("default", "k"))).isEqualTo(1);
        assertThat(await(host("globex", false).kv().get("default", "k"))).isNull();
    }

    @Test
    void kvValidatesArguments() {
        KvApi kv = host("acme", false).kv();

        assertHostError(kv.get("default", null), "TypeError", "invalid_argument");
        assertHostError(kv.get("default", "a\u001fb"), "TypeError", "invalid_argument");
        assertHostError(kv.get("default", "k".repeat(600)), "RangeError", "limit_exceeded");
        assertHostError(kv.set("default", "k", "v".repeat(KvApi.MAX_VALUE_BYTES + 1), null), "RangeError", "limit_exceeded");
        assertHostError(kv.set("default", "k", 1, -5L), "TypeError", "invalid_argument");
        assertHostError(kv.list("default", "", 5000, null), "TypeError", "invalid_argument");
        assertHostError(HostApis.NONE.kv().get("default", "k"), "Error", "unavailable");
    }

    // --- the tenant quota covers kv and files together (D-043) ---

    private NodeHostApis quotaHost(cluster93.core.files.FileStore files, cluster93.core.storage.TenantQuota quota) {
        return new NodeHostApis(new NodeHostApis.Node(vertx, kv, new FetchClient(vertx, EgressPolicy.PUBLIC_ONLY), serve, files,
                cluster93.core.files.StorageCapacity.UNLIMITED, quota, (t, target) -> quota.remaining(t), null, null), "acme", "job-q", false);
    }

    @Test
    void kvAndFilesShareTheTenantQuota() throws Exception {
        cluster93.core.files.FileStore files = new cluster93.core.files.SharedDataFileStore(vertx, cluster93.core.files.StorageCapacity.UNLIMITED);
        cluster93.core.storage.TenantQuota quota = new cluster93.core.storage.TenantQuota(1_000,
                t -> files.usage(t).compose(f -> kv.usage(t).map(k -> f + k)));
        NodeHostApis host = quotaHost(files, quota);
        String big = "x".repeat(600);   // an entry of 7 + 1 + 602 = 610 bytes

        await(host.kv().set("default", "a", big, null));
        assertHostError(host.kv().set("default", "b", big, null), "RangeError", "limit_exceeded");

        // Full or not, a tenant can shrink and regrow what it has, as long as the result fits.
        await(host.kv().set("default", "a", "x".repeat(300), null));
        await(host.kv().set("default", "a", big, null));
        assertThat(await(kv.usage("acme"))).isEqualTo(610);

        // Files get what kv leaves: 390 bytes.
        cluster93.core.files.FileWriter tooBig = await(host.files().create("big.bin", null));
        assertThatThrownBy(() -> await(tooBig.write(io.vertx.core.buffer.Buffer.buffer(new byte[391]))))
                .isInstanceOf(ExecutionException.class).cause().hasMessageContaining("quota");
        cluster93.core.files.FileWriter fits = await(host.files().create("fits.bin", null));
        await(fits.write(io.vertx.core.buffer.Buffer.buffer(new byte[300])));
        await(fits.finish());

        // And kv gets what files leave: 90 bytes.
        assertHostError(host.kv().set("default", "c", "y".repeat(100), null), "RangeError", "limit_exceeded");
        await(host.kv().set("default", "c", "y".repeat(50), null));
        assertHostError(host.kv().increment("default", "visits", 1), "RangeError", "limit_exceeded");

        // Deleting makes room again.
        await(host.kv().delete("default", "a"));
        await(host.kv().increment("default", "visits", 1));
    }

    // --- bus ---

    @Test
    void publishReachesSubscribersOfTheSameTenantOnly() throws Exception {
        List<Object> acme = new CopyOnWriteArrayList<>();
        List<Object> globex = new CopyOnWriteArrayList<>();
        await(host("acme", false).bus().subscribe("orders", in -> acme.add(in.payload())));
        await(host("globex", false).bus().subscribe("orders", in -> globex.add(in.payload())));

        await(host("acme", false).bus().publish("orders", new JsonObject().put("id", 1)));
        Thread.sleep(200);

        assertThat(acme).containsExactly(new JsonObject().put("topic", "orders").put("message", new JsonObject().put("id", 1)));
        assertThat(globex).isEmpty();
    }

    @Test
    void requestsAreAnsweredByAHandler() throws Exception {
        await(host("acme", false).bus().handle("math", in -> {
            JsonObject message = ((JsonObject) in.payload()).getJsonObject("message");
            in.reply(message.getInteger("a") + message.getInteger("b"));
        }));

        Object answer = await(host("acme", false).bus().request("math", new JsonObject().put("a", 2).put("b", 3), 10_000));

        assertThat(answer).isEqualTo(5);
        assertHostError(host("globex", false).bus().request("math", 1, 10_000), "Error", "no_handler");
    }

    @Test
    void requestFailuresAndTimeoutsAreTyped() throws Exception {
        await(host("acme", false).bus().handle("fails", in -> in.fail(500, "handler broke")));
        await(host("acme", false).bus().handle("silent", in -> { }));

        assertHostError(host("acme", false).bus().request("fails", 1, 10_000), "Error", "handler_failed");
        assertHostError(host("acme", false).bus().request("silent", 1, 200), "TimeoutError", "timeout");
    }

    @Test
    void closingStopsListeners() throws Exception {
        List<Object> seen = new CopyOnWriteArrayList<>();
        NodeHostApis host = host("acme", false);
        await(host.bus().subscribe("t", in -> seen.add(in.payload())));
        await(host.close());

        await(host("acme", false).bus().publish("t", 1));
        Thread.sleep(200);

        assertThat(seen).isEmpty();
    }

    @Test
    void topicsAreValidated() {
        assertHostError(host("acme", false).bus().publish("has space", 1), "TypeError", "invalid_argument");
    }

    // --- fetch ---

    /** Reads a fetched body to the end. */
    private static String readAll(cluster93.core.runtime.FetchedResponse response) throws Exception {
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
        io.vertx.core.buffer.Buffer all = io.vertx.core.buffer.Buffer.buffer();
        response.body().handler(all::appendBuffer, () -> done.complete(all.toString()), done::completeExceptionally);
        response.body().resume();
        return done.get(30, TimeUnit.SECONDS);
    }

    private cluster93.core.runtime.FetchedResponse fetch(NodeHostApis host, JsonObject args) throws Exception {
        return await(host.fetch().fetch(args));
    }

    @Test
    void fetchReturnsStatusHeadersAndAStreamingBody() throws Exception {
        cluster93.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/json")));

        assertThat(response.meta().getInteger("status")).isEqualTo(200);
        assertThat(response.meta().getJsonObject("headers").getString("content-type")).isEqualTo("application/json");
        assertThat(new JsonObject(readAll(response))).isEqualTo(new JsonObject().put("hello", "world"));
    }

    @Test
    void fetchSendsMethodHeadersAndBody() throws Exception {
        cluster93.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/echo"))
                .put("method", "POST").put("headers", new JsonObject().put("x-custom", "yes").put("host", "evil.example"))
                .put("body", "payload"));

        assertThat(readAll(response)).isEqualTo("yes|payload");
        assertThat(response.meta().getJsonObject("headers").getString("x-method")).isEqualTo("POST");
    }

    @Test
    void fetchSendsABinaryBodyAsItsBytes() throws Exception {
        byte[] body = {(byte) 0xff, 0x00, (byte) 0x80, (byte) 0xc3, 0x28};   // not UTF-8: decoding it would change it
        cluster93.core.runtime.FetchedResponse response = await(host("acme", true).fetch()
                .fetch(new JsonObject().put("url", url("/hex")).put("method", "POST"), body));

        assertThat(readAll(response)).isEqualTo("ff0080c328");
    }

    @Test
    void fetchDoesNotFollowRedirects() throws Exception {
        cluster93.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/redirect")));

        assertThat(response.meta().getInteger("status")).isEqualTo(302);
        response.body().cancel();
    }

    @Test
    void fetchStreamsLargeBodiesWithoutALimit() throws Exception {
        cluster93.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/big")));

        assertThat(readAll(response)).hasSize(320 * 64 * 1024);
    }

    @Test
    void fetchTimesOut() {
        assertHostError(host("acme", true).fetch().fetch(new JsonObject().put("url", url("/slow")).put("timeoutMs", 300)), "TimeoutError", "timeout");
    }

    @Test
    void concurrentFetchesAreLimitedUntilBodiesAreDone() throws Exception {
        NodeHostApis host = host("acme", true);
        List<cluster93.core.runtime.FetchedResponse> open = new java.util.ArrayList<>();
        for (int i = 0; i < FetchApi.MAX_CONCURRENT; i++) {
            open.add(fetch(host, new JsonObject().put("url", url("/json"))));
        }

        assertHostError(host.fetch().fetch(new JsonObject().put("url", url("/json"))), "RangeError", "limit_exceeded");
        readAll(open.getFirst());
        open.subList(1, open.size()).forEach(r -> r.body().cancel());
        Thread.sleep(100);
        assertThat(readAll(fetch(host, new JsonObject().put("url", url("/json"))))).contains("hello");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/", "http://localhost/", "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.1/",
            "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://[fd00::1]/", "http://0.0.0.0/",
            "http://100.64.0.1/", "http://[::ffff:127.0.0.1]/"})
    void privateAndReservedAddressesAreRefusedByDefault(String target) {
        assertHostError(host("acme", false).fetch().fetch(new JsonObject().put("url", target)), "TypeError", "egress_denied");
    }

    @Test
    void onlyHttpSchemesAreAllowed() {
        assertHostError(host("acme", true).fetch().fetch(new JsonObject().put("url", "file:///etc/passwd")), "TypeError", "invalid_argument");
        assertHostError(host("acme", true).fetch().fetch(new JsonObject().put("url", "ftp://example.com/")), "TypeError", "invalid_argument");
    }

    @Test
    void publicAddressesPassThePolicy() throws Exception {
        assertThat(EgressPolicy.PUBLIC_ONLY.allows(InetAddress.getByName("93.184.216.34"))).isTrue();
        assertThat(EgressPolicy.PUBLIC_ONLY.allows(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }

    // --- serve ---

    @Test
    void onlyOneServeRegistrationPerJobAndOnlyForServices() throws Exception {
        NodeHostApis host = host("acme", false);
        Registration first = await(host.http().serve(exchange -> { }));

        assertHostError(host.http().serve(exchange -> { }).map(Object.class::cast), "Error", "already_serving");
        await(first.cancel());
        assertThat(serve.handler("job-1")).isNull();

        NodeHostApis job = new NodeHostApis(new NodeHostApis.Node(vertx, kv, new FetchClient(vertx, EgressPolicy.PUBLIC_ONLY), serve), "acme", "job-2", false);
        assertHostError(job.http().serve(exchange -> { }).map(Object.class::cast), "Error", "not_a_service");
    }

    private static void assertHostError(Future<?> future, String name, String code) {
        assertThatThrownBy(() -> await(future))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(HostException.class)
                .satisfies(e -> {
                    assertThat(((HostException) e).errorName()).as(e.getMessage()).isEqualTo(name);
                    assertThat(((HostException) e).code()).as(e.getMessage()).isEqualTo(code);
                });
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
