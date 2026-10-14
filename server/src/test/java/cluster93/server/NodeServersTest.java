package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Node's http.Server in a service (D-112, D-123), through the real ingress: a plain app.js with no default export,
 * servers on several ports reached by hostname, a path prefix, a port kept off the network, and the request and
 * response as Node's. The frameworks themselves are RealNpmPackagesTest's.
 */
class NodeServersTest {

    private static final String TENANT = "srv-tenant";

    private static final String APP = """
            import http from 'node:http';
            import https from 'node:https';

            const seen = [];
            const main = http.createServer((req, res) => {
              if (req.url === '/inspect') {
                res.setHeader('Content-Type', 'application/json');
                res.end(JSON.stringify({
                  method: req.method, url: req.url, httpVersion: req.httpVersion, custom: req.headers['x-custom'],
                  cookie: req.headers.cookie, rawHeaders: req.rawHeaders, remote: req.socket.remoteAddress,
                  family: req.socket.remoteFamily, encrypted: req.socket.encrypted ?? false, local: req.socket.localPort,
                }));
              } else if (req.url === '/cookies') {
                res.setHeader('Set-Cookie', ['a=1', 'b=2']);
                res.writeHead(201, 'Made It', { 'X-Extra': 'yes' });
                res.end('made');
              } else if (req.url === '/upload') {
                let bytes = 0;
                req.on('data', (c) => { bytes += c.length; });
                req.on('end', () => res.end(String(bytes)));
              } else if (req.url === '/trailers') {
                res.writeHead(200, { 'Trailer': 'X-Checksum' });
                res.write('part');
                res.addTrailers({ 'X-Checksum': 'abc' });
                res.end();
              } else if (req.url === '/slow') {
                res.write('start\\n');
                res.on('close', () => seen.push('closed ' + res.writableFinished));
              } else if (req.url === '/seen') {
                res.end(JSON.stringify(seen));
              } else {
                res.statusCode = 404;
                res.end('nope');
              }
            });
            main.listen(3000, () => seen.push('listening ' + main.address().port));

            const admin = http.createServer((req, res) => {
              res.end(JSON.stringify({ server: 'admin', url: req.url, prefix: req.headers['x-forwarded-prefix'] ?? null }));
            });
            admin.listen(4000);

            const hidden = http.createServer((req, res) => res.end('hidden'));
            hidden.listen(5000);

            const clash = http.createServer();
            clash.on('error', (e) => seen.push([e.code, e.syscall, e.port, e.message].join(' ')));
            clash.listen(4000);

            const anywhere = https.createServer({ key: 'k', cert: 'c' }, (req, res) => res.end('random port'));
            anywhere.listen(0, () => seen.push('random ' + (anywhere.address().port >= 1024)));

            process.on('SIGTERM', () => {
              seen.push('sigterm');
              for (const s of [main, admin, hidden, anywhere]) s.close();
            });
            """;

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    private static HttpClient raw;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("C93_NODE_ID", "servers"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_INGRESS_DOMAIN", "apps.test"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_HEAP_MB", "128"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                Map.entry("C93_STOP_GRACE_MS", "5000"))))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        raw = clientVertx.createHttpClient();
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
    }

    @Test
    void aPlainNodeAppServesOnItsPortsThroughTheIngress() throws Exception {
        HttpResponse<Buffer> created = await(client.put(node.httpPort(), "localhost", "/v1/services/app").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", APP)
                        .put("ports", new JsonArray().add(new JsonObject().put("port", 4000).put("path", "/admin"))
                                .add(new JsonObject().put("port", 5000).put("expose", false)))));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        JsonObject service = awaitJson("/v1/services/app", s -> "RUNNING".equals(s.getString("status")));
        assertThat(service.getJsonArray("routes").encode()).contains("\"host\":\"app--srv-tenant--4000.apps.test\"", "\"path\":\"/admin\"", "\"exposed\":false");
        String main = "app--" + TENANT + ".apps.test";

        // The main host is the first server's; the request arrives as the client sent it.
        Reply inspected = retry(() -> send(main, HttpMethod.GET, "/inspect", MultiMap.caseInsensitiveMultiMap()
                .add("X-Custom", "value").add("Cookie", "a=1").add("Cookie", "b=2").add("X-Forwarded-Host", "evil.example"), null));
        JsonObject inspect = inspected.bodyAsJsonObject();
        // Ended in one go: Node sends the length.
        assertThat(inspected.headers.get("Content-Length")).isEqualTo(String.valueOf(inspected.body.length()));
        assertThat(inspect.getString("url")).isEqualTo("/inspect");
        assertThat(inspect.getString("httpVersion")).isEqualTo("1.1");
        assertThat(inspect.getString("custom")).isEqualTo("value");
        assertThat(inspect.getString("cookie")).isEqualTo("a=1; b=2");
        // The client's own headers in its order and spelling (Host first, as it sent it), then the proxy's X-Forwarded-*.
        assertThat(inspect.getJsonArray("rawHeaders").getList()).startsWith("Host", main).containsSubsequence("X-Custom", "value")
                .containsSubsequence("X-Forwarded-For", "127.0.0.1").containsSubsequence("X-Forwarded-Host", main)
                // A client's own X-Forwarded-* never reach the service.
                .doesNotContain("evil.example");
        assertThat(inspect.getString("remote")).isEqualTo("127.0.0.1");
        assertThat(inspect.getString("family")).isEqualTo("IPv4");
        assertThat(inspect.getBoolean("encrypted")).isFalse();
        assertThat(inspect.getInteger("local")).isEqualTo(3000);

        // Repeated headers and a reason phrase of its own; after writeHead without a length, the body is chunked, as Node's.
        Reply cookies = send(main, HttpMethod.GET, "/cookies", MultiMap.caseInsensitiveMultiMap(), null);
        assertThat(cookies.status).isEqualTo(201);
        assertThat(cookies.message).isEqualTo("Made It");
        assertThat(cookies.headers.getAll("Set-Cookie")).containsExactly("a=1", "b=2");
        assertThat(cookies.headers.get("X-Extra")).isEqualTo("yes");
        assertThat(cookies.headers.get("Content-Length")).isNull();
        assertThat(cookies.headers.get("Transfer-Encoding")).isEqualTo("chunked");
        assertThat(cookies.headers.get("Date")).isNotNull();
        assertThat(cookies.body).isEqualTo("made");
        // HEAD: no body.
        Reply head = send(main, HttpMethod.HEAD, "/cookies", MultiMap.caseInsensitiveMultiMap(), null);
        assertThat(head.status).isEqualTo(201);
        assertThat(head.body).isEmpty();
        // A body streams in; with Expect: 100-continue it's answered at once.
        assertThat(send(main, HttpMethod.POST, "/upload", MultiMap.caseInsensitiveMultiMap().add("Expect", "100-continue"),
                Buffer.buffer(new byte[300_000])).body).isEqualTo("300000");
        // Trailers reach the client.
        Reply trailers = send(main, HttpMethod.GET, "/trailers", MultiMap.caseInsensitiveMultiMap(), null);
        assertThat(trailers.body).isEqualTo("part");
        assertThat(trailers.trailers.get("X-Checksum")).isEqualTo("abc");
        assertThat(send(main, HttpMethod.GET, "/missing", MultiMap.caseInsensitiveMultiMap(), null).status).isEqualTo(404);

        // Each other port has its own host; a declared path is served under the main host with its prefix taken off.
        assertThat(send("app--" + TENANT + "--4000.apps.test", HttpMethod.GET, "/x", MultiMap.caseInsensitiveMultiMap(), null).body)
                .isEqualTo("{\"server\":\"admin\",\"url\":\"/x\",\"prefix\":null}");
        assertThat(send(main, HttpMethod.GET, "/admin/users?q=1", MultiMap.caseInsensitiveMultiMap().add("X-Forwarded-Prefix", "/spoofed"), null).body)
                .isEqualTo("{\"server\":\"admin\",\"url\":\"/users?q=1\",\"prefix\":\"/admin\"}");
        // A port kept off the network isn't there, whatever the host; a port nothing listens on is unavailable.
        assertThat(send("app--" + TENANT + "--5000.apps.test", HttpMethod.GET, "/", MultiMap.caseInsensitiveMultiMap(), null).status).isEqualTo(404);
        assertThat(send("app--" + TENANT + "--6000.apps.test", HttpMethod.GET, "/", MultiMap.caseInsensitiveMultiMap(), null).status).isEqualTo(503);

        // A client that leaves mid-response: the response closes without finishing.
        HttpClientResponse slow = await(raw.request(options(main, HttpMethod.GET, "/slow")).compose(r -> r.send()));
        slow.pause();
        slow.request().connection().close();
        long deadline = System.currentTimeMillis() + 5_000;
        String seen;
        do {
            Thread.sleep(50);
            seen = send(main, HttpMethod.GET, "/seen", MultiMap.caseInsensitiveMultiMap(), null).body;
        } while (!seen.contains("closed false") && System.currentTimeMillis() < deadline);
        assertThat(seen).contains("listening 3000", "random true", "closed false",
                "EADDRINUSE listen 4000 listen EADDRINUSE: address already in use :::4000");

        // Stopping it: SIGTERM, the servers close, and the run ends without waiting out the grace period.
        String job = service.getString("currentJobId");
        long stopping = System.nanoTime();
        await(client.post(node.httpPort(), "localhost", "/v1/services/app/stop").putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        awaitJson("/v1/services/app", s -> "STOPPED".equals(s.getString("status")));
        assertThat(Duration.ofNanos(System.nanoTime() - stopping)).isLessThan(Duration.ofSeconds(4));
        // A stopped run is CANCELLED, as every service's is; it ended because its servers closed, well inside the grace.
        JsonObject ended = awaitJson("/v1/jobs/" + job, j -> !java.util.List.of("QUEUED", "RUNNING").contains(j.getString("status")));
        assertThat(ended.getString("status")).isEqualTo("CANCELLED");
        assertThat(ended.getLong("finishedAt") - ended.getLong("startedAt")).isPositive();
    }

    @Test
    void aJobCantListenAndAServiceNeedsAServerOrADefaultExport() throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", """
                        import http from 'node:http';
                        export default () => new Promise((resolve) => {
                          http.createServer().listen(3000).on('error', (e) => resolve([e.code, e.message]));
                        });
                        """)));
        JsonObject job = awaitJson("/v1/jobs/" + submitted.bodyAsJsonObject().getString("id"),
                j -> !java.util.List.of("QUEUED", "RUNNING").contains(j.getString("status")));
        assertThat(job.getJsonArray("output").getString(0)).isEqualTo("ERR_NOT_SUPPORTED");
        assertThat(job.getJsonArray("output").getString(1)).contains("Only services can listen");

        await(client.put(node.httpPort(), "localhost", "/v1/services/empty").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", "console.log('nothing here');").put("restart", "on-failure")));
        JsonObject failed = awaitJson("/v1/services/empty", s -> s.getJsonObject("lastExit") != null);
        assertThat(failed.getJsonObject("lastExit").getString("error")).contains("export a default function, or start a server with listen");
        await(client.delete(node.httpPort(), "localhost", "/v1/services/empty").putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private record Reply(int status, String message, MultiMap headers, MultiMap trailers, String body) {
        JsonObject bodyAsJsonObject() {
            return new JsonObject(body);
        }
    }

    private static RequestOptions options(String host, HttpMethod method, String path) {
        return new RequestOptions().setMethod(method).setHost("127.0.0.1").setPort(node.ingressPort()).setURI(path).putHeader("Host", host);
    }

    private static Reply send(String host, HttpMethod method, String path, MultiMap headers, Buffer body) throws Exception {
        return await(raw.request(options(host, method, path)).compose(request -> {
            request.headers().addAll(headers);
            Future<HttpClientResponse> sent = body == null ? request.send() : request.send(body);
            return sent.compose(response -> response.body().map(bytes -> new Reply(response.statusCode(), response.statusMessage(),
                    response.headers(), response.trailers(), bytes.toString())));
        }));
    }

    /** The first requests can find the service still starting its servers. */
    private static Reply retry(ThrowingSupplier<Reply> call) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Reply reply = call.get();
        while (reply.status == 503 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            reply = call.get();
        }
        return reply;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static JsonObject awaitJson(String path, Predicate<JsonObject> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<Buffer> response = await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
            if (response.statusCode() == 200) {
                last = response.bodyAsJsonObject();
                if (condition.test(last)) {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting on " + path + ": " + last);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
