package automately.runtime.js;

import automately.core.runtime.ScriptRuntime;

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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code net} and {@code tls} sockets in the isolate (D-116), in every build, against local servers through a standalone
 * gate with the development switch (the servers are on loopback). Their results against real Node are checked by
 * RealNpmPackagesTest's sockets fixture.
 */
class NodeSocketsTest {

    private static Vertx vertx;
    private static ScriptRuntime runtime;
    private static NetServer echo;
    private static NetServer tls;
    private static NetServer starttls;
    private static String ca;
    private static final AtomicInteger closedByPeer = new AtomicInteger();
    private static NodeHostApis.Node node;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        runtime = EngineUnderTest.create(vertx, 64, 2);
        echo = vertx.createNetServer().connectHandler(socket -> {
            socket.handler(socket::write);
            socket.closeHandler(v -> closedByPeer.incrementAndGet());
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        SelfSignedCertificate certificate = SelfSignedCertificate.create("localhost");
        ca = Files.readString(Path.of(certificate.certificatePath()));
        tls = vertx.createNetServer(new NetServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()))
                .connectHandler(socket -> socket.handler(data -> socket.write(Buffer.buffer("tls:").appendBuffer(data))))
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        starttls = vertx.createNetServer(new NetServerOptions().setKeyCertOptions(certificate.keyCertOptions()))
                .connectHandler(socket -> socket.handler(data -> {
                    if (data.toString().equals("STARTTLS")) {
                        // As a real server does: stop reading, answer, switch, then read again (as TLS).
                        socket.pause();
                        socket.write("OK").compose(v -> socket.upgradeToSsl()).onComplete(v -> socket.resume());
                    } else {
                        socket.write(Buffer.buffer(socket.isSsl() ? "secure:" : "plain:").appendBuffer(data));
                    }
                })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(true)), new ServeRegistry());
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aSocketConnectsTalksAndClosesInNodesOrder() throws Exception {
        JsonArray out = run("""
                import net from 'node:net';
                export default () => new Promise((resolve) => {
                  const events = [];
                  const socket = net.connect(%d, '127.0.0.1', () => events.push('callback'));
                  socket.setEncoding('utf8');
                  for (const e of ['lookup', 'connect', 'ready', 'end', 'finish']) socket.on(e, () => events.push(e));
                  let text = '';
                  socket.on('data', (chunk) => {
                    text += chunk;
                    if (text === 'hello there') socket.end();
                  });
                  let remote;
                  let local;
                  // Read while connected: as in Node, the addresses are gone once the socket closes.
                  socket.on('connect', () => { remote = [socket.remoteAddress, socket.remotePort, socket.remoteFamily]; local = typeof socket.localPort; });
                  socket.on('close', (hadError) => resolve({ events, text, hadError, remote, local, after: socket.remoteAddress ?? null,
                    written: socket.bytesWritten, read: socket.bytesRead, state: socket.readyState }));
                  // Written before the connection exists: Node holds it until then.
                  socket.write('hello ');
                  socket.write(Buffer.from('there'));
                });
                """.formatted(echo.actualPort()));

        JsonObject result = out.getJsonObject(0);
        // The callback was registered first, so it runs first, as in Node.
        assertThat(result.getJsonArray("events")).containsExactly("callback", "connect", "ready", "finish", "end");
        assertThat(result.getString("text")).isEqualTo("hello there");
        assertThat(result.getBoolean("hadError")).isFalse();
        assertThat(result.getJsonArray("remote")).containsExactly("127.0.0.1", echo.actualPort(), "IPv4");
        assertThat(result.getString("local")).isEqualTo("number");
        assertThat(result.getValue("after")).isNull();
        assertThat(result.getInteger("written")).isEqualTo(11);
        assertThat(result.getInteger("read")).isEqualTo(11);
        assertThat(result.getString("state")).isEqualTo("closed");
    }

    @Test
    void endKeepsReadingUntilThePeerFinishes() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept(); InputStream in = peer.getInputStream(); OutputStream out = peer.getOutputStream()) {
                    out.write(("got " + new String(in.readAllBytes(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            JsonArray out = run("""
                    import { connect } from 'node:net';
                    export default async () => {
                      const socket = connect({ port: %d, host: '127.0.0.1' });
                      socket.end('question');
                      let text = '';
                      for await (const chunk of socket) text += chunk;
                      return text;
                    };
                    """.formatted(server.getLocalPort()));
            assertThat(out.getString(0)).isEqualTo("got question");
            served.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void failuresHaveNodesCodesAndFields() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        JsonArray out = run("""
                import net from 'node:net';
                const failure = (fn) => { try { fn(); return 'ran'; } catch (e) { return [e.name, e.code]; } };
                export default async () => [
                  await new Promise((resolve) => net.connect(%d, '127.0.0.1').on('error', (e) =>
                    resolve([e.code, e.message, e.address, e.port, e.syscall, e.errno]))),
                  failure(() => net.connect(70000, '127.0.0.1')),
                  failure(() => net.connect('/var/run/docker.sock')),
                  failure(() => net.createServer()),
                  await new Promise((resolve) => net.connect(25, '127.0.0.1').on('error', (e) => resolve(e.code))),
                ];
                """.formatted(closed));

        assertThat(out.getJsonArray(0)).containsExactly("ECONNREFUSED", "connect ECONNREFUSED 127.0.0.1:" + closed, "127.0.0.1", closed, "connect", -111);
        assertThat(out.getJsonArray(1)).containsExactly("RangeError", "ERR_SOCKET_BAD_PORT");
        assertThat(out.getJsonArray(2)).containsExactly("Error", "ERR_NOT_SUPPORTED");
        assertThat(out.getJsonArray(3)).containsExactly("Error", "ERR_NOT_SUPPORTED");
        // Mail ports are refused by the default cluster rule, before anything connects.
        assertThat(out.getString(4)).isEqualTo("egress_denied");
    }

    @Test
    void tlsConnectVerifiesUnlessTrustedOrOptedOut() throws Exception {
        JsonArray out = run("""
                import tls from 'node:tls';
                const call = (options) => new Promise((resolve) => {
                  const socket = tls.connect({ port: %d, host: 'localhost', ...options }, () => {
                    socket.write('x');
                  });
                  socket.setEncoding('utf8');
                  socket.on('data', (text) => {
                    const cert = socket.getPeerCertificate(true);
                    resolve({ text, authorized: socket.authorized, error: socket.authorizationError, cn: cert.subject.CN,
                      selfIssued: cert.issuerCertificate === cert, protocol: socket.getProtocol(), encrypted: socket.encrypted,
                      servername: socket.servername });
                    socket.destroy();
                  });
                  socket.on('error', (e) => resolve({ error: e.code, message: e.message }));
                });
                export default async () => [
                  await call({}),
                  await call({ ca: %s }),
                  await call({ rejectUnauthorized: false }),
                  await call({ ca: %s, servername: 'wrong.example', checkServerIdentity: () => undefined }),
                  await call({ ca: %s, checkServerIdentity: () => Object.assign(new Error('not this one'), { code: 'MINE' }) }),
                ];
                """.formatted(tls.actualPort(), Json.encode(ca), Json.encode(ca), Json.encode(ca)));

        assertThat(out.getJsonObject(0).getString("error")).as(out.getJsonObject(0).encode()).isEqualTo("DEPTH_ZERO_SELF_SIGNED_CERT");
        assertThat(out.getJsonObject(0).getString("message")).startsWith("self-signed certificate;");
        JsonObject trusted = out.getJsonObject(1);
        assertThat(trusted.getString("text")).isEqualTo("tls:x");
        assertThat(trusted.getBoolean("authorized")).isTrue();
        assertThat(trusted.getValue("error")).isNull();
        assertThat(trusted.getString("cn")).isEqualTo("localhost");
        assertThat(trusted.getBoolean("selfIssued")).isTrue();
        assertThat(trusted.getString("protocol")).startsWith("TLSv1.");
        assertThat(trusted.getBoolean("encrypted")).isTrue();
        assertThat(trusted.getValue("servername")).isEqualTo(false);
        JsonObject unverified = out.getJsonObject(2);
        assertThat(unverified.getBoolean("authorized")).isFalse();
        assertThat(unverified.getString("error")).isEqualTo("DEPTH_ZERO_SELF_SIGNED_CERT");
        // A script's own identity check replaces the host-name check, as in Node.
        assertThat(out.getJsonObject(3).getString("text")).isEqualTo("tls:x");
        assertThat(out.getJsonObject(4).getString("error")).isEqualTo("MINE");
    }

    @Test
    void tlsConnectUpgradesAConnectedSocket() throws Exception {
        JsonArray out = run("""
                import net from 'node:net';
                import tls from 'node:tls';
                export default () => new Promise((resolve) => {
                  const plain = net.connect(%d, '127.0.0.1');
                  const seen = [];
                  // A hang shows how far it got instead of running into the job's timeout.
                  setTimeout(() => resolve([...seen, 'gave up']), 5000);
                  plain.once('data', (first) => {
                    seen.push(String(first));
                    plain.write('STARTTLS');
                    plain.once('data', (ok) => {
                      seen.push(String(ok));
                      const secure = tls.connect({ socket: plain, ca: %s, servername: 'localhost' }, () => {
                        seen.push('secureConnect ' + secure.authorized);
                        secure.write('after');
                      });
                      secure.on('data', (data) => { seen.push(String(data)); secure.destroy(); resolve(seen); });
                      secure.on('error', (e) => resolve([...seen, e.code]));
                    });
                  });
                  plain.write('before');
                });
                """.formatted(starttls.actualPort(), Json.encode(ca)));

        assertThat(out).containsExactly("plain:before", "OK", "secureConnect true", "secure:after");
    }

    @Test
    void aScriptHasAtMostSixteenSocketsAndTheyCloseWithIt() throws Exception {
        int before = closedByPeer.get();
        JsonArray out = run("""
                import net from 'node:net';
                const open = () => new Promise((resolve) => {
                  const socket = net.connect(%d, '127.0.0.1', () => resolve('open'));
                  socket.on('error', (e) => resolve(e.code));
                });
                export default async () => {
                  const results = [];
                  for (let i = 0; i < 17; i++) results.push(await open());
                  return results;
                };
                """.formatted(echo.actualPort()));

        JsonArray results = out;
        assertThat(results.stream().filter("open"::equals).count()).isEqualTo(16);
        assertThat(results.getString(16)).isEqualTo("limit_exceeded");
        // The script returned with sixteen open: the run's end closed them.
        long deadline = System.currentTimeMillis() + 5_000;
        while (closedByPeer.get() - before < 16 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(closedByPeer.get() - before).isEqualTo(16);
    }

    @Test
    void aSocketTheScriptDoesntReadPushesBackOnTheServer() throws Exception {
        // A server that sends as fast as it's allowed: 64 MB in 64 KB writes, each waiting for the last.
        java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();
        NetServer firehose = vertx.createNetServer().connectHandler(socket -> {
            Buffer chunk = Buffer.buffer(new byte[64 * 1024]);
            Runnable[] pump = new Runnable[1];
            pump[0] = () -> {
                if (sent.get() < 64L * 1024 * 1024) {
                    socket.write(chunk).onSuccess(v -> {
                        sent.addAndGet(chunk.length());
                        pump[0].run();
                    });
                }
            };
            pump[0].run();
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            JsonArray out = run("""
                    import net from 'node:net';
                    export default () => new Promise((resolve) => {
                      const socket = net.connect(%d, '127.0.0.1');
                      // Connected but never read: what arrives has to wait in the host, a little, and then in the network.
                      socket.pause();
                      setTimeout(() => { resolve(socket.bytesRead); socket.destroy(); }, 2000);
                    });
                    """.formatted(firehose.actualPort()));

            // The host stopped reading after a few chunks, so TCP pushed back: far from 64 MB went anywhere.
            assertThat(sent.get()).as("bytes the server could send").isLessThan(16L * 1024 * 1024);
            assertThat(out.getLong(0)).isZero();
        } finally {
            firehose.close();
        }
    }

    @Test
    void alpnIsNegotiatedAsNodeReportsIt() throws Exception {
        io.vertx.core.http.HttpServer h2 = vertx.createHttpServer(new io.vertx.core.http.HttpServerOptions().setSsl(true).setUseAlpn(true)
                .setKeyCertOptions(SelfSignedCertificate.create("localhost").keyCertOptions())).requestHandler(req -> req.response().end())
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            JsonArray out = run("""
                    import tls from 'node:tls';
                    export default () => new Promise((resolve) => {
                      const socket = tls.connect({ host: 'localhost', port: %d, rejectUnauthorized: false, ALPNProtocols: ['h2', 'http/1.1'] }, () => {
                        resolve(socket.alpnProtocol);
                        socket.destroy();
                      });
                      socket.on('error', (e) => resolve(e.code));
                    });
                    """.formatted(h2.actualPort()));
            assertThat(out.getValue(0)).isEqualTo("h2");
        } finally {
            h2.close();
        }
    }

    @Test
    void checkServerIdentityIsNodes() throws Exception {
        JsonArray out = run("""
                import { checkServerIdentity } from 'node:tls';
                const ok = (host, cert) => { const e = checkServerIdentity(host, cert); return e ? e.code : 'ok'; };
                export default () => [
                  ok('a.example.com', { subjectaltname: 'DNS:*.example.com', subject: {} }),
                  ok('a.b.example.com', { subjectaltname: 'DNS:*.example.com', subject: {} }),
                  ok('example.com', { subject: { CN: 'example.com' } }),
                  ok('other.com', { subjectaltname: 'DNS:example.com', subject: { CN: 'other.com' } }),
                  ok('10.0.0.1', { subjectaltname: 'IP Address:10.0.0.1', subject: {} }),
                  ok('::1', { subjectaltname: 'IP Address:0:0:0:0:0:0:0:1', subject: {} }),
                ];
                """);

        assertThat(out).containsExactly("ok", "ERR_TLS_CERT_ALTNAME_INVALID", "ok", "ERR_TLS_CERT_ALTNAME_INVALID", "ok", "ok");
    }

    private static JsonArray run(String script) throws Exception {
        Execution execution = new Execution("job-1", "tenant-a", "import { Buffer } from 'node:buffer';\n" + script, new JsonObject(),
                Duration.ofSeconds(20), JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        ExecutionResult result = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD,
                        NodeHostApis.factory(node).create("tenant-a", "job-1", false))
                .result().toCompletionStage().toCompletableFuture().get(40, TimeUnit.SECONDS);
        assertThat(result.error()).isNull();
        Object output = result.output();
        return output instanceof JsonArray array ? array : new JsonArray().add(output);
    }
}
