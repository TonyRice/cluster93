package automately.core.host;

import automately.core.host.egress.ClusterAddresses;
import automately.core.host.egress.Egress;
import automately.core.host.egress.EgressPolicies;
import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingHttp2Session;
import automately.core.runtime.OutgoingHttp2Stream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scripts' HTTP/2 sessions on the host (D-117): over TLS (ALPN) and h2c, streams, trailers, resets, ping, settings. */
class Http2ClientTest {

    private Vertx vertx;
    private Http2Client client;
    private HttpServer secure;
    private HttpServer clear;
    private HttpServer onlyHttp1;
    private String ca;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        Egress egress = new Egress(vertx, true, new ClusterAddresses(vertx, null, Set.of()), EgressPolicies.NONE);
        client = new Http2Client(vertx, egress, new SocketClient(vertx, egress));
        SelfSignedCertificate certificate = SelfSignedCertificate.create("localhost");
        ca = Files.readString(Path.of(certificate.certificatePath()));
        io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> handler = req -> req.body().onSuccess(body -> {
            switch (req.path()) {
                case "/reset" -> req.response().reset(7);
                case "/trailers" -> {
                    req.response().setChunked(true).putTrailer("x-checksum", "abc").write("with trailers");
                    req.response().end();
                }
                default -> req.response().putHeader("content-type", "text/plain").putHeader("x-version", req.version().name())
                        .end(req.method().name() + " " + req.path() + " " + body.toString());
            }
        });
        secure = await(vertx.createHttpServer(new HttpServerOptions().setSsl(true).setUseAlpn(true).setKeyCertOptions(certificate.keyCertOptions()))
                .requestHandler(handler).listen(0, "127.0.0.1"));
        clear = await(vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(true)).requestHandler(handler).listen(0, "127.0.0.1"));
        onlyHttp1 = await(vertx.createHttpServer(new HttpServerOptions().setSsl(true).setUseAlpn(true)
                .setAlpnVersions(List.of(HttpVersion.HTTP_1_1)).setKeyCertOptions(certificate.keyCertOptions()))
                .requestHandler(req -> req.response().end("http/1.1")).listen(0, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private OutgoingHttp2Session open(String authority, JsonObject tls) throws Exception {
        return await(client.connect("acme", new JsonObject().put("authority", authority).put("tls", tls), new AtomicInteger(), () -> () -> { }));
    }

    @Test
    void streamsGoOverOneVerifiedTlsConnection() throws Exception {
        OutgoingHttp2Session session = open("https://localhost:" + secure.actualPort(), new JsonObject().put("ca", ca));
        assertThat(session.info().getJsonObject("tls").getString("alpnProtocol")).isEqualTo("h2");
        assertThat(session.info().getJsonObject("tls").getBoolean("authorized")).isTrue();
        assertThat(session.remoteSettings().getBoolean("enablePush")).isNotNull();

        OutgoingHttp2Stream get = await(session.request(new JsonObject().put(":path", "/hello"), true));
        OutgoingHttp2Stream post = await(session.request(new JsonObject().put(":method", "POST").put(":path", "/echo").put("x-a", "1"), false));
        await(post.write(Buffer.buffer("part one ")));
        await(post.end());
        assertThat(get.id()).isNotEqualTo(post.id());
        JsonObject head = await(get.response());
        assertThat(head.getInteger("status")).isEqualTo(200);
        assertThat(head.getJsonArray("rawHeaders").getString(0)).isEqualTo(":status");
        assertThat(read(get.body())).isEqualTo("GET /hello ");
        assertThat(await(post.response()).getJsonArray("rawHeaders").encode()).contains("\"x-version\",\"HTTP_2\"");
        assertThat(read(post.body())).isEqualTo("POST /echo part one ");
    }

    @Test
    void h2cTrailersResetsPingAndSettings() throws Exception {
        OutgoingHttp2Session session = open("http://127.0.0.1:" + clear.actualPort(), new JsonObject());
        assertThat(session.info().containsKey("tls")).isFalse();

        OutgoingHttp2Stream withTrailers = await(session.request(new JsonObject().put(":path", "/trailers"), true));
        await(withTrailers.response());
        assertThat(read(withTrailers.body())).isEqualTo("with trailers");
        assertThat(await(withTrailers.trailers()).getJsonArray("rawTrailers").encode()).isEqualTo("[\"x-checksum\",\"abc\"]");

        OutgoingHttp2Stream reset = await(session.request(new JsonObject().put(":path", "/reset"), true));
        assertThatThrownBy(() -> await(reset.response())).cause().isInstanceOf(HostException.class)
                .hasMessage("Stream closed with error code NGHTTP2_REFUSED_STREAM");

        JsonObject pong = await(session.ping(Buffer.buffer("12345678")));
        assertThat(Buffer.buffer(java.util.Base64.getDecoder().decode(pong.getString("payload"))).toString()).isEqualTo("12345678");
        await(session.settings(new JsonObject().put("initialWindowSize", 100_000)));
        assertThatThrownBy(() -> await(session.settings(new JsonObject().put("nope", 1)))).cause().hasMessageContaining("Unknown setting");

        CompletableFuture<String> events = new CompletableFuture<>();
        StringBuilder seen = new StringBuilder();
        session.events().handler(chunk -> seen.append(chunk.toString(StandardCharsets.UTF_8)).append('\n'), () -> events.complete(seen.toString()),
                events::completeExceptionally);
        session.events().resume();
        await(session.close());
        assertThat(events.get(10, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void anUntrustedChainOrAServerWithoutHttp2IsRefused() {
        assertThatThrownBy(() -> open("https://localhost:" + secure.actualPort(), new JsonObject())).cause()
                .isInstanceOf(HostException.class).hasMessageStartingWith("self-signed certificate");
        assertThatThrownBy(() -> open("https://localhost:" + onlyHttp1.actualPort(), new JsonObject().put("ca", ca))).cause()
                .extracting(e -> ((HostException) e).code()).isEqualTo("ERR_SSL_TLSV1_ALERT_NO_APPLICATION_PROTOCOL");
        assertThatThrownBy(() -> open("ftp://localhost:1", new JsonObject())).cause().hasMessageContaining("unsupported");
    }

    @Test
    void aSessionIsASocketAndAStreamIsARequest() throws Exception {
        AtomicInteger script = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        OutgoingHttp2Session session = await(client.connect("acme", new JsonObject().put("authority", "http://127.0.0.1:" + clear.actualPort()),
                script, () -> {
                    if (requests.incrementAndGet() > 1) {
                        requests.decrementAndGet();
                        return null;
                    }
                    return requests::decrementAndGet;
                }));
        assertThat(script.get()).isEqualTo(1);
        OutgoingHttp2Stream first = await(session.request(new JsonObject().put(":path", "/a"), true));
        assertThatThrownBy(() -> await(session.request(new JsonObject().put(":path", "/b"), true))).hasMessageContaining("Too many concurrent requests");
        await(first.response());
        read(first.body());
        await(first.trailers());
        assertThat(requests.get()).isZero();
        session.destroy();
        assertThat(script.get()).isZero();
    }

    private static String read(BodyStream body) throws Exception {
        CompletableFuture<String> done = new CompletableFuture<>();
        Buffer all = Buffer.buffer();
        body.handler(all::appendBuffer, () -> done.complete(all.toString()), done::completeExceptionally);
        body.resume();
        return done.get(10, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}
