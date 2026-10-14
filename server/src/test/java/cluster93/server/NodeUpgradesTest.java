package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgraded connections through the real ingress (D-125): a Node server's {@code 'upgrade'} listener answers with its own
 * head on a net.Socket, a 101 turns the connection into a byte tunnel both ways, any other answer goes as a response,
 * and a server with no listener answers the request as an ordinary one. The ws library itself is RealNpmPackagesTest's.
 */
class NodeUpgradesTest {

    private static final String TENANT = "up-tenant";
    private static final String HOST = "chat--" + TENANT + ".apps.test";

    private static final String APP = """
            import http from 'node:http';
            import net from 'node:net';

            const seen = [];
            const main = http.createServer((req, res) => res.end(`plain ${req.upgrade} ${req.headers.upgrade ?? '-'}`));
            main.on('upgrade', (req, socket, head) => {
              seen.push([req.url, req.headers.upgrade, req.complete, socket instanceof net.Socket, socket.server === main,
                head.length, typeof socket.remoteAddress].join(' '));
              if (req.url === '/echo') {
                socket.write('HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: echo\\r\\nConnection: Upgrade\\r\\nX-Proto: 1\\r\\n\\r\\nready\\n');
                socket.on('data', (d) => socket.write(String(d).toUpperCase()));
                socket.on('end', () => { seen.push('client ended'); socket.end(); });
                socket.on('close', () => seen.push('closed'));
              } else if (req.url === '/refuse') {
                // As ws's abortHandshake: a whole response, then the socket is destroyed once it's written.
                socket.once('finish', () => socket.destroy());
                socket.end('HTTP/1.1 426 Upgrade Required\\r\\nContent-Type: text/plain\\r\\nContent-Length: 4\\r\\n\\r\\nnope');
              } else {
                socket.destroy();
              }
            });
            main.listen(3000);

            // No 'upgrade' listener: the request is an ordinary one, as in Node.
            const other = http.createServer((req, res) => res.end(`other ${req.upgrade} ${req.headers.upgrade ?? '-'}`));
            other.listen(4000);

            const status = http.createServer((req, res) => res.end(JSON.stringify(seen)));
            status.listen(5000);
            """;

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;
    /** Held for the class: Vert.x closes a client's connections once nothing references it. */
    private static io.vertx.core.net.NetClient netClient;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("C93_NODE_ID", "upgrades"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_INGRESS_DOMAIN", "apps.test"),
                Map.entry("C93_INGRESS_WEBSOCKET_IDLE_TIMEOUT_MS", "1500"),
                Map.entry("C93_SERVICE_MAX_WEBSOCKETS", "3"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_HEAP_MB", "128"),
                Map.entry("C93_HEARTBEAT_MS", "200"))))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        netClient = clientVertx.createNetClient();
        HttpResponse<Buffer> created = await(client.put(node.httpPort(), "localhost", "/v1/services/chat")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendJsonObject(new JsonObject().put("script", APP)));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        long deadline = System.currentTimeMillis() + 20_000;
        while (!plain(HOST, "/").startsWith("HTTP/1.1 200") && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
    }

    @Test
    void aOneOhOneTurnsTheConnectionIntoATunnelBothWays() throws Exception {
        try (Raw conn = Raw.open()) {
            conn.send(upgradeRequest(HOST, "/echo"));
            String head = conn.awaitText("ready\n");
            assertThat(head).startsWith("HTTP/1.1 101 Switching Protocols\r\n").contains("Upgrade: echo\r\n", "Connection: Upgrade\r\n",
                    "X-Proto: 1\r\n").doesNotContain("Content-Length", "Transfer-Encoding");

            conn.send("hello ");
            conn.send("tunnel");
            assertThat(conn.awaitText("HELLO TUNNEL")).endsWith("HELLO TUNNEL");
            // A larger stream, with backpressure along the way.
            String big = "x".repeat(2 * 1024 * 1024);
            int before = conn.text().length();
            conn.send(big);
            try {
                conn.awaitLength(before + big.length());
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "\nthe app saw: " + plain("chat--" + TENANT + "--5000.apps.test", "/")
                        + "\nthe runs: " + await(client.get(node.httpPort(), "localhost", "/v1/jobs")
                        .putHeader("Authorization", TestAuth.bearer(TENANT)).send()).bodyAsString().replaceAll("\"script\":\"[^\"]*\"", ""), e);
            }
            assertThat(conn.text()).endsWith("XXXX");
        }
        String seen = plain("chat--" + TENANT + "--5000.apps.test", "/");
        assertThat(seen).contains("/echo echo true true true 0 string");
    }

    @Test
    void anyOtherAnswerGoesAsAResponseAndASilentCloseClosesTheClient() throws Exception {
        try (Raw conn = Raw.open()) {
            conn.send(upgradeRequest(HOST, "/refuse"));
            String answer = conn.awaitText("nope");
            assertThat(answer).startsWith("HTTP/1.1 426 Upgrade Required\r\n").contains("Content-Type: text/plain\r\n", "Content-Length: 4\r\n");
        }
        try (Raw conn = Raw.open()) {
            conn.send(upgradeRequest(HOST, "/silent"));
            conn.awaitClosed();
            assertThat(conn.text()).isEmpty();
        }
    }

    @Test
    void withoutAnUpgradeListenerTheRequestIsAnOrdinaryOne() throws Exception {
        try (Raw conn = Raw.open()) {
            conn.send(upgradeRequest("chat--" + TENANT + "--4000.apps.test", "/"));
            assertThat(conn.awaitText("other false echo")).startsWith("HTTP/1.1 200 OK\r\n");
        }
        // Asking to upgrade with a body isn't tunnelled: the ingress sends it as an ordinary request.
        try (Raw conn = Raw.open()) {
            conn.send("POST /echo HTTP/1.1\r\nHost: " + HOST + "\r\nConnection: Upgrade\r\nUpgrade: echo\r\nContent-Length: 2\r\n\r\nhi");
            assertThat(conn.awaitText("plain false echo")).startsWith("HTTP/1.1 200 OK\r\n");
        }
    }

    @Test
    void aRunHoldsAtMostItsLimitOfUpgradedConnectionsAndIdleOnesClose() throws Exception {
        Raw[] open = new Raw[3];
        try {
            for (int i = 0; i < open.length; i++) {
                open[i] = Raw.open();
                open[i].send(upgradeRequest(HOST, "/echo"));
                open[i].awaitText("ready\n");
            }
            try (Raw fourth = Raw.open()) {
                fourth.send(upgradeRequest(HOST, "/echo"));
                assertThat(fourth.awaitText("limit")).startsWith("HTTP/1.1 503 ").contains("3 upgraded connections open");
            }
            // Nothing moves on them, so the ingress closes them after its idle timeout (1.5 s here).
            for (Raw conn : open) {
                conn.awaitClosed();
            }
        } finally {
            for (Raw conn : open) {
                if (conn != null) {
                    conn.close();
                }
            }
        }
        // Their slots are free again.
        long deadline = System.currentTimeMillis() + 5_000;
        String answer = "";
        while (System.currentTimeMillis() < deadline) {
            try (Raw again = Raw.open()) {
                again.send(upgradeRequest(HOST, "/echo"));
                answer = again.awaitText("\r\n\r\n");
                if (answer.startsWith("HTTP/1.1 101")) {
                    break;
                }
            }
            Thread.sleep(100);
        }
        assertThat(answer).startsWith("HTTP/1.1 101");
    }

    @Test
    void finishedRequestsGiveBackTheirSlot() throws Exception {
        // A run takes 64 requests at once; each finished one frees its slot (a counter that never went down refused all after 64).
        for (int i = 0; i < 80; i++) {
            assertThat(plain(HOST, "/n" + i)).as("request " + i).startsWith("HTTP/1.1 200");
        }
    }

    private static String upgradeRequest(String host, String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: Upgrade\r\nUpgrade: echo\r\n\r\n";
    }

    private static String plain(String host, String path) throws Exception {
        try (Raw conn = Raw.open()) {
            conn.send("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
            conn.awaitClosed();
            return conn.text();
        }
    }

    /** A plain TCP connection to the ingress, collecting what comes back. */
    private static final class Raw implements AutoCloseable {

        private final NetSocket socket;
        private final StringBuffer received = new StringBuffer();
        private volatile boolean closed;

        private Raw(NetSocket socket) {
            this.socket = socket;
            socket.handler(chunk -> received.append(chunk.toString(java.nio.charset.StandardCharsets.ISO_8859_1)));
            socket.closeHandler(v -> closed = true);
        }

        static Raw open() throws Exception {
            return new Raw(await(netClient.connect(node.ingressPort(), "127.0.0.1")));
        }

        void send(String text) throws Exception {
            await(socket.write(Buffer.buffer(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))));
        }

        String text() {
            return received.toString();
        }

        String awaitText(String expected) throws Exception {
            long deadline = System.currentTimeMillis() + 15_000;
            while (!received.toString().contains(expected)) {
                if (System.currentTimeMillis() > deadline || closed && !received.toString().contains(expected)) {
                    Thread.sleep(50);
                    if (received.toString().contains(expected)) {
                        break;
                    }
                    throw new AssertionError("Expected \"" + expected + "\" (closed: " + closed + "), got: " + received);
                }
                Thread.sleep(10);
            }
            return received.toString();
        }

        void awaitLength(int length) throws Exception {
            long deadline = System.currentTimeMillis() + 15_000;
            while (received.length() < length && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(received.length()).isGreaterThanOrEqualTo(length);
        }

        void awaitClosed() throws Exception {
            long deadline = System.currentTimeMillis() + 15_000;
            while (!closed) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("Still open, got: " + received);
                }
                Thread.sleep(10);
            }
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
