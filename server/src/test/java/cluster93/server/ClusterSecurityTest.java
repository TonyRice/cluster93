package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import cluster93.tls.ClusterCa;
import cluster93.tls.NodeBundle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Node certificates between core nodes (D-066, D-067): who gets in, who doesn't, and revocation. */
class ClusterSecurityTest {

    private static final int PORT = 34701;
    /** A certificate issued in the same instant as a revocation counts as issued after it; wait a moment either side. */
    private static final long SETTLE_MS = 50;

    private static Map<String, String> env(String nodeId) {
        Map<String, String> env = new HashMap<>(Map.ofEntries(
                Map.entry("C93_NODE_ID", nodeId),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", String.valueOf(PORT)),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1:" + PORT + ".." + (PORT + 9)),
                // A refused node gives up instead of retrying for the default minute.
                Map.entry("C93_CLUSTER_JOIN_TIMEOUT_MS", "8000")));
        return env;
    }

    @Test
    void aClusteredNodeWithoutCertificatesRefusesToStart() throws Exception {
        Map<String, String> env = env("bare");
        env.put("C93_TLS_DIR", Files.createTempDirectory("empty-tls").toString());

        assertThatThrownBy(() -> await(Cluster93.start(ServerConfig.fromEnv(env))))
                .isInstanceOf(ExecutionException.class)
                .cause().hasMessageContaining("No node certificate").hasMessageContaining("c93 node invite bare")
                .hasMessageContaining("C93_CLUSTER_TLS=off");
    }

    @Test
    void aJoinLineInstallsTheNodesCertificateAndOnlyForThatNode() throws Exception {
        Path dir = Files.createTempDirectory("join-tls");
        Map<String, String> wrongNode = env("node-x");
        wrongNode.put("C93_TLS_DIR", dir.toString());
        wrongNode.put("C93_JOIN", NodeBundle.bundle(TestTls.identity("node-y")));
        assertThatThrownBy(() -> await(Cluster93.start(ServerConfig.fromEnv(wrongNode))))
                .cause().hasMessageContaining("This join line is for node node-y, but this node is node-x");

        Map<String, String> joining = env("node-x");
        joining.put("C93_TLS_DIR", dir.toString());
        joining.put("C93_JOIN", NodeBundle.bundle(TestTls.identity("node-x")));
        RunningNode node = await(Cluster93.start(ServerConfig.fromEnv(joining)));
        try {
            assertThat(Files.exists(dir.resolve("node.key"))).isTrue();
            // The internal endpoint only speaks TLS with a node certificate: plain HTTP gets no HTTP answer.
            assertThat(plainHttp(node.internalPort())).doesNotStartWith("HTTP/");
        } finally {
            if (node != null) { await(node.stop()); }
        }
    }

    @Test
    void outsidersCantJoinAndARevokedNodeIsStoppedAndKeptOut() throws Exception {
        RunningNode first = await(Cluster93.start(ServerConfig.fromEnv(TestTls.secure(env("first")))));
        Vertx clientVertx = Vertx.vertx();
        WebClient client = WebClient.create(clientVertx);
        try {
            TestAuth.use(first);

            // A node with a certificate from another CA, and one with TLS off, can't get in.
            Map<String, String> foreign = env("foreign");
            foreign.put("C93_TLS_DIR", TestTls.directoryFor("foreign", ClusterCa.create("other")).toString());
            tryToJoin(foreign);
            Map<String, String> plain = env("plain");
            plain.put("C93_CLUSTER_TLS", "off");
            tryToJoin(plain);
            assertThat(nodes(client, first)).as("only members are in the cluster").containsExactly("first");

            // A member joins with its certificate.
            Path secondDir = TestTls.directoryFor("second", TestTls.CA);
            Map<String, String> second = env("second");
            second.put("C93_TLS_DIR", secondDir.toString());
            RunningNode member = await(Cluster93.start(ServerConfig.fromEnv(second)));
            awaitNodes(client, first, List.of("first", "second"));
            if (member != null) { await(member.stop()); }
            awaitNodes(client, first, List.of("first"));

            // Revoked while it's away (revoking a node that's in the cluster forces it out and halts its process, which the
            // Docker test covers), its certificate is refused when it tries to come back.
            HttpResponse<Buffer> revoked = await(client.post(first.httpPort(), "localhost", "/v1/cluster/nodes/second/revoke")
                    .putHeader("Authorization", TestAuth.ADMIN).send());
            assertThat(revoked.statusCode()).as(revoked.bodyAsString()).isEqualTo(200);
            assertThat(revoked.bodyAsJsonObject().getBoolean("stopped")).isFalse();
            Thread.sleep(SETTLE_MS);
            tryToJoin(second);
            assertThat(nodes(client, first)).as("the revoked certificate is refused").containsExactly("first");

            // Invited again (a new certificate), it's a member again.
            Map<String, String> reinvited = env("second");
            reinvited.put("C93_TLS_DIR", TestTls.directoryFor("second", TestTls.CA).toString());
            RunningNode back = await(Cluster93.start(ServerConfig.fromEnv(reinvited)));
            awaitNodes(client, first, List.of("first", "second"));
            if (back != null) { await(back.stop()); }
        } finally {
            if (clientVertx != null) { await(clientVertx.close()); }
            if (first != null) { await(first.stop()); }
            org.apache.ignite.Ignition.allGrids().stream().filter(g -> !g.name().equals("cluster93-first"))
                    .forEach(g -> org.apache.ignite.Ignition.stop(g.name(), true));
        }
    }

    /** Starts a node that should not get into the cluster; whatever it does on its own is stopped afterwards. */
    private static void tryToJoin(Map<String, String> env) throws Exception {
        CompletableFuture<RunningNode> started = Cluster93.start(ServerConfig.fromEnv(env)).toCompletionStage().toCompletableFuture();
        try {
            RunningNode alone = started.get(45, TimeUnit.SECONDS);
            if (alone != null) { await(alone.stop()); }
        } catch (Exception e) {
            // Refused or still trying: either way it isn't a member.
        }
        org.apache.ignite.Ignition.allGrids().stream().filter(g -> g.name().equals("cluster93-" + env.get("C93_NODE_ID")))
                .forEach(g -> org.apache.ignite.Ignition.stop(g.name(), true));
    }

    private static List<String> nodes(WebClient client, RunningNode node) throws Exception {
        JsonObject body = await(client.get(node.httpPort(), "localhost", "/v1/nodes").putHeader("Authorization", TestAuth.ADMIN).send())
                .bodyAsJsonObject();
        return body.getJsonArray("nodes").stream().map(n -> ((JsonObject) n).getString("id")).sorted().toList();
    }

    private static void awaitNodes(WebClient client, RunningNode node, List<String> expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<String> seen = List.of();
        while (System.nanoTime() < deadline) {
            seen = nodes(client, node);
            if (seen.equals(expected)) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(seen).isEqualTo(expected);
    }

    /** What a plain-HTTP request to {@code port} gets back (empty if the connection is closed). */
    private static String plainHttp(int port) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[16];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
