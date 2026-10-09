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
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code http2} sessions in the isolate (D-117), in every build, against local HTTP/2 servers through a standalone gate
 * with the development switch. Their results against real Node are checked by RealNpmPackagesTest's http2client fixture.
 */
class NodeHttp2Test {

    private static Vertx vertx;
    private static JavetScriptRuntime runtime;
    private static HttpServer secure;
    private static HttpServer clear;
    private static String ca;
    private static final AtomicInteger connectionsClosed = new AtomicInteger();
    private static NodeHostApis.Node node;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        runtime = new JavetScriptRuntime(vertx, 64, 2);
        SelfSignedCertificate certificate = SelfSignedCertificate.create("localhost");
        ca = Files.readString(Path.of(certificate.certificatePath()));
        io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> handler = req -> req.body().onSuccess(body -> {
            req.connection().closeHandler(v -> connectionsClosed.incrementAndGet());
            switch (req.path()) {
                case "/trailers" -> {
                    req.response().setChunked(true).putTrailer("x-checksum", "abc");
                    req.response().end("with trailers");
                }
                case "/refused" -> req.response().reset(7);
                case "/slow" -> vertx.setTimer(5_000, id -> req.response().end("late"));
                default -> req.response().putHeader("x-version", req.version().name()).end(req.method().name() + " " + req.path() + " " + body);
            }
        });
        secure = vertx.createHttpServer(new HttpServerOptions().setSsl(true).setUseAlpn(true).setKeyCertOptions(certificate.keyCertOptions()))
                .requestHandler(handler).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        clear = vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(true)).requestHandler(handler)
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(true)), new ServeRegistry());
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void streamsTrailersResetsAndPingOverOneSession() throws Exception {
        JsonArray out = run("""
                import http2 from 'node:http2';
                const call = (session, headers, body) => new Promise((resolve) => {
                  const req = session.request(headers);
                  const events = [];
                  let text = '';
                  req.setEncoding('utf8');
                  req.on('response', (h) => events.push('response ' + h[':status'] + ' ' + h['x-version']));
                  req.on('trailers', (t) => events.push('trailers ' + t['x-checksum']));
                  req.on('data', (c) => { text += c; });
                  req.on('error', (e) => events.push('error ' + e.code));
                  req.on('close', () => resolve({ events, text, rstCode: req.rstCode }));
                  if (body !== undefined) req.end(body);
                });
                export default () => new Promise((resolve) => {
                  const session = http2.connect('https://localhost:%d', { ca: %s }, async () => {
                    const out = [session.alpnProtocol];
                    out.push(await call(session, { ':path': '/hello' }));
                    out.push(await call(session, { ':method': 'POST', ':path': '/echo' }, 'body'));
                    out.push(await call(session, { ':path': '/trailers' }));
                    out.push(await call(session, { ':path': '/refused' }));
                    out.push(await new Promise((r) => session.ping(Buffer.from('abcdefgh'), (err, duration, payload) => r(String(payload)))));
                    session.close(() => resolve(out));
                  });
                  session.on('error', (e) => resolve(['error', e.code, e.message]));
                });
                """.formatted(secure.actualPort(), Json.encode(ca)));

        assertThat(out.getString(0)).isEqualTo("h2");
        assertThat(out.getJsonObject(1).getJsonArray("events")).containsExactly("response 200 HTTP_2");
        assertThat(out.getJsonObject(1).getString("text")).isEqualTo("GET /hello ");
        assertThat(out.getJsonObject(2).getString("text")).isEqualTo("POST /echo body");
        assertThat(out.getJsonObject(3).getJsonArray("events")).containsExactly("response 200 undefined", "trailers abc");
        assertThat(out.getJsonObject(4).getJsonArray("events")).containsExactly("error ERR_HTTP2_STREAM_ERROR");
        assertThat(out.getJsonObject(4).getInteger("rstCode")).isEqualTo(7);
        assertThat(out.getString(5)).isEqualTo("abcdefgh");
    }

    @Test
    void requestTrailersStateAndPriorityAsNode26() throws Exception {
        JsonArray out = run("""
                import http2 from 'node:http2';
                import util from 'node:util';
                const warnings = [];
                process.on('warning', (w) => warnings.push([w.name, w.code, w.message]));
                export default () => new Promise((resolve) => {
                  const session = http2.connect('http://127.0.0.1:%d', () => {
                    const out = [Object.keys(session.state).length, session.state.nextStreamID];
                    const req = session.request({ ':method': 'POST', ':path': '/upload' }, { waitForTrailers: true, weight: 99 });
                    const events = [];
                    try { req.sendTrailers({}); } catch (e) { events.push(e.code); }
                    req.priority({ weight: 1 });
                    req.on('wantTrailers', () => {
                      events.push('wantTrailers');
                      try { req.sendTrailers({ ':status': '1' }); } catch (e) { events.push(e.code); }
                      req.sendTrailers({ 'X-Sum': 42 });
                      try { req.sendTrailers({}); } catch (e) { events.push(e.code); }
                    });
                    req.on('finish', () => events.push('finish'));
                    req.on('response', (h, flags) => events.push(['response', flags & 4, req.state.state, req.state.weight]));
                    let text = '';
                    req.setEncoding('utf8');
                    req.on('data', (c) => { text += c; });
                    req.on('close', () => {
                      util.deprecate(() => {}, 'old', 'DEP0999')();
                      out.push(events, text, req.sentTrailers['X-Sum'], JSON.stringify(req.state), session.state.nextStreamID);
                      session.close(() => setTimeout(() => resolve([...out, JSON.stringify(session.state), warnings]), 0));
                    });
                    req.write('a');
                    req.end('b');
                  });
                });
                """.formatted(clear.actualPort()));

        assertThat(out.getInteger(0)).isEqualTo(9);
        assertThat(out.getInteger(1)).isEqualTo(1);
        assertThat(out.getJsonArray(2).encode()).isEqualTo(
                "[\"ERR_HTTP2_TRAILERS_NOT_READY\",\"wantTrailers\",\"ERR_HTTP2_INVALID_PSEUDOHEADER\",\"ERR_HTTP2_TRAILERS_ALREADY_SENT\","
                        + "\"finish\",[\"response\",4,5,16]]");
        // The server answered once the trailers ended the request.
        assertThat(out.getString(3)).isEqualTo("POST /upload ab");
        assertThat(out.getInteger(4)).isEqualTo(42);
        assertThat(out.getString(5)).isEqualTo("{}");
        assertThat(out.getInteger(6)).isEqualTo(3);
        assertThat(out.getString(7)).isEqualTo("{}");
        assertThat(out.getJsonArray(8).encode()).isEqualTo("[[\"DeprecationWarning\",\"DEP0194\",\"Priority signaling has been deprecated as of RFC 9113.\"],"
                + "[\"DeprecationWarning\",\"DEP0999\",\"old\"]]");
    }

    @Test
    void theOutboundQueueIsNghttp2sAsNodeReportsIt() throws Exception {
        JsonArray out = run("""
                import http2 from 'node:http2';
                export default () => new Promise((resolve) => {
                  const seen = [];
                  const q = (s) => seen.push(s.state.outboundQueueSize);
                  const s = http2.connect('http://127.0.0.1:%d');
                  s.once('remoteSettings', () => q(s));
                  s.on('connect', () => {
                    q(s);
                    setImmediate(() => {
                      q(s);
                      const r = s.request({ ':path': '/hello' });
                      q(s);
                      r.resume();
                      r.on('close', () => {
                        s.settings({ initialWindowSize: 70000 });
                        s.request({ ':path': '/hello' }).close(http2.constants.NGHTTP2_CANCEL);
                        s.ping(() => {});
                        q(s);
                        setImmediate(() => { q(s); s.destroy(); resolve(seen); });
                      });
                    });
                  });
                });
                """.formatted(clear.actualPort()));

        // connect: our SETTINGS; next turn: written; request: HEADERS; remoteSettings: their ACK alone;
        // SETTINGS + HEADERS + PING (a stream closed before its HEADERS went adds no RST_STREAM); then written.
        assertThat(out).containsExactly(1, 0, 1, 1, 3, 0);
    }

    @Test
    void streamStateFollowsWhatWasWrittenAndWhatTheScriptSaw() throws Exception {
        JsonArray out = run("""
                import http2 from 'node:http2';
                const pick = (st) => st.state === undefined ? '{}' : `${st.state} ${st.localClose}${st.remoteClose} ${st.localWindowSize} ${st.weight}`;
                export default () => new Promise((resolve) => {
                  const seen = [];
                  const s = http2.connect('http://127.0.0.1:%d', () => {
                    const r = s.request({ ':method': 'POST', ':path': '/echo' });
                    r.on('ready', () => seen.push('ready ' + pick(r.state)));
                    seen.push('request() ' + pick(r.state) + ' id ' + r.id);
                    setImmediate(() => {
                      seen.push('next turn ' + pick(r.state));
                      r.end('abc');
                      seen.push('end() ' + pick(r.state));
                      setImmediate(() => seen.push('written ' + pick(r.state)));
                    });
                    r.on('response', () => seen.push('response ' + pick(r.state)));
                    r.on('data', () => seen.push('data ' + pick(r.state)));
                    r.on('close', () => { seen.push('close ' + pick(r.state)); s.close(); resolve(seen); });
                  });
                });
                """.formatted(clear.actualPort()));

        // Idle until its HEADERS are written; local side closed once END_STREAM is; the window less what arrived
        // ("POST /echo abc"). On a connected session 'ready' fires inside request(), before a listener can be added.
        assertThat(out).containsExactly("request() 1 00 0 0 id 1", "next turn 2 00 65535 16", "end() 2 00 65535 16",
                "written 5 10 65535 16", "response 5 10 65535 16", "data 5 10 65521 16", "close {}");
    }

    @Test
    void h2cAndRefusals() throws Exception {
        JsonArray out = run("""
                import http2 from 'node:http2';
                const open = (url, options) => new Promise((resolve) => {
                  const session = http2.connect(url, options);
                  session.on('connect', () => { resolve(['connected', session.alpnProtocol]); session.close(); });
                  session.on('error', (e) => resolve(['error', e.code]));
                });
                export default async () => [
                  await open('http://127.0.0.1:%d'),
                  await open('https://localhost:%d'),
                  await open('https://127.0.0.1:%d', { ca: %s }),
                  (() => { try { http2.connect('ftp://x'); return 'ran'; } catch (e) { return e.code; } })(),
                  (() => { try { http2.createServer(); return 'ran'; } catch (e) { return e.code; } })(),
                ];
                """.formatted(clear.actualPort(), secure.actualPort(), secure.actualPort(), Json.encode(ca)));

        assertThat(out.getJsonArray(0)).containsExactly("connected", "h2c");
        assertThat(out.getJsonArray(1)).containsExactly("error", "DEPTH_ZERO_SELF_SIGNED_CERT");
        assertThat(out.getJsonArray(2)).containsExactly("error", "ERR_TLS_CERT_ALTNAME_INVALID");
        assertThat(out.getString(3)).isEqualTo("ERR_HTTP2_UNSUPPORTED_PROTOCOL");
        assertThat(out.getString(4)).isEqualTo("ERR_NOT_SUPPORTED");
    }

    @Test
    void streamsShareTheRequestLimitAndSessionsCloseWithTheRun() throws Exception {
        int before = connectionsClosed.get();
        JsonArray out = run("""
                import http2 from 'node:http2';
                export default () => new Promise((resolve) => {
                  const session = http2.connect('http://127.0.0.1:%d', () => {
                    const results = [];
                    // Sixteen slow streams hold every request place; the seventeenth is refused.
                    for (let i = 0; i < 17; i++) {
                      const req = session.request({ ':path': '/slow' });
                      req.on('error', (e) => { results.push(e.message.includes('Too many concurrent requests') ? 'refused' : e.code); resolve(results); });
                    }
                  });
                });
                """.formatted(clear.actualPort()));

        assertThat(out).containsExactly("refused");
        // The script returned with its session open: the run's end closed it.
        long deadline = System.currentTimeMillis() + 5_000;
        while (connectionsClosed.get() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(connectionsClosed.get()).isGreaterThan(before);
    }

    private static JsonArray run(String script) throws Exception {
        Execution execution = new Execution("job-1", "tenant-a", "import { Buffer } from 'node:buffer';\n" + script, new JsonObject(),
                Duration.ofSeconds(20), JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        ExecutionResult result = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD,
                        NodeHostApis.factory(node).create("tenant-a", "job-1", false))
                .result().toCompletionStage().toCompletableFuture().get(40, TimeUnit.SECONDS);
        assertThat(result.error()).isNull();
        return (JsonArray) result.output();
    }
}
