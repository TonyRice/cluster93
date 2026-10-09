package automately.core.host;

import automately.core.host.egress.ClusterAddresses;
import automately.core.host.egress.ClusterEgress;
import automately.core.host.egress.Egress;
import automately.core.host.egress.EgressPolicies;
import automately.core.identity.SharedDataRecords;
import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingSocket;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scripts' TCP and TLS sockets on the host (D-116): data both ways, half-close, TLS and STARTTLS, the gate and limits. */
class SocketClientTest {

    private Vertx vertx;
    private EgressPolicies policies;
    private SocketClient client;
    private NetServer echo;
    private NetServer tls;
    private NetServer starttls;
    private SelfSignedCertificate certificate;
    private String ca;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        policies = new EgressPolicies(new SharedDataRecords(vertx, EgressPolicies.KIND), new SharedDataRecords(vertx, EgressPolicies.CLUSTER_KIND), null);
        // The development switch: the servers are on loopback, which is this node.
        client = new SocketClient(vertx, new Egress(vertx, true, new ClusterAddresses(vertx, null, Set.of()), policies));
        echo = await(vertx.createNetServer().connectHandler(socket -> socket.handler(socket::write)).listen(0, "127.0.0.1"));
        certificate = SelfSignedCertificate.create("localhost");
        ca = Files.readString(Path.of(certificate.certificatePath()));
        tls = await(vertx.createNetServer(new NetServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()))
                .connectHandler(socket -> socket.handler(data -> socket.write(Buffer.buffer("tls:").appendBuffer(data)))).listen(0, "127.0.0.1"));
        // A plain server that switches to TLS when asked, as SMTP's STARTTLS and Postgres's SSLRequest do.
        starttls = await(vertx.createNetServer(new NetServerOptions().setKeyCertOptions(certificate.keyCertOptions()))
                .connectHandler(socket -> socket.handler(data -> {
                    if (data.toString().equals("STARTTLS")) {
                        socket.pause();
                        socket.write("OK").compose(v -> socket.upgradeToSsl()).onComplete(v -> socket.resume());
                    } else {
                        socket.write(Buffer.buffer(socket.isSsl() ? "secure:" : "plain:").appendBuffer(data));
                    }
                })).listen(0, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void dataGoesBothWaysAndTheSocketDescribesItself() throws Exception {
        AtomicInteger open = new AtomicInteger();
        OutgoingSocket socket = await(client.connect("acme", new JsonObject().put("host", "127.0.0.1").put("port", echo.actualPort()), open));
        Reader reader = new Reader(socket);
        await(socket.write(Buffer.buffer("hello ")));
        await(socket.write(Buffer.buffer("there")));
        assertThat(reader.until("hello there")).isEqualTo("hello there");
        assertThat(socket.info().getString("remoteAddress")).isEqualTo("127.0.0.1");
        assertThat(socket.info().getInteger("remotePort")).isEqualTo(echo.actualPort());
        assertThat(socket.info().containsKey("tls")).isFalse();
        assertThat(open.get()).isEqualTo(1);
        assertThat(client.open("acme")).isEqualTo(1);

        socket.destroy();
        assertThat(open.get()).isZero();
        assertThat(client.open("acme")).isZero();
    }

    @Test
    void endStopsSendingButKeepsReading() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            // A server that answers only once the client has finished sending.
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept(); InputStream in = peer.getInputStream(); OutputStream out = peer.getOutputStream()) {
                    String got = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    out.write(("got " + got).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            OutgoingSocket socket = await(client.connect("acme", new JsonObject().put("host", "127.0.0.1").put("port", server.getLocalPort()),
                    new AtomicInteger()));
            Reader reader = new Reader(socket);
            await(socket.write(Buffer.buffer("question")));
            await(socket.end());
            assertThat(reader.toEnd()).isEqualTo("got question");
            served.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void tlsIsVerifiedUnlessTheScriptTrustsTheCaOrOptsOut() throws Exception {
        JsonObject target = new JsonObject().put("host", "localhost").put("port", tls.actualPort());
        // Refused with OpenSSL's reason, as Node's error has it.
        assertThatThrownBy(() -> await(client.connect("acme", target.copy().put("tls", new JsonObject()), new AtomicInteger())))
                .cause().isInstanceOf(HostException.class).hasMessageStartingWith("self-signed certificate; if the root CA is installed locally")
                .extracting(e -> ((HostException) e).code()).isEqualTo("DEPTH_ZERO_SELF_SIGNED_CERT");

        OutgoingSocket trusted = await(client.connect("acme", target.copy().put("tls", new JsonObject().put("ca", ca)), new AtomicInteger()));
        Reader reader = new Reader(trusted);
        await(trusted.write(Buffer.buffer("x")));
        assertThat(reader.until("tls:x")).isEqualTo("tls:x");
        JsonObject info = trusted.info().getJsonObject("tls");
        assertThat(info.getBoolean("authorized")).isTrue();
        assertThat(info.getString("protocol")).startsWith("TLSv1.");
        assertThat(info.getJsonArray("peerCertificates").getJsonObject(0).getJsonObject("subject").getString("CN")).isEqualTo("localhost");

        OutgoingSocket insecure = await(client.connect("acme", target.copy().put("tls", new JsonObject().put("rejectUnauthorized", false)),
                new AtomicInteger()));
        assertThat(insecure.info().getJsonObject("tls").getBoolean("authorized")).isFalse();
        assertThat(insecure.info().getJsonObject("tls").getString("authorizationError")).isEqualTo("DEPTH_ZERO_SELF_SIGNED_CERT");
    }

    @Test
    void aPlainConnectionUpgradesToTls() throws Exception {
        OutgoingSocket socket = await(client.connect("acme", new JsonObject().put("host", "localhost").put("port", starttls.actualPort()),
                new AtomicInteger()));
        Reader reader = new Reader(socket);
        await(socket.write(Buffer.buffer("before")));
        assertThat(reader.until("plain:before")).isEqualTo("plain:before");
        await(socket.write(Buffer.buffer("STARTTLS")));
        assertThat(reader.until("OK")).isEqualTo("OK");

        JsonObject info = await(socket.upgradeTls(new JsonObject().put("ca", ca)));
        assertThat(info.getJsonObject("tls").getBoolean("authorized")).isTrue();
        await(socket.write(Buffer.buffer("after")));
        assertThat(reader.until("secure:after")).isEqualTo("secure:after");
    }

    @Test
    void alpnIsNegotiatedAndReported() throws Exception {
        io.vertx.core.http.HttpServer h2 = await(vertx.createHttpServer(new io.vertx.core.http.HttpServerOptions().setSsl(true).setUseAlpn(true)
                .setKeyCertOptions(certificate.keyCertOptions())).requestHandler(req -> req.response().end()).listen(0, "127.0.0.1"));
        OutgoingSocket socket = await(client.connect("acme", new JsonObject().put("host", "localhost").put("port", h2.actualPort())
                .put("tls", new JsonObject().put("ca", ca).put("ALPNProtocols", new JsonArray().add("h2").add("http/1.1"))), new AtomicInteger()));
        assertThat(socket.info().getJsonObject("tls").getString("alpnProtocol")).isEqualTo("h2");
        socket.destroy();

        // A connection without ALPN, or with other protocols, first: each connection still offers its own.
        await(client.connect("acme", new JsonObject().put("host", "localhost").put("port", h2.actualPort())
                .put("tls", new JsonObject().put("ca", ca)), new AtomicInteger())).destroy();
        OutgoingSocket http1 = await(client.connect("acme", new JsonObject().put("host", "localhost").put("port", h2.actualPort())
                .put("tls", new JsonObject().put("ca", ca).put("ALPNProtocols", new JsonArray().add("http/1.1"))), new AtomicInteger()));
        assertThat(http1.info().getJsonObject("tls").getString("alpnProtocol")).isEqualTo("http/1.1");
        http1.destroy();
        OutgoingSocket again = await(client.connect("acme", new JsonObject().put("host", "localhost").put("port", h2.actualPort())
                .put("tls", new JsonObject().put("ca", ca).put("ALPNProtocols", new JsonArray().add("h2").add("http/1.1"))), new AtomicInteger()));
        assertThat(again.info().getJsonObject("tls").getString("alpnProtocol")).isEqualTo("h2");
        again.destroy();
    }

    @Test
    void endingAConnectionThePeerClosedIsDone() throws Exception {
        io.vertx.core.net.NetServer closing = await(vertx.createNetServer().connectHandler(io.vertx.core.net.NetSocket::close).listen(0, "127.0.0.1"));
        OutgoingSocket socket = await(client.connect("acme", new JsonObject().put("host", "127.0.0.1").put("port", closing.actualPort()),
                new AtomicInteger()));
        Reader reader = new Reader(socket);
        reader.toEnd();
        Thread.sleep(200); // Netty closes our side on the peer's FIN
        await(socket.end());
    }

    @Test
    void mailPortsAreRefusedByDefault() {
        assertThatThrownBy(() -> await(client.connect("acme", new JsonObject().put("host", "127.0.0.1").put("port", 25), new AtomicInteger())))
                .cause().isInstanceOf(HostException.class).hasMessageContaining("a cluster rule denies it").hasMessageContaining("blocklisted");
    }

    @Test
    void theClusterPolicysLimitsHold() throws Exception {
        await(policies.putCluster(ClusterEgress.fromJson(new JsonObject().put("limits",
                new JsonObject().put("socketsPerExecution", 2).put("socketsPerTenantPerNode", 3).put("connectsPerMinute", 5)))));
        JsonObject target = new JsonObject().put("host", "127.0.0.1").put("port", echo.actualPort());
        AtomicInteger script = new AtomicInteger();
        List<OutgoingSocket> held = new ArrayList<>();
        held.add(await(client.connect("acme", target, script)));
        held.add(await(client.connect("acme", target, script)));
        assertThatThrownBy(() -> await(client.connect("acme", target, script))).hasMessageContaining("limit 2 per script");

        AtomicInteger another = new AtomicInteger();
        held.add(await(client.connect("acme", target, another)));
        assertThatThrownBy(() -> await(client.connect("acme", target, another))).hasMessageContaining("for this tenant on this node (limit 3)");
        held.forEach(OutgoingSocket::destroy);

        // Five connections a minute: three were made, two more fit, then the next is refused.
        await(client.connect("acme", target, new AtomicInteger())).destroy();
        await(client.connect("acme", target, new AtomicInteger())).destroy();
        assertThatThrownBy(() -> await(client.connect("acme", target, new AtomicInteger())))
                .hasMessageContaining("more than 5 connections in the last minute");
        // Another tenant has its own allowance.
        await(client.connect("globex", target, new AtomicInteger())).destroy();
    }

    @Test
    void refusedConnectionsAndBadArgumentsHaveNodesCodes() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        int port = closed;
        assertThatThrownBy(() -> await(client.connect("acme", new JsonObject().put("host", "127.0.0.1").put("port", port), new AtomicInteger())))
                .cause().isInstanceOf(HostException.class).hasMessage("connect ECONNREFUSED 127.0.0.1:" + port)
                .extracting(e -> ((HostException) e).code()).isEqualTo("ECONNREFUSED");
        assertThatThrownBy(() -> await(client.connect("acme", new JsonObject().put("host", "127.0.0.1").put("port", 70000), new AtomicInteger())))
                .cause().extracting(e -> ((HostException) e).code()).isEqualTo("ERR_SOCKET_BAD_PORT");
        assertThatThrownBy(() -> await(client.connect("acme", new JsonObject().put("host", "127.0.0.1").put("port", 1)
                .put("tls", new JsonObject().put("maxVersion", "TLSv1.1")), new AtomicInteger())))
                .cause().extracting(e -> ((HostException) e).code()).isEqualTo("ERR_INVALID_ARG_VALUE");
    }

    @Test
    void rootCertificatesAreTheJvmsTrustStoreAsPem() {
        JsonArray roots = SocketClient.rootCertificates();
        assertThat(roots).isNotEmpty();
        assertThat(roots.getString(0)).startsWith("-----BEGIN CERTIFICATE-----\n").endsWith("\n-----END CERTIFICATE-----");
    }

    /** Collects what a socket receives. */
    private static final class Reader {
        private final StringBuffer received = new StringBuffer();
        private final CompletableFuture<String> ended = new CompletableFuture<>();

        Reader(OutgoingSocket socket) {
            socket.data().handler(chunk -> received.append(chunk.toString(StandardCharsets.UTF_8)),
                    () -> ended.complete(received.toString()), ended::completeExceptionally);
            socket.data().resume();
        }

        String until(String suffix) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!received.toString().endsWith(suffix) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            return received.toString().substring(Math.max(0, received.length() - suffix.length()));
        }

        String toEnd() throws Exception {
            return ended.get(10, TimeUnit.SECONDS);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e;
        }
    }
}
