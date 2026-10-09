package automately.core.host;

import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingRequest;
import automately.core.runtime.api.FetchApi;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requests whose body streams, for node:http and node:https (D-109): framing, TLS options, limits and release. */
class StreamedRequestTest {

    private Vertx vertx;
    private HttpServer plain;
    private HttpServer tls;
    private SelfSignedCertificate certificate;
    private FetchApi fetch;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        plain = await(vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(body -> {
            req.response().headers().add("set-cookie", "a=1").add("set-cookie", "b=2");
            req.response().end(new JsonObject().put("method", req.method().name()).put("te", req.getHeader("transfer-encoding"))
                    .put("length", req.getHeader("content-length")).put("host", req.getHeader("host"))
                    .put("body", body.toString()).encode());
        })).listen(0, "127.0.0.1"));
        certificate = SelfSignedCertificate.create("localhost");
        tls = await(vertx.createHttpServer(new HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()))
                .requestHandler(req -> req.response().end("secure")).listen(0, "127.0.0.1"));
        // Loopback is reachable only with the development switch; a standalone gate lists no node ports.
        fetch = new NodeHostApis(new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(true)),
                new ServeRegistry()), "acme", "job-1", false).fetch();
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void aChunkedBodyStreamsAndTheResponseKeepsRepeatedHeaders() throws Exception {
        OutgoingRequest request = await(fetch.open(new JsonObject().put("url", "http://127.0.0.1:" + plain.actualPort() + "/up")
                .put("method", "POST").put("chunked", true).put("headers", new JsonObject().put("x-list", new JsonArray().add("1").add("2")))));
        await(request.write(Buffer.buffer("one ")));
        await(request.write(Buffer.buffer("two")));
        await(request.end());
        FetchedResponse response = await(request.response());

        JsonObject echoed = new JsonObject(read(response));
        assertThat(echoed.getString("te")).isEqualTo("chunked");
        assertThat(echoed.getString("body")).isEqualTo("one two");
        assertThat(echoed.getString("host")).isEqualTo("127.0.0.1:" + plain.actualPort());
        JsonArray raw = response.meta().getJsonArray("rawHeaders");
        List<String> cookies = new ArrayList<>();
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            if (raw.getString(i).equalsIgnoreCase("set-cookie")) {
                cookies.add(raw.getString(i + 1));
            }
        }
        assertThat(cookies).containsExactly("a=1", "b=2");
    }

    @Test
    void aContentLengthIsSentInsteadOfChunks() throws Exception {
        OutgoingRequest request = await(fetch.open(new JsonObject().put("url", "http://127.0.0.1:" + plain.actualPort() + "/")
                .put("method", "PUT").put("chunked", true).put("headers", new JsonObject().put("Content-Length", "6"))));
        await(request.write(Buffer.buffer("abc")));
        await(request.write(Buffer.buffer("def")));
        await(request.end());

        JsonObject echoed = new JsonObject(read(await(request.response())));
        assertThat(echoed.getString("length")).isEqualTo("6");
        assertThat(echoed.getString("te")).isNull();
    }

    @Test
    void certificatesAreVerifiedUnlessTheRequestTrustsItsOwnCaOrOptsOut() throws Exception {
        String url = "https://localhost:" + tls.actualPort() + "/";
        assertThatThrownBy(() -> sendEmpty(new JsonObject().put("url", url)))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(HostException.class);

        String ca = Files.readString(Path.of(certificate.certificatePath()));
        assertThat(read(sendEmpty(new JsonObject().put("url", url).put("tls", new JsonObject().put("ca", ca))))).isEqualTo("secure");
        assertThat(read(sendEmpty(new JsonObject().put("url", url).put("tls", new JsonObject().put("rejectUnauthorized", false)))))
                .isEqualTo("secure");
    }

    @Test
    void invalidRequestsAreRefusedBeforeConnecting() {
        String url = "http://127.0.0.1:" + plain.actualPort() + "/";
        for (JsonObject bad : List.of(
                new JsonObject().put("url", url).put("method", "CONNECT"),
                new JsonObject().put("url", url).put("method", "BAD METHOD"),
                new JsonObject().put("url", url).put("headers", new JsonObject().put("x-a", "line\nbreak")),
                new JsonObject().put("url", url).put("headers", new JsonObject().put("bad name", "v")),
                new JsonObject().put("url", url).put("headers", new JsonObject().put("content-length", "-1")),
                new JsonObject().put("url", "ftp://127.0.0.1/"))) {
            assertThatThrownBy(() -> await(fetch.open(bad))).as(bad.encode()).hasCauseInstanceOf(HostException.class);
        }
    }

    @Test
    void openRequestsShareFetchsLimitAndGiveTheirPlaceBack() throws Exception {
        String url = "http://127.0.0.1:" + plain.actualPort() + "/";
        List<OutgoingRequest> open = new ArrayList<>();
        for (int i = 0; i < FetchApi.MAX_CONCURRENT; i++) {
            open.add(await(fetch.open(new JsonObject().put("url", url).put("method", "POST").put("chunked", true))));
        }
        assertThatThrownBy(() -> await(fetch.open(new JsonObject().put("url", url))))
                .hasCauseInstanceOf(HostException.class).hasMessageContaining("Too many concurrent requests");
        assertThatThrownBy(() -> await(fetch.fetch(new JsonObject().put("url", url))))
                .hasMessageContaining("Too many concurrent fetches");

        // An abort gives its place back, as does a response read to its end.
        open.get(0).abort();
        OutgoingRequest finished = open.get(1);
        await(finished.end());
        read(await(finished.response()));
        long deadline = System.currentTimeMillis() + 5_000;
        List<OutgoingRequest> more = new ArrayList<>();
        while (more.size() < 2 && System.currentTimeMillis() < deadline) {
            try {
                more.add(await(fetch.open(new JsonObject().put("url", url))));
            } catch (ExecutionException e) {
                Thread.sleep(20);
            }
        }
        assertThat(more).hasSize(2);
    }

    private FetchedResponse sendEmpty(JsonObject args) throws Exception {
        OutgoingRequest request = await(fetch.open(args));
        await(request.end());
        return await(request.response());
    }

    private static String read(FetchedResponse response) throws Exception {
        CompletableFuture<String> done = new CompletableFuture<>();
        Buffer all = Buffer.buffer();
        response.body().handler(all::appendBuffer, () -> done.complete(all.toString()), done::completeExceptionally);
        response.body().resume();
        return done.get(10, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
