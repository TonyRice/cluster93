package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The cluster inventory, status/doctor reconciliation, and the membership commands (D-188). */
class ClusterCommandTest {

    @TempDir
    Path home;

    private HttpServer server;
    private final AtomicReference<String> liveNodes = new AtomicReference<>("{\"nodes\":[]}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/nodes", exchange -> respond(exchange, liveNodes.get()));
        server.createContext("/v1/cluster/nodes/", exchange -> respond(exchange,
                "{\"nodeId\":\"n2\",\"revokedAt\":1,\"stopped\":true}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Io io() {
        return new Io(Map.of(), home.resolve("config"), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), false);
    }

    private Result run(String... args) {
        return run(io(), args);
    }

    private Result run(Io io, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io withOutput = new Io(io.env(), io.configDir(), io.in(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(withOutput, args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void login() {
        new Config(url(), "admin-token", "cluster").save(io(), "default");
    }

    private Result init(String... extra) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("cluster", "init",
                "--nodes", "n1,n2", "--out", home.resolve("tls").toString(), "--api", url()));
        args.addAll(java.util.List.of(extra));
        return run(args.toArray(String[]::new));
    }

    @Test
    void initWritesTheInventoryAndCertificates() throws Exception {
        Result result = run("cluster", "init", "--nodes", "n1,n2", "--host", "n2=10.0.0.12",
                "--api", url(), "--out", home.resolve("tls").toString());
        assertThat(result.code()).as(result.err()).isZero();

        Path dir = home.resolve("tls");
        assertThat(dir.resolve("ca.key")).isRegularFile();
        assertThat(dir.resolve("ca.crt")).isRegularFile();
        assertThat(dir.resolve("n1.join")).isRegularFile();
        assertThat(dir.resolve("n1.crt")).isRegularFile();
        assertThat(dir.resolve("cluster.json")).isRegularFile();

        ClusterFile cluster = ClusterFile.load(dir);
        assertThat(cluster).isNotNull();
        assertThat(cluster.name()).isEqualTo("c93");
        assertThat(cluster.api()).isEqualTo(url());
        assertThat(cluster.ids()).containsExactly("n1", "n2");
        JsonNode n2 = cluster.node("n2");
        assertThat(n2.path("addresses").toString()).contains("10.0.0.12");
        assertThat(n2.path("expires").asText()).isNotBlank();
        assertThat(n2.path("certificate").asText()).isEqualTo("n2.crt");
        assertThat(n2.path("join").asText()).isEqualTo("n2.join");

        assertThat(ProfileStore.meta(io(), "default").get("cluster").asText())
                .isEqualTo(dir.toAbsolutePath().normalize().toString());
    }

    @Test
    void statusReconcilesInventoryAndLiveNodesAndCheckFailsOnDrift() {
        login();
        assertThat(init().code()).isZero();
        liveNodes.set("{\"nodes\":[{\"id\":\"n1\",\"capacity\":4,\"running\":1,\"endpoint\":\"n1:9396\",\"lastSeen\":1}]}");

        Result status = run("cluster", "status", "--file", home.resolve("tls").toString(), "--json");
        assertThat(status.code()).as(status.err()).isZero();
        JsonNode json = Json.parse(status.out());
        assertThat(json.get("ok").asBoolean()).isFalse();
        assertThat(json.get("problems").toString()).contains("missing");

        Result check = run("cluster", "status", "--file", home.resolve("tls").toString(), "--check");
        assertThat(check.code()).isEqualTo(1);
        assertThat(check.out()).contains("n2").contains("missing");

        liveNodes.set("{\"nodes\":[{\"id\":\"n1\",\"capacity\":4,\"running\":1},"
                + "{\"id\":\"n2\",\"capacity\":2,\"running\":0}]}");
        Result clean = run("cluster", "status", "--file", home.resolve("tls").toString(), "--check");
        assertThat(clean.code()).as(clean.out()).isZero();
        assertThat(clean.out()).contains("live");
    }

    @Test
    void inviteWritesAJoinLineAndRecordsTheNode() throws Exception {
        login();
        assertThat(init().code()).isZero();
        Path dir = home.resolve("tls");

        Result invited = run("cluster", "invite", "n3", "--host", "10.0.0.13");
        assertThat(invited.code()).as(invited.err()).isZero();
        assertThat(invited.out()).contains("C93_JOIN=c93_nodebundle_");
        assertThat(dir.resolve("n3.join")).isRegularFile();
        assertThat(dir.resolve("n3.crt")).isRegularFile();

        ClusterFile cluster = ClusterFile.load(dir);
        assertThat(cluster.ids()).contains("n3");
        assertThat(cluster.node("n3").path("revoked").asBoolean()).isFalse();
        assertThat(cluster.node("n3").path("expires").asText()).isNotBlank();
    }

    @Test
    void revokeMarksTheInventoryAfterTheApiAcceptsIt() throws Exception {
        login();
        assertThat(init().code()).isZero();
        Path file = home.resolve("tls/cluster.json");

        Result revoked = run("cluster", "revoke", "n2", "--file", file.toString());
        assertThat(revoked.code()).as(revoked.err()).isZero();
        assertThat(revoked.out()).contains("Revoked n2");

        assertThat(ClusterFile.load(file).node("n2").path("revoked").asBoolean()).isTrue();
    }

    @Test
    void doctorFailsWhenAConfiguredNodeIsMissing() {
        login();
        assertThat(init().code()).isZero();
        liveNodes.set("{\"nodes\":[{\"id\":\"n1\",\"capacity\":4,\"running\":1}]}");

        Result doctor = run("cluster", "doctor", "--file", home.resolve("tls").toString());
        assertThat(doctor.code()).isEqualTo(1);
        assertThat(doctor.out()).contains("n2").contains("missing");

        liveNodes.set("{\"nodes\":[{\"id\":\"n1\",\"capacity\":4,\"running\":1},"
                + "{\"id\":\"n2\",\"capacity\":2,\"running\":0}]}");
        Result clean = run("cluster", "doctor", "--file", home.resolve("tls").toString());
        assertThat(clean.code()).as(clean.out()).isZero();
        assertThat(clean.out()).contains("ok");
    }

    @Test
    void theAdminCommandsLiveUnderClusterAndStillWorkAtTheRoot() {
        assertThat(run("cluster", "tenants", "--help").out()).contains("Usage: c93 cluster tenants");
        assertThat(run("cluster", "users", "--help").out()).contains("Usage: c93 cluster users");
        assertThat(run("cluster", "audit", "--help").out()).contains("Usage: c93 cluster audit");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record Result(int code, String out, String err) {
    }
}
