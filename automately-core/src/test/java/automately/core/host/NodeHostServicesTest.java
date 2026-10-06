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
                case "/huge" -> req.response().end("x".repeat(FetchClient.MAX_RESPONSE_BYTES + 1));
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

    @Test
    void fetchReturnsStatusHeadersAndBody() throws Exception {
        JsonObject response = (JsonObject) await(host("acme", true).call("http.fetch", new JsonObject().put("url", url("/json"))));

        assertThat(response.getInteger("status")).isEqualTo(200);
        assertThat(response.getJsonObject("headers").getString("content-type")).isEqualTo("application/json");
        assertThat(new JsonObject(response.getString("body"))).isEqualTo(new JsonObject().put("hello", "world"));
    }

    @Test
    void fetchSendsMethodHeadersAndBody() throws Exception {
        JsonObject response = (JsonObject) await(host("acme", true).call("http.fetch", new JsonObject().put("url", url("/echo"))
                .put("method", "POST").put("headers", new JsonObject().put("x-custom", "yes").put("host", "evil.example"))
                .put("body", "payload")));

        assertThat(response.getString("body")).isEqualTo("yes|payload");
        assertThat(response.getJsonObject("headers").getString("x-method")).isEqualTo("POST");
    }

    @Test
    void fetchDoesNotFollowRedirects() throws Exception {
        JsonObject response = (JsonObject) await(host("acme", true).call("http.fetch", new JsonObject().put("url", url("/redirect"))));

        assertThat(response.getInteger("status")).isEqualTo(302);
    }

    @Test
    void fetchCapsResponseSize() {
        assertHostError(host("acme", true).call("http.fetch", new JsonObject().put("url", url("/huge"))), "RangeError", "limit_exceeded");
    }

    @Test
    void fetchTimesOut() {
        assertHostError(host("acme", true).call("http.fetch", new JsonObject().put("url", url("/slow")).put("timeoutMs", 300)),
                "TimeoutError", "timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/", "http://localhost/", "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.1/",
            "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://[fd00::1]/", "http://0.0.0.0/",
            "http://100.64.0.1/", "http://[::ffff:127.0.0.1]/"})
    void privateAndReservedAddressesAreRefusedByDefault(String target) {
        assertHostError(host("acme", false).call("http.fetch", new JsonObject().put("url", target)), "TypeError", "egress_denied");
    }

    @Test
    void onlyHttpSchemesAreAllowed() {
        assertHostError(host("acme", true).call("http.fetch", new JsonObject().put("url", "file:///etc/passwd")), "TypeError", "invalid_argument");
        assertHostError(host("acme", true).call("http.fetch", new JsonObject().put("url", "ftp://example.com/")), "TypeError", "invalid_argument");
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
        HostServices.Registration first = await(host.listen("http.serve", new JsonObject(), in -> { }));

        assertHostError(host.listen("http.serve", new JsonObject(), in -> { }).map(Object.class::cast), "Error", "already_serving");
        await(first.cancel());
        assertThat(serve.handler("job-1")).isNull();

        NodeHostServices job = new NodeHostServices(vertx, "acme", "job-2", false, kv, new FetchClient(vertx, EgressPolicy.PUBLIC_ONLY), serve);
        assertHostError(job.listen("http.serve", new JsonObject(), in -> { }).map(Object.class::cast), "Error", "not_a_service");
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
