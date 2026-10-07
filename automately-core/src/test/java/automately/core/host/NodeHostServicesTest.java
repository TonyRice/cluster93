package automately.core.host;

import automately.core.runtime.HostException;
import automately.core.runtime.HostServices;
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

class NodeHostServicesTest {

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

    private NodeHostServices host(String tenant, boolean allowPrivate) {
        return new NodeHostServices(vertx, tenant, "job-1", true, kv, new FetchClient(vertx, new EgressPolicy(allowPrivate)), serve);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.actualPort() + path;
    }

    // --- kv through the host API ---

    @Test
    void kvOperationsAreScopedToTheTenant() throws Exception {
        await(host("acme", false).call("kv.set", new JsonObject().put("ns", "default").put("key", "k").put("value", 1)));

        assertThat(await(host("acme", false).call("kv.get", new JsonObject().put("ns", "default").put("key", "k")))).isEqualTo(1);
        assertThat(await(host("globex", false).call("kv.get", new JsonObject().put("ns", "default").put("key", "k")))).isNull();
    }

    @Test
    void kvValidatesArguments() {
        HostServices host = host("acme", false);

        assertHostError(host.call("kv.get", new JsonObject().put("ns", "default")), "TypeError", "invalid_argument");
        assertHostError(host.call("kv.get", new JsonObject().put("ns", "default").put("key", "a\u001fb")), "TypeError", "invalid_argument");
        assertHostError(host.call("kv.get", new JsonObject().put("ns", "default").put("key", "k".repeat(600))), "RangeError", "limit_exceeded");
        assertHostError(host.call("kv.set", new JsonObject().put("ns", "default").put("key", "k")
                .put("value", "v".repeat(NodeHostServices.MAX_VALUE_BYTES + 1))), "RangeError", "limit_exceeded");
        assertHostError(host.call("kv.set", new JsonObject().put("ns", "default").put("key", "k").put("value", 1).put("ttlMs", -5)),
                "TypeError", "invalid_argument");
        assertHostError(host.call("kv.list", new JsonObject().put("ns", "default").put("prefix", "").put("limit", 5000)),
                "TypeError", "invalid_argument");
        assertHostError(host.call("nope", new JsonObject()), "Error", "unknown_operation");
    }

    // --- the tenant quota covers kv and files together (D-043) ---

    private NodeHostServices quotaHost(automately.core.files.FileStore files, automately.core.storage.TenantQuota quota) {
        return new NodeHostServices(vertx, "acme", "job-q", false, kv, new FetchClient(vertx, EgressPolicy.PUBLIC_ONLY), serve, files,
                automately.core.files.StorageCapacity.UNLIMITED, quota);
    }

    private static JsonObject entry(String key, Object value) {
        return new JsonObject().put("ns", "default").put("key", key).put("value", value);
    }

    @Test
    void kvAndFilesShareTheTenantQuota() throws Exception {
        automately.core.files.FileStore files = new automately.core.files.SharedDataFileStore(vertx, automately.core.files.StorageCapacity.UNLIMITED);
        automately.core.storage.TenantQuota quota = new automately.core.storage.TenantQuota(1_000,
                t -> files.usage(t).compose(f -> kv.usage(t).map(k -> f + k)));
        NodeHostServices host = quotaHost(files, quota);
        String big = "x".repeat(600);   // an entry of 7 + 1 + 602 = 610 bytes

        await(host.call("kv.set", entry("a", big)));
        assertHostError(host.call("kv.set", entry("b", big)), "RangeError", "limit_exceeded");

        // Full or not, a tenant can shrink and regrow what it has, as long as the result fits.
        await(host.call("kv.set", entry("a", "x".repeat(300))));
        await(host.call("kv.set", entry("a", big)));
        assertThat(await(kv.usage("acme"))).isEqualTo(610);

        // Files get what kv leaves: 390 bytes.
        automately.core.files.FileWriter tooBig = await(host.createFile(new JsonObject().put("path", "big.bin")));
        assertThatThrownBy(() -> await(tooBig.write(io.vertx.core.buffer.Buffer.buffer(new byte[391]))))
                .isInstanceOf(ExecutionException.class).cause().hasMessageContaining("quota");
        automately.core.files.FileWriter fits = await(host.createFile(new JsonObject().put("path", "fits.bin")));
        await(fits.write(io.vertx.core.buffer.Buffer.buffer(new byte[300])));
        await(fits.finish());

        // And kv gets what files leave: 90 bytes.
        assertHostError(host.call("kv.set", entry("c", "y".repeat(100))), "RangeError", "limit_exceeded");
        await(host.call("kv.set", entry("c", "y".repeat(50))));
        assertHostError(host.call("kv.increment", new JsonObject().put("ns", "default").put("key", "visits").put("by", 1)),
                "RangeError", "limit_exceeded");

        // Deleting makes room again.
        await(host.call("kv.delete", new JsonObject().put("ns", "default").put("key", "a")));
        await(host.call("kv.increment", new JsonObject().put("ns", "default").put("key", "visits").put("by", 1)));
    }

    // --- bus ---

    @Test
    void publishReachesSubscribersOfTheSameTenantOnly() throws Exception {
        List<Object> acme = new CopyOnWriteArrayList<>();
        List<Object> globex = new CopyOnWriteArrayList<>();
        await(host("acme", false).listen("bus.subscribe", new JsonObject().put("topic", "orders"), in -> acme.add(in.payload())));
        await(host("globex", false).listen("bus.subscribe", new JsonObject().put("topic", "orders"), in -> globex.add(in.payload())));

        await(host("acme", false).call("bus.publish", new JsonObject().put("topic", "orders").put("message", new JsonObject().put("id", 1))));
        Thread.sleep(200);

        assertThat(acme).containsExactly(new JsonObject().put("topic", "orders").put("message", new JsonObject().put("id", 1)));
        assertThat(globex).isEmpty();
    }

    @Test
    void requestsAreAnsweredByAHandler() throws Exception {
        await(host("acme", false).listen("bus.handle", new JsonObject().put("topic", "math"), in -> {
            JsonObject message = ((JsonObject) in.payload()).getJsonObject("message");
            in.reply(message.getInteger("a") + message.getInteger("b"));
        }));

        Object answer = await(host("acme", false).call("bus.request", new JsonObject().put("topic", "math")
                .put("message", new JsonObject().put("a", 2).put("b", 3))));

        assertThat(answer).isEqualTo(5);
        assertHostError(host("globex", false).call("bus.request", new JsonObject().put("topic", "math").put("message", 1)),
                "Error", "no_handler");
    }

    @Test
    void requestFailuresAndTimeoutsAreTyped() throws Exception {
        await(host("acme", false).listen("bus.handle", new JsonObject().put("topic", "fails"), in -> in.fail(500, "handler broke")));
        await(host("acme", false).listen("bus.handle", new JsonObject().put("topic", "silent"), in -> { }));

        assertHostError(host("acme", false).call("bus.request", new JsonObject().put("topic", "fails").put("message", 1)),
                "Error", "handler_failed");
        assertHostError(host("acme", false).call("bus.request", new JsonObject().put("topic", "silent").put("message", 1).put("timeoutMs", 200)),
                "TimeoutError", "timeout");
    }

    @Test
    void closingStopsListeners() throws Exception {
        List<Object> seen = new CopyOnWriteArrayList<>();
        NodeHostServices host = host("acme", false);
        await(host.listen("bus.subscribe", new JsonObject().put("topic", "t"), in -> seen.add(in.payload())));
        await(host.close());

        await(host("acme", false).call("bus.publish", new JsonObject().put("topic", "t").put("message", 1)));
        Thread.sleep(200);

        assertThat(seen).isEmpty();
    }

    @Test
    void topicsAreValidated() {
        assertHostError(host("acme", false).call("bus.publish", new JsonObject().put("topic", "has space").put("message", 1)),
                "TypeError", "invalid_argument");
    }

    // --- fetch ---

    /** Reads a fetched body to the end. */
    private static String readAll(automately.core.runtime.FetchedResponse response) throws Exception {
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
        io.vertx.core.buffer.Buffer all = io.vertx.core.buffer.Buffer.buffer();
        response.body().handler(all::appendBuffer, () -> done.complete(all.toString()), done::completeExceptionally);
        response.body().resume();
        return done.get(30, TimeUnit.SECONDS);
    }

    private automately.core.runtime.FetchedResponse fetch(NodeHostServices host, JsonObject args) throws Exception {
        return await(host.fetch(args));
    }

    @Test
    void fetchReturnsStatusHeadersAndAStreamingBody() throws Exception {
        automately.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/json")));

        assertThat(response.meta().getInteger("status")).isEqualTo(200);
        assertThat(response.meta().getJsonObject("headers").getString("content-type")).isEqualTo("application/json");
        assertThat(new JsonObject(readAll(response))).isEqualTo(new JsonObject().put("hello", "world"));
    }

    @Test
    void fetchSendsMethodHeadersAndBody() throws Exception {
        automately.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/echo"))
                .put("method", "POST").put("headers", new JsonObject().put("x-custom", "yes").put("host", "evil.example"))
                .put("body", "payload"));

        assertThat(readAll(response)).isEqualTo("yes|payload");
        assertThat(response.meta().getJsonObject("headers").getString("x-method")).isEqualTo("POST");
    }

    @Test
    void fetchDoesNotFollowRedirects() throws Exception {
        automately.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/redirect")));

        assertThat(response.meta().getInteger("status")).isEqualTo(302);
        response.body().cancel();
    }

    @Test
    void fetchStreamsLargeBodiesWithoutALimit() throws Exception {
        automately.core.runtime.FetchedResponse response = fetch(host("acme", true), new JsonObject().put("url", url("/big")));

        assertThat(readAll(response)).hasSize(320 * 64 * 1024);
    }

    @Test
    void fetchTimesOut() {
        assertHostError(host("acme", true).fetch(new JsonObject().put("url", url("/slow")).put("timeoutMs", 300)), "TimeoutError", "timeout");
    }

    @Test
    void concurrentFetchesAreLimitedUntilBodiesAreDone() throws Exception {
        NodeHostServices host = host("acme", true);
        List<automately.core.runtime.FetchedResponse> open = new java.util.ArrayList<>();
        for (int i = 0; i < NodeHostServices.MAX_CONCURRENT_FETCHES; i++) {
            open.add(fetch(host, new JsonObject().put("url", url("/json"))));
        }

        assertHostError(host.fetch(new JsonObject().put("url", url("/json"))), "RangeError", "limit_exceeded");
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
        assertHostError(host("acme", false).fetch(new JsonObject().put("url", target)), "TypeError", "egress_denied");
    }

    @Test
    void onlyHttpSchemesAreAllowed() {
        assertHostError(host("acme", true).fetch(new JsonObject().put("url", "file:///etc/passwd")), "TypeError", "invalid_argument");
        assertHostError(host("acme", true).fetch(new JsonObject().put("url", "ftp://example.com/")), "TypeError", "invalid_argument");
    }

    @Test
    void publicAddressesPassThePolicy() throws Exception {
        assertThat(EgressPolicy.PUBLIC_ONLY.allows(InetAddress.getByName("93.184.216.34"))).isTrue();
        assertThat(EgressPolicy.PUBLIC_ONLY.allows(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }

    // --- serve ---

    @Test
    void onlyOneServeRegistrationPerJobAndOnlyForServices() throws Exception {
        NodeHostServices host = host("acme", false);
        HostServices.Registration first = await(host.serve(exchange -> { }));

        assertHostError(host.serve(exchange -> { }).map(Object.class::cast), "Error", "already_serving");
        await(first.cancel());
        assertThat(serve.handler("job-1")).isNull();

        NodeHostServices job = new NodeHostServices(vertx, "acme", "job-2", false, kv, new FetchClient(vertx, EgressPolicy.PUBLIC_ONLY), serve);
        assertHostError(job.serve(exchange -> { }).map(Object.class::cast), "Error", "not_a_service");
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
