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
    private static HttpServer tlsServer;
    private static String tlsCa;
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
        io.vertx.core.net.SelfSignedCertificate certificate = io.vertx.core.net.SelfSignedCertificate.create("localhost");
        tlsCa = java.nio.file.Files.readString(java.nio.file.Path.of(certificate.certificatePath()));
        tlsServer = vertx.createHttpServer(new io.vertx.core.http.HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()))
                .requestHandler(req -> req.response().end("secure")).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
                  code(() => net.createServer()), code(() => tls.createServer()),
                  http2.constants.HTTP2_HEADER_STATUS, code(() => http2.createServer()),
                  tty.isatty(1), tls.DEFAULT_MIN_VERSION,
                ];
                """);

        // Servers never run on the cluster's nodes (D-112); connecting is NodeSocketsTest's (D-116).
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

    @Test
    void theSocketReportsTheRealConnectionInNodesOrder() throws Exception {
        JsonArray out = run("""
                import http from 'node:http';
                const call = () => new Promise((resolve) => {
                  const events = [];
                  const req = http.get('http://127.0.0.1:%d/a', (res) => {
                    res.resume();
                    res.on('end', () => resolve({ events, reused: req.reusedSocket, remote: [req.socket.remoteAddress, req.socket.remotePort,
                      req.socket.remoteFamily], local: typeof req.socket.localPort, connecting: req.socket.connecting }));
                  });
                  req.on('socket', (s) => {
                    events.push('socket ' + s.connecting);
                    for (const e of ['lookup', 'connect', 'secureConnect', 'ready']) s.on(e, () => events.push(e));
                  });
                });
                export default async () => [await call(), await call()];
                """.formatted(server.actualPort()));

        JsonObject first = out.getJsonObject(0);
        // An address is never looked up, as in Node; the connection is new, then the second request reuses it.
        assertThat(first.getJsonArray("events")).containsExactly("socket true", "connect", "ready");
        assertThat(first.getJsonArray("remote")).containsExactly("127.0.0.1", server.actualPort(), "IPv4");
        assertThat(first.getString("local")).isEqualTo("number");
        assertThat(first.getBoolean("reused")).isFalse();
        assertThat(first.getBoolean("connecting")).isFalse();
        assertThat(out.getJsonObject(1).getBoolean("reused")).isTrue();
    }

    @Test
    void aTlsSocketReportsItsPeerAndWhetherItWasTrusted() throws Exception {
        JsonArray out = run("""
                import https from 'node:https';
                import { Buffer } from 'node:buffer';
                const call = (options) => new Promise((resolve) => {
                  const req = https.get('https://localhost:%d/', options, (res) => {
                    res.resume();
                    const s = req.socket;
                    const cert = s.getPeerCertificate(true);
                    resolve({ authorized: s.authorized, error: s.authorizationError ?? null, protocol: s.getProtocol(),
                      cipher: typeof s.getCipher().name, cn: cert.subject.CN, san: cert.subjectaltname, raw: Buffer.isBuffer(cert.raw),
                      fingerprint: /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(cert.fingerprint256), selfIssued: cert.issuerCertificate === cert,
                      from: /^[A-Z][a-z]{2} [ 0-9]\\d \\d\\d:\\d\\d:\\d\\d \\d{4} GMT$/.test(cert.valid_from) });
                  });
                  req.on('error', (e) => resolve({ error: e.code }));
                });
                export default async () => [await call({ rejectUnauthorized: false }), await call({ ca: %s })];
                """.formatted(tlsServer.actualPort(), io.vertx.core.json.Json.encode(tlsCa)));

        JsonObject unverified = out.getJsonObject(0);
        assertThat(unverified.getBoolean("authorized")).isFalse();
        assertThat(unverified.getString("error")).isEqualTo("DEPTH_ZERO_SELF_SIGNED_CERT");
        assertThat(unverified.getString("protocol")).startsWith("TLSv1.");
        assertThat(unverified.getString("cipher")).isEqualTo("string");
        assertThat(unverified.getString("cn")).isEqualTo("localhost");
        assertThat(unverified.getBoolean("raw")).isTrue();
        assertThat(unverified.getBoolean("fingerprint")).isTrue();
        assertThat(unverified.getBoolean("selfIssued")).isTrue();
        assertThat(unverified.getBoolean("from")).isTrue();
        JsonObject trusted = out.getJsonObject(1);
        assertThat(trusted.getBoolean("authorized")).as(trusted.encode()).isTrue();
        assertThat(trusted.getValue("error")).isNull();
    }

    @Test
    void httpRequestTakesItsProtocolFromTheAgentAsNodeDoes() throws Exception {
        JsonArray out = run("""
                import http from 'node:http';
                import https from 'node:https';
                const call = (options) => new Promise((resolve) => {
                  try {
                    const req = http.request('https://localhost:%d/', options, (res) => {
                      let text = '';
                      res.setEncoding('utf8');
                      res.on('data', (c) => { text += c; });
                      res.on('end', () => resolve(text));
                    });
                    req.on('error', (e) => resolve(e.code));
                    req.end();
                  } catch (e) {
                    resolve(e.code);
                  }
                });
                export default async () => [
                  await call({ ca: %s }),
                  // http2-wrapper (got's http2: true) falls back to HTTP/1.1 this way.
                  await call({ _defaultAgent: https.globalAgent, ca: %2$s }),
                  await call({ agent: new https.Agent({ ca: %2$s }) }),
                ];
                """.formatted(tlsServer.actualPort(), io.vertx.core.json.Json.encode(tlsCa)));

        assertThat(out).containsExactly("ERR_INVALID_PROTOCOL", "secure", "secure");
    }

    @Test
    void diagnosticsChannelsPublishAndTrace() throws Exception {
        JsonArray out = run("""
                import dc, { channel, subscribe, tracingChannel } from 'node:diagnostics_channel';
                export default async () => {
                  const seen = [];
                  const ch = channel('app:work');
                  const before = ch.hasSubscribers;
                  subscribe('app:work', (message, name) => seen.push(name + ' ' + message.n));
                  ch.publish({ n: 1 });
                  const traced = tracingChannel('app:op');
                  traced.subscribe({ start: () => seen.push('start'), end: () => seen.push('end'),
                    asyncStart: (c) => seen.push('asyncStart ' + c.result), asyncEnd: () => seen.push('asyncEnd'), error: (c) => seen.push('error ' + c.error.message) });
                  await traced.tracePromise(async () => 42, {});
                  try { traced.traceSync(() => { throw new Error('boom'); }, {}); } catch (e) { seen.push('caught'); }
                  return [before, ch.hasSubscribers, dc.hasSubscribers('nobody'), channel('app:work') === ch, seen];
                };
                """);

        assertThat(out.getBoolean(0)).isFalse();
        assertThat(out.getBoolean(1)).isTrue();
        assertThat(out.getBoolean(2)).isFalse();
        assertThat(out.getBoolean(3)).isTrue();
        assertThat(out.getJsonArray(4)).containsExactly("app:work 1", "start", "end", "asyncStart 42", "asyncEnd", "start", "error boom", "end", "caught");
    }

    @Test
    void eventsOnIteratesUntilCloseOrAbort() throws Exception {
        JsonArray out = run("""
                import { EventEmitter, on } from 'node:events';
                export default async () => {
                  const e = new EventEmitter();
                  setTimeout(() => { e.emit('data', 1, 'a'); e.emit('data', 2, 'b'); e.emit('end'); }, 5);
                  const got = [];
                  for await (const args of on(e, 'data', { close: ['end'] })) got.push(args);
                  const failing = new EventEmitter();
                  setTimeout(() => failing.emit('error', new Error('bad')), 5);
                  let error;
                  try { for await (const _ of on(failing, 'data')) { /* nothing */ } } catch (err) { error = err.message; }
                  const controller = new AbortController();
                  setTimeout(() => controller.abort(), 5);
                  let aborted;
                  try { for await (const _ of on(new EventEmitter(), 'data', { signal: controller.signal })) { /* nothing */ } } catch (err) { aborted = err.name; }
                  return [got, error, aborted, e.listenerCount('data'), e.listenerCount('end')];
                };
                """);

        assertThat(out.encode()).isEqualTo("[[[1,\"a\"],[2,\"b\"]],\"bad\",\"AbortError\",0,0]");
    }

    @Test
    void dnsHasNodesNamesAndLooksUpWithoutTheNetworkWhereNodeDoes() throws Exception {
        // Real lookups and queries are NodeDnsTest's and the dnsclient fixture's (D-122); this stays off the network:
        // an address needs no lookup, and localhost comes from the hosts file.
        JsonArray out = run("""
                import dns, { promises, Resolver, ADDRCONFIG, NOTFOUND } from 'node:dns';
                import { lookup } from 'node:dns/promises';
                export default async () => {
                  const literal = await new Promise((resolve) => dns.lookup('93.184.216.34', (err, address, family) => resolve([address, family])));
                  const local = await lookup('localhost', { family: 4 });
                  let badServer;
                  try { new promises.Resolver().setServers(['not-an-ip']); } catch (e) { badServer = e.code; }
                  return [ADDRCONFIG, NOTFOUND, typeof Resolver, literal, local.address, badServer, dns.getDefaultResultOrder()];
                };
                """);

        assertThat(out.encode()).isEqualTo("[32,\"ENOTFOUND\",\"function\",[\"93.184.216.34\",4],\"127.0.0.1\",\"ERR_INVALID_IP_ADDRESS\",\"verbatim\"]");
    }

    @Test
    void anUnconnectedSocketCanBeMade() throws Exception {
        JsonArray out = run("""
                import net from 'node:net';
                import tls from 'node:tls';
                import { PassThrough, Duplex } from 'node:stream';
                export default () => {
                  const socket = new net.Socket();
                  const wrapped = new tls.TLSSocket(new PassThrough());
                  return [socket instanceof Duplex, socket.connecting, socket.readyState, wrapped instanceof net.Socket,
                    typeof wrapped._handle._parentWrap.constructor];
                };
                """);

        assertThat(out).containsExactly(true, false, "open", true, "function");
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
