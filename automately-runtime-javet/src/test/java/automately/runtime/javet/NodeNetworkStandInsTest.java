package automately.runtime.javet;

import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.NodeHostApis;
import automately.core.host.ServeRegistry;
import automately.core.host.SharedDataKvStore;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.StateSink;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * node:http and the network modules' pure parts in the isolate (D-109), in every build: against a local server, through
 * the real host APIs (a standalone gate with the development switch, since the server is on loopback). Their results
 * were checked against real Node by RealNpmPackagesTest's httpclient fixture.
 */
class NodeNetworkStandInsTest {

    private static Vertx vertx;
    private static JavetScriptRuntime runtime;
    private static HttpServer server;
    private static NodeHostApis.Node node;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        runtime = new JavetScriptRuntime(vertx, 64, 2);
        server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(body -> {
            if (req.path().equals("/slow")) {
                vertx.setTimer(2_000, id -> req.response().end("late"));
                return;
            }
            req.response().headers().add("set-cookie", "a=1").add("set-cookie", "b=2").add("x-repeat", "one").add("x-repeat", "two");
            req.response().end(new JsonObject().put("method", req.method().name()).put("te", req.getHeader("transfer-encoding"))
                    .put("length", req.getHeader("content-length")).put("body", body.toString()).encode());
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(true)), new ServeRegistry());
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aRequestAndItsResponseAsInNode() throws Exception {
        JsonArray out = run("""
                import { request } from 'node:http';
                const base = 'http://127.0.0.1:%d';
                const call = (path, options, write) => new Promise((resolve) => {
                  const events = [];
                  const req = request(base + path, options, (res) => {
                    events.push('response');
                    let text = '';
                    res.setEncoding('utf8');
                    res.on('data', (c) => (text += c));
                    res.on('end', () => resolve({ events, status: res.statusCode, cookies: res.headers['set-cookie'],
                      repeat: res.headers['x-repeat'], echo: JSON.parse(text) }));
                  });
                  req.on('socket', () => events.push('socket'));
                  req.on('finish', () => events.push('finish'));
                  req.on('error', (e) => resolve({ error: e.code }));
                  write(req);
                });
                export default async () => [
                  await call('/a', {}, (req) => req.end()),
                  await call('/b', { method: 'POST' }, (req) => req.end('whole')),
                  await call('/c', { method: 'POST' }, (req) => { req.write('one '); setTimeout(() => req.end('two'), 10); }),
                ];
                """.formatted(server.actualPort()));

        JsonObject get = out.getJsonObject(0);
        assertThat(get.getJsonArray("events")).containsExactly("socket", "finish", "response");
        assertThat(get.getJsonArray("cookies")).containsExactly("a=1", "b=2");
        assertThat(get.getString("repeat")).isEqualTo("one, two");
        assertThat(out.getJsonObject(1).getJsonObject("echo").getString("length")).isEqualTo("5");
        JsonObject streamed = out.getJsonObject(2);
        assertThat(streamed.getJsonArray("events")).containsExactly("socket", "finish", "response");
        assertThat(streamed.getJsonObject("echo").getString("te")).isEqualTo("chunked");
        assertThat(streamed.getJsonObject("echo").getString("body")).isEqualTo("one two");
    }

    @Test
    void failuresHaveNodesCodes() throws Exception {
        JsonArray out = run("""
                import http from 'node:http';
                const fails = (url, after) => new Promise((resolve) => {
                  const req = http.get(url, () => resolve('answered'));
                  req.on('error', (e) => resolve(e.code));
                  if (after) after(req);
                });
                export default async () => [
                  await fails('http://127.0.0.1:9/'),
                  await fails('http://127.0.0.1:%d/slow', (req) => setTimeout(() => req.destroy(), 30)),
                  (() => { try { http.request('https://example.com/'); } catch (e) { return e.code; } })(),
                  (() => { try { http.request({ host: 'x', socketPath: '/var/run/docker.sock' }); } catch (e) { return e.code; } })(),
                ];
                """.formatted(server.actualPort()));

        assertThat(out).containsExactly("ECONNREFUSED", "ECONNRESET", "ERR_INVALID_PROTOCOL", "ERR_NOT_SUPPORTED");
    }

    @Test
    void theOtherNetworkModulesHaveTheirPureParts() throws Exception {
        JsonArray out = run("""
                import net from 'node:net';
                import tls from 'node:tls';
                import http2 from 'node:http2';
                import tty from 'node:tty';
                const code = (fn) => { try { fn(); return 'ran'; } catch (e) { return e.code; } };
                export default () => [
                  net.isIP('10.0.0.1'), net.isIP('::1'), net.isIP('01.1.1.1'), net.isIPv6('fe80::1%eth0'),
                  code(() => net.connect(80, 'example.com')), code(() => tls.connect(443, 'example.com')),
                  http2.constants.HTTP2_HEADER_STATUS, code(() => http2.connect('https://example.com')),
                  tty.isatty(1), tls.DEFAULT_MIN_VERSION,
                ];
                """);

        assertThat(out).containsExactly(4, 6, 0, true, "ERR_NOT_SUPPORTED", "ERR_NOT_SUPPORTED", ":status", "ERR_NOT_SUPPORTED", false, "TLSv1.2");
    }

    @Test
    void fsStreamsAreNodeStreams() throws Exception {
        JsonArray out = run("""
                import { createReadStream, createWriteStream } from 'node:fs';
                import { pipeline } from 'node:stream/promises';
                import { Readable } from 'node:stream';
                export default async () => {
                  await pipeline(Readable.from(['alpha,', 'beta,', 'gamma']), createWriteStream('/tmp/s.txt'));
                  const parts = [];
                  for await (const c of createReadStream('/tmp/s.txt', { start: 2, end: 9, highWaterMark: 3 })) parts.push(c.toString());
                  let missing;
                  try { for await (const c of createReadStream('/tmp/nope.txt')) parts.push(c); } catch (e) { missing = e.code; }
                  return [parts.join('|'), missing];
                };
                """);

        assertThat(out).containsExactly("pha|,be|ta", "ENOENT");
    }

    private static JsonArray run(String script) throws Exception {
        Execution execution = new Execution("job-1", "tenant-a", script, new JsonObject(), Duration.ofSeconds(10),
                JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        ExecutionResult result = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD,
                        NodeHostApis.factory(node).create("tenant-a", "job-1", false))
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(result.error()).isNull();
        return (JsonArray) result.output();
    }
}
