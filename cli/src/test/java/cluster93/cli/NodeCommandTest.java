package cluster93.cli;

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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The {@code c93 node ...} commands: the migrated {@code c93 local} verbs against a temp {@code $C93_HOME}, and the
 * install/lifecycle verbs against a local stand-in for the release host (signed with a key made for the test). No
 * daemon, no real service, and no network beyond loopback.
 */
class NodeCommandTest {

    @TempDir
    Path work;

    private Path home;
    private Path configDir;
    private Map<String, String> env;

    private HttpServer server;
    private Path releases;
    private KeyPair releaseKey;
    private Path releasePem;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(work.resolve("home"));
        configDir = home.resolve("config");
        env = new HashMap<>();
        env.put("HOME", home.toString());
        env.put("C93_CONFIG_DIR", configDir.toString());
        // The install tests must not depend on whether this host has a node running: the port check has its own
        // tests below, which clear this key.
        env.put("C93_SKIP_PORT_CHECK", "1");
        env.put("C93_HOME", home.resolve("master").toString());
        Files.createDirectories(Path.of(env.get("C93_HOME")));
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io io = new Io(env, configDir, new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(io, args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Io io() {
        return new Io(env, configDir, new ByteArrayInputStream(new byte[0]), System.out, System.err, false);
    }

    private void writeLocalEnv() throws Exception {
        Path masterHome = Path.of(env.get("C93_HOME"));
        Files.createDirectories(masterHome);
        Files.writeString(masterHome.resolve("local.env"), """
                # Cluster93 local master node configuration.
                NODE_ID="local-master"
                ROLES="api,ingress,worker,data,console"
                DATA_DIR="%s"
                LOG_DIR="%s"
                HTTP_PORT="8080"
                INGRESS_PORT="8000"
                METRICS_PORT="9090"
                WORKER_MODE="process"
                ADMIN_TOKEN="local-admin-token-0123456789abcdef"
                TENANT="local"
                """.formatted(home.resolve("data"), home.resolve("logs")));
    }

    // ----------------------------------------------------------------------
    // the migrated `c93 local` verbs
    // ----------------------------------------------------------------------

    @Test
    void repoRootResolvesFromTheEnvFileWhenTheWorkingDirectoryIsElsewhere() throws Exception {
        Path checkout = home.resolve("checkout");
        Files.createDirectories(checkout.resolve("scripts"));
        Files.writeString(checkout.resolve("scripts").resolve("local.sh"), "#!/usr/bin/env bash\n");
        Files.writeString(Path.of(env.get("C93_HOME")).resolve("local.env"), "REPO_ROOT=\"" + checkout + "\"\n");

        assertThat(NodeCommand.repoRoot(io())).isEqualTo(checkout);
    }

    @Test
    void repoRootResolvesFromNodeMetaWhenTheEnvFileIsMissing() throws Exception {
        Path checkout = home.resolve("checkout");
        Files.createDirectories(checkout.resolve("scripts"));
        Files.writeString(checkout.resolve("scripts").resolve("local.sh"), "#!/usr/bin/env bash\n");
        Files.createDirectories(Path.of(env.get("C93_HOME")));
        Files.writeString(Path.of(env.get("C93_HOME")).resolve("node.meta"), "repo_root=" + checkout + "\n");

        assertThat(NodeCommand.repoRoot(io())).isEqualTo(checkout);
    }

    @Test
    void repoRootPrefersTheEnvironmentOverride() throws Exception {
        Path checkout = home.resolve("checkout");
        Files.createDirectories(checkout.resolve("scripts"));
        Files.writeString(checkout.resolve("scripts").resolve("local.sh"), "#!/usr/bin/env bash\n");
        env.put("C93_REPO", checkout.toString());

        assertThat(NodeCommand.repoRoot(io())).isEqualTo(checkout);
    }

    @Test
    void statusSaysSoWhenNothingIsSetUp() {
        Result result = run("node", "status");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("No local master node is set up").contains("c93 node install");
    }

    @Test
    void statusReportsTheConfiguredNode() throws Exception {
        writeLocalEnv();

        Result result = run("node", "status");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out())
                .contains("Local master node")
                .contains("local-master")
                .contains("api,ingress,worker,data,console")
                .contains(home.resolve("data").toString())
                .contains("http://localhost:8080")
                .contains("http://localhost:8000")
                .contains("process")
                .contains("PID:            (not running)");
    }

    @Test
    void statusShowsTheNodesWorkerUseFromItsMetrics() throws Exception {
        writeLocalEnv();
        HttpServer metrics = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        metrics.createContext("/metrics", exchange -> {
            byte[] body = """
                    c93_workers_running{node="local-master"} 3.0
                    c93_workers_limit{node="local-master"} 8.0
                    c93_workers_warm{node="local-master"} 1.0
                    c93_tenants_active{node="local-master"} 2.0
                    c93_workers_memory_used_bytes{node="local-master"} 1342177280.0
                    c93_workers_memory_limit_bytes{node="local-master"} 5368709120.0
                    c93_tenant_workers{node="local-master",tenant="acme"} 2.0
                    c93_tenant_workers{node="local-master",tenant="globex"} 1.0
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        metrics.start();
        try {
            // Point the node's metrics port at the stand-in; the CLI reads worker use from /metrics (D-156).
            Path localEnv = Path.of(env.get("C93_HOME")).resolve("local.env");
            Files.writeString(localEnv, Files.readString(localEnv)
                    .replace("METRICS_PORT=\"9090\"", "METRICS_PORT=\"" + metrics.getAddress().getPort() + "\""));

            Result text = run("node", "status");
            assertThat(text.code()).as(text.err()).isZero();
            assertThat(text.out())
                    .contains("workers:")
                    .contains("3 running / 8")
                    .contains("2 tenant(s) active")
                    .contains("1280 MB / 5120 MB memory")
                    .contains("acme: 2");

            Result json = run("--json", "node", "status");
            com.fasterxml.jackson.databind.JsonNode node = Json.parse(json.out());
            assertThat(node.path("workers").path("running").asInt()).isEqualTo(3);
            assertThat(node.path("workers").path("limit").asInt()).isEqualTo(8);
            assertThat(node.path("workers").path("memoryUsedBytes").asLong()).isEqualTo(1342177280L);
            assertThat(node.path("tenants").size()).isEqualTo(2);
        } finally {
            metrics.stop(0);
        }
    }

    @Test
    void theServerEnvironmentFallsBackToTheMastersOwnDataDirectory() {
        Map<String, String> environment = NodeCommand.serverEnvironment(Map.of(), io());

        // scripts/local.sh installs the master under ${C93_HOME}/master-data; the fallback must match,
        // so it never points the node at the shared directory scripts/dev.sh uses.
        assertThat(environment.get("C93_DATA_DIR")).isEqualTo(Path.of(env.get("C93_HOME")).resolve("master-data").toString());
    }

    @Test
    void statusJsonHasTheState() throws Exception {
        writeLocalEnv();

        Result result = run("--json", "node", "status");

        assertThat(result.code()).as(result.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(result.out());
        assertThat(json.get("nodeId").asText()).isEqualTo("local-master");
        assertThat(json.get("backend").asText()).isEqualTo("development");
        assertThat(json.get("apiUrl").asText()).isEqualTo("http://localhost:8080");
        assertThat(json.get("running").asBoolean()).isFalse();
    }

    @Test
    void statusFlagsAnUpdateWhenTheCheckoutMovedOn() throws Exception {
        writeLocalEnv();
        String head = NodeCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        Files.writeString(Path.of(env.get("C93_HOME")).resolve("node.meta"), "git_commit=" + "0".repeat(40) + "\n");

        Result result = run("node", "status");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("Update Available").contains(head)
                .contains("scripts/local.sh update").contains("c93 update --node");
    }

    @Test
    void statusIsQuietWhenTheBuildMatchesHead() throws Exception {
        writeLocalEnv();
        String head = NodeCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        Files.writeString(Path.of(env.get("C93_HOME")).resolve("node.meta"), "git_commit=" + head + "\n");

        Result result = run("node", "status");

        assertThat(result.code()).isZero();
        assertThat(result.out()).doesNotContain("Update Available");
    }

    @Test
    void mcpPrintsTheSavedConfiguration() throws Exception {
        Files.writeString(Path.of(env.get("C93_HOME")).resolve("mcp.json"), """
                {"mcpServers": {"c93": {"type": "http", "url": "http://localhost:8080/mcp"}}}
                """);

        Result result = run("node", "mcp");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out()).contains("http://localhost:8080/mcp").contains("mcpServers");
    }

    @Test
    void mcpGeneratesTheConfigurationFromTheSavedLogin() throws Exception {
        writeLocalEnv();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), """
                {"url": "http://localhost:9999", "token": "c93_local_test_token"}
                """);

        Result result = run("node", "mcp");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out())
                .contains("http://localhost:9999/mcp")
                .contains("c93_local_test_token")
                .contains("claude mcp add --transport http c93")
                .contains("stdio bridge");
    }

    @Test
    void mcpJsonIsMachineReadable() throws Exception {
        writeLocalEnv();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), """
                {"url": "http://localhost:9999", "token": "c93_local_test_token"}
                """);

        Result result = run("--json", "node", "mcp");

        assertThat(result.code()).as(result.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(result.out());
        assertThat(json.at("/mcpServers/c93/url").asText()).isEqualTo("http://localhost:9999/mcp");
    }

    @Test
    void nodeWithoutASubcommandShowsWhatItCanDo() {
        Result result = run("node");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("status").contains("start").contains("stop").contains("logs")
                .contains("mcp").contains("install");
        assertThat(result.out()).as("membership moved to c93 cluster")
                .doesNotContainPattern("(?m)^  invite ").doesNotContainPattern("(?m)^  revoke ");
    }

    @Test
    void theDeprecatedLocalAliasStillDrivesTheNode() throws Exception {
        writeLocalEnv();

        Result status = run("local", "status");
        assertThat(status.code()).as(status.err()).isZero();
        assertThat(status.out()).contains("Local master node");

        Result bare = run("local");
        assertThat(bare.code()).isZero();
        assertThat(bare.err()).contains("deprecated");
        assertThat(bare.out()).contains("status").contains("start");
    }

    @Test
    void helpAndBadArgumentsBehave() {
        assertThat(run("--help").out()).contains("node").contains("Install, run, and manage");
        assertThat(run("node").out()).contains("status").contains("start").contains("setup");

        Result badLines = run("node", "logs", "--lines", "not-a-number");
        assertThat(badLines.code()).isEqualTo(2);

        Result unknown = run("node", "not-a-command");
        assertThat(unknown.code()).isEqualTo(2);
    }

    // ----------------------------------------------------------------------
    // install / uninstall / lifecycle
    // ----------------------------------------------------------------------

    private static KeyPair newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String pem(KeyPair key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** What {@code openssl dgst -sha256 -sign} writes: a DER ECDSA signature. */
    private static byte[] sign(byte[] data, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(data);
        return signer.sign();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** A local stand-in for the release host, serving {@code releases/} by path. */
    @BeforeEach
    void serveReleases() throws Exception {
        releaseKey = newKey();
        releasePem = Files.writeString(work.resolve("release.pem"), pem(releaseKey));
        releases = Files.createDirectories(work.resolve("releases"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Path file = releases.resolve(exchange.getRequestURI().getPath().substring(1));
            if (Files.isRegularFile(file)) {
                byte[] bytes = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        env.put("C93_INSTALL_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        env.put("C93_RELEASE_PUBLIC_KEY", releasePem.toString());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Publishes a full bundle; {@code tamper} overrides one asset's checksum, {@code signer} null leaves it unsigned. */
    private void publish(String tamper, PrivateKey signer) throws Exception {
        byte[] jar = "server-jar".getBytes(StandardCharsets.UTF_8);
        byte[] cli = "cli-jar".getBytes(StandardCharsets.UTF_8);
        byte[] javet = gzip("javet-worker".getBytes(StandardCharsets.UTF_8));
        byte[] graaljs = gzip("graaljs-worker".getBytes(StandardCharsets.UTF_8));
        Files.write(releases.resolve("c93.jar"), jar);
        Files.write(releases.resolve("cluster93-cli.jar"), cli);
        Files.write(releases.resolve("worker-javet.gz"), javet);
        Files.write(releases.resolve("worker-graaljs.gz"), graaljs);
        String sums = hash(jar) + "  c93.jar\n"
                + hash(cli) + "  cluster93-cli.jar\n"
                + (tamper != null && tamper.equals("worker-javet.gz") ? "f".repeat(64) : hash(javet)) + "  worker-javet.gz\n"
                + hash(graaljs) + "  worker-graaljs.gz\n";
        byte[] sumsBytes = sums.getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve("SHA256SUMS"), sumsBytes);
        if (signer != null) {
            Files.write(releases.resolve("SHA256SUMS.sig"), sign(sumsBytes, signer));
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(buffer)) {
            gz.write(data);
        }
        return buffer.toByteArray();
    }

    /** A stub {@code systemctl} that records its arguments; returns the directory to put on PATH. */
    private Path stubSystemctl() throws IOException {
        Path bin = Files.createDirectories(work.resolve("bin"));
        Path marker = work.resolve("systemctl.calls");
        Path script = bin.resolve("systemctl");
        Files.writeString(script, "#!/bin/sh\necho \"$*\" >> \"" + marker + "\"\nexit 0\n");
        script.toFile().setExecutable(true, false);
        return bin;
    }

    private Path emptyPath() throws IOException {
        return Files.createDirectories(work.resolve("empty-bin"));
    }

    private Path nodeDir() {
        return work.resolve("node");
    }

    private NodeInstall.Layout layout() {
        return NodeInstall.userLayout(io(), nodeDir());
    }

    @Test
    void installLaysOutTheTreeEnvAndUnitAndNoStartSkipsLaunching() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, releaseKey.getPrivate());

        Result result = run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(nodeDir().resolve("c93.jar")).exists();
        assertThat(nodeDir().resolve("bin").resolve("worker-javet")).exists();
        assertThat(nodeDir().resolve("bin").resolve("worker-graaljs")).exists();
        assertThat(Files.readString(nodeDir().resolve("bin").resolve("worker-javet"))).isEqualTo("javet-worker");
        assertThat(nodeDir().resolve("bin").resolve("worker-javet").toFile().canExecute()).isTrue();

        NodeInstall.Layout layout = layout();
        assertThat(layout.envFile()).exists();
        assertThat(Files.readString(layout.envFile()))
                .contains("C93_NODE_ID=").contains("C93_ADMIN_TOKEN=").contains("C93_DATA_DIR=");
        assertThat(layout.unitFile()).exists();
        assertThat(Files.readString(layout.unitFile()))
                .contains(layout.programDir().toString()).contains("c93.jar").contains(layout.dataDir().toString());

        // --no-start wrote no pid file and started nothing.
        assertThat(NodeService.pidFile(layout)).doesNotExist();
        assertThat(result.out()).contains("c93 node start");
    }

    @Test
    void installJsonIsMachineReadable() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, releaseKey.getPrivate());

        Result result = run("--json", "node", "install", "--release", "--dir", nodeDir().toString(), "--no-start");

        assertThat(result.code()).as(result.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(result.out());
        assertThat(json.get("programDir").asText()).isEqualTo(nodeDir().toAbsolutePath().toString());
        assertThat(json.get("started").asBoolean()).isFalse();
    }

    @Test
    void aTamperedAssetIsRefused() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish("worker-javet.gz", releaseKey.getPrivate());

        Result result = run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start");

        assertThat(result.code()).isNotZero();
        assertThat(result.err()).contains("Checksum mismatch").contains("worker-javet.gz");
    }

    @Test
    void sumsSignedByAnotherKeyInstallNothing() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, newKey().getPrivate());

        Result result = run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start");

        assertThat(result.code()).isNotZero();
        assertThat(result.err()).contains("isn't signed by the c93 release key");
    }

    @Test
    void anUnsignedReleaseInstallsNothingUnlessSkippingIsAskedFor() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, null);

        Result refused = run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start");
        assertThat(refused.code()).isNotZero();
        assertThat(refused.err()).contains("an unsigned one isn't installed");

        env.put("C93_INSECURE_SKIP_SIGNATURE", "1");
        Result skipped = run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start");
        assertThat(skipped.code()).as(skipped.err()).isZero();
        assertThat(skipped.err()).contains("not checking the release's signature");
    }

    @Test
    void asecondInstallWithoutForceIsRefused() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, releaseKey.getPrivate());
        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();

        Result again = run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start");
        assertThat(again.code()).isNotZero();
        assertThat(again.err()).contains("already installed").contains("--force");

        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start", "--force").code()).isZero();
    }

    @Test
    void startAndStopDriveSystemctlWhenPresent() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        assumeThat(NodeInstall.isMac()).isFalse();
        publish(null, releaseKey.getPrivate());
        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();

        env.put("PATH", stubSystemctl().toString());
        Path calls = work.resolve("systemctl.calls");

        Result started = run("node", "start");
        assertThat(started.code()).as(started.err()).isZero();
        assertThat(Files.readString(calls)).contains("start").contains("cluster93.service");

        Result stopped = run("node", "stop");
        assertThat(stopped.code()).as(stopped.err()).isZero();
        assertThat(Files.readString(calls)).contains("stop");
    }

    @Test
    void withoutAServiceManagerTheDirectProcessBackendIsUsed() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        assumeThat(NodeInstall.isMac()).isFalse();
        publish(null, releaseKey.getPrivate());
        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();

        env.put("PATH", emptyPath().toString());

        Result status = run("node", "status");
        assertThat(status.code()).as(status.err()).isZero();
        assertThat(status.out()).contains("Backend:").contains("direct process");

        Result stopped = run("node", "stop");
        assertThat(stopped.code()).as(stopped.err()).isZero();
        assertThat(stopped.out()).contains("Not running");
    }

    @Test
    void statusReportsTheServiceBackend() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        assumeThat(NodeInstall.isMac()).isFalse();
        publish(null, releaseKey.getPrivate());
        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();

        env.put("PATH", stubSystemctl().toString());
        Result status = run("node", "status");
        assertThat(status.code()).as(status.err()).isZero();
        assertThat(status.out()).contains("Backend:").contains("systemd --user");
    }

    @Test
    void installRecordsWhereTheNodeLivesSoTheLifecycleVerbsFindIt() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, releaseKey.getPrivate());
        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();

        // `--dir` isn't derivable from the config path, so the lifecycle verbs read it back from node.meta.
        NodeInstall.Layout layout = NodeInstall.installedLayout(io());
        assertThat(layout.programDir()).isEqualTo(nodeDir().toAbsolutePath());
        assertThat(NodeInstall.metaFile(layout)).exists();

        Result status = run("node", "status");
        assertThat(status.code()).as(status.err()).isZero();
        assertThat(status.out()).contains("Program:").contains(nodeDir().toAbsolutePath().toString());
    }

    @Test
    void uninstallRemovesTheProgramAndUnitAndPurgeRemovesData() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, releaseKey.getPrivate());

        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();
        NodeInstall.Layout layout = layout();
        assertThat(layout.dataDir()).exists();
        assertThat(layout.envFile()).exists();
        assertThat(NodeInstall.metaFile(layout)).exists();

        env.put("PATH", emptyPath().toString());
        assertThat(run("node", "uninstall", "--dir", nodeDir().toString()).code()).isZero();
        assertThat(nodeDir().resolve("c93.jar")).doesNotExist();
        assertThat(nodeDir().resolve("bin")).doesNotExist();
        assertThat(layout.unitFile()).doesNotExist();
        assertThat(layout.envFile()).doesNotExist();
        assertThat(NodeInstall.metaFile(layout)).doesNotExist();
        assertThat(layout.dataDir()).as("data is kept without --purge").exists();

        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();
        assertThat(run("node", "uninstall", "--dir", nodeDir().toString(), "--purge").code()).isZero();
        assertThat(layout.dataDir()).as("--purge removes the data").doesNotExist();
    }

    @Test
    void envShowsAndSetsTheConfiguration() throws Exception {
        assumeThat(NodeInstall.isWindows()).isFalse();
        publish(null, releaseKey.getPrivate());
        assertThat(run("node", "install", "--release", "--dir", nodeDir().toString(), "--no-start").code()).isZero();

        Result shown = run("node", "env");
        assertThat(shown.code()).as(shown.err()).isZero();
        assertThat(shown.out()).contains("C93_HTTP_PORT=9393");

        Result set = run("node", "env", "--set", "HTTP_PORT=9494", "--set", "C93_TENANT=team");
        assertThat(set.code()).as(set.err()).isZero();
        assertThat(Files.readString(layout().envFile())).contains("C93_HTTP_PORT=9494").contains("C93_TENANT=team");
        assertThat(run("node", "env").out()).contains("C93_HTTP_PORT=9494");
    }

    @Test
    void envWithoutAnInstalledNodeExplainsHowToInstallOne() {
        Result result = run("node", "env");

        assertThat(result.code()).isNotZero();
        assertThat(result.err()).contains("c93 node install");
    }

    @Test
    void installRefusesASystemInstallOnMacOrWindows() {
        if (NodeInstall.isMac() || NodeInstall.isWindows()) {
            Result refused = run("node", "install", "--release", "--system", "--no-start");
            assertThat(refused.code()).isEqualTo(2);
            assertThat(refused.err()).contains("system install");
        }
    }

    @Test
    void aSystemInstallRefusesToRunWithoutRoot() {
        assumeThat(NodeInstall.isLinux()).isTrue();
        assumeThat(NodeService.isRoot()).as("only meaningful as a non-root user").isFalse();

        Result refused = run("node", "install", "--release", "--system", "--no-start");

        assertThat(refused.code()).isEqualTo(2);
        assertThat(refused.err()).contains("must run as root");
    }

    @Test
    void aSystemInstallCreatesADedicatedServiceUser() {
        // The unit renders User=cluster93, so the installer must provision exactly that account (D-136).
        assertThat(NodeInstall.useraddCommand(Path.of("/var/lib/cluster93"), "/usr/sbin/nologin"))
                .containsExactly("useradd", "--system", "--user-group", "--home-dir", "/var/lib/cluster93",
                        "--no-create-home", "--shell", "/usr/sbin/nologin", "cluster93");
        assertThat(NodeInstall.nologinShell()).isIn("/usr/sbin/nologin", "/sbin/nologin", "/bin/false");
    }

    @Test
    void aContainerInstallLaysOutItsEnvComposeAndTlsBesideItsConfig() {
        NodeInstall.Layout layout = NodeInstall.containerLayout(io(), false, null);

        assertThat(layout.container()).isTrue();
        assertThat(layout.unitKind()).isEqualTo(NodeInstall.Layout.Kind.CONTAINER);
        // The program lives in the image, so programDir is the configuration root (D-141).
        assertThat(layout.programDir()).isEqualTo(configDir);
        assertThat(layout.envFile()).isEqualTo(configDir.resolve("node.env"));
        assertThat(layout.composeFile()).isEqualTo(configDir.resolve("compose.yaml"));
        assertThat(layout.tlsDir()).isEqualTo(configDir.resolve("tls"));
        assertThat(layout.dataDir()).isEqualTo(home.resolve(".local/share/cluster93/data"));
        // No unit: the compose file is its definition, and Docker supervises it.
        assertThat(layout.unitFile()).isNull();
        assertThat(NodeInstall.renderUnit(io(), layout, Map.of())).isEmpty();
    }

    @Test
    void aContainerNodeIsClusteredSizeOneWithDurableDataAndTls() {
        Map<String, String> config = NodeInstall.containerConfig("node-1");

        // D-142: durable Ignite from the first start, which needs an explicit data dir and certificates.
        assertThat(config).containsEntry("C93_CLUSTERED", "true");
        assertThat(config).containsEntry("C93_CLUSTER_SIZE", "1");
        assertThat(config).containsEntry("C93_CLUSTER_MEMBERS", "node-1");
        assertThat(config).containsEntry("C93_CLUSTER_BIND", "node-1");
        assertThat(config).containsEntry("C93_TLS_DIR", "/tls");
        assertThat(config).containsEntry("C93_DATA_DIR", "/var/lib/cluster93");
        // The image ships bin/worker-*, so workers run as processes (D-142).
        assertThat(config).containsEntry("C93_WORKER_MODE", "process");
        // The ports in node.env are the container's; the host mapping is compose's job.
        assertThat(config).containsEntry("C93_HTTP_PORT", "9393");
        assertThat(config.get("C93_ADMIN_TOKEN")).hasSize(64);
        // Accepting worker containers is opt-in and discoverable: the settings appear commented in node.env (D-166).
        assertThat(config).containsEntry("# C93_WORKER_SOCKET", "/run/cluster93/workers/node.sock");
        assertThat(config).containsEntry("# C93_WORKER_SECRET_FILE", "/run/secrets/c93-worker-secret");
    }

    @Test
    void theComposePublishesTheControlPlaneAndExposesOnlyTheInternalEndpoint() {
        NodeInstall.Layout layout = NodeInstall.containerLayout(io(), false, null);
        String compose = NodeInstall.renderCompose(layout, NodeInstall.containerConfig("node-1"),
                "docker", "cluster93-node:2.0.0-local", "0.0.0.0", true);

        assertThat(compose).contains("image: cluster93-node:2.0.0-local");
        assertThat(compose).contains("container_name: cluster93-node-1");
        assertThat(compose).contains("hostname: node-1");
        assertThat(compose).contains("restart: unless-stopped");
        assertThat(compose).contains("env_file: node.env");
        // The API, ingress, metrics and cluster ports are published; 9396 is only exposed (S8).
        assertThat(compose).contains("\"0.0.0.0:9393:9393\"");
        assertThat(compose).contains("\"0.0.0.0:9394:9394\"");
        assertThat(compose).contains("\"0.0.0.0:9395:9395\"");
        assertThat(compose).contains("\"0.0.0.0:5701:5701\"");
        assertThat(compose).contains("\"0.0.0.0:5801:5801\"");
        assertThat(compose).contains("expose: [\"9396\"]");
        assertThat(compose).doesNotContain("9396:9396");
        // Persistence is the bind mount, and the certificates are read-only (D-142).
        assertThat(compose).contains(layout.dataDir() + ":/var/lib/cluster93");
        assertThat(compose).contains(layout.tlsDir() + ":/tls:ro");
        // The worker socket directory is mounted unconditionally, so a deployment's worker containers can share it and
        // enabling them is an env change rather than another install (D-166).
        assertThat(compose).contains(NodeInstall.workerSocketDir(layout) + ":/run/cluster93/workers");
        // Hardening (S4).
        assertThat(compose).contains("cap_drop: [ALL]");
        assertThat(compose).contains("no-new-privileges:true");
        assertThat(compose).contains("read_only: true");
        assertThat(compose).contains("pids_limit");
    }

    @Test
    void theComposeBindAddressAndClusterPortsAreOptional() {
        NodeInstall.Layout layout = NodeInstall.containerLayout(io(), false, null);
        String compose = NodeInstall.renderCompose(layout, NodeInstall.containerConfig("node-1"),
                "docker", "img", "127.0.0.1", false);

        assertThat(compose).contains("\"127.0.0.1:9393:9393\"");
        // --no-publish-cluster-ports keeps Ignite off the host network.
        assertThat(compose).doesNotContain("5701:5701");
        assertThat(compose).doesNotContain("5801:5801");
    }

    @Test
    void aContainerInstallRecordsBothDataDirsAndTheEngineInItsMeta() throws Exception {
        NodeInstall.Layout layout = NodeInstall.containerLayout(io(), false, null);
        Files.createDirectories(layout.envFile().getParent());
        Files.writeString(layout.envFile(), "C93_NODE_ID=node-1\n");

        NodeInstall.writeContainerMeta(layout, "docker", "false", "cluster93-node:local", "2.0.0-local", "node-1");

        Map<String, String> meta = NodeCommand.loadKeyValues(NodeInstall.metaFile(layout));
        assertThat(meta).containsEntry("kind", "container");
        assertThat(meta).containsEntry("engine", "docker");
        assertThat(meta).containsEntry("engine_rootless", "false");
        assertThat(meta).containsEntry("image", "cluster93-node:local");
        assertThat(meta).containsEntry("project", "cluster93-node-1");
        assertThat(meta).containsEntry("in_container_data_dir", "/var/lib/cluster93");
        // The host path and the in-container path differ, and --purge depends on knowing both (D-141).
        assertThat(meta).containsEntry("host_data_dir", layout.dataDir().toString());
        assertThat(meta).containsEntry("version", "2.0.0-local");
    }

    /** A stand-in container engine: answers `info` with the given security options, and does nothing else. */
    private Path fakeEngine(String name, String securityOptions) throws IOException {
        Path bin = Files.createDirectories(work.resolve("bin-" + name + "-" + securityOptions.hashCode()));
        Path script = bin.resolve(name);
        Files.writeString(script, "#!/bin/sh\n"
                + "if [ \"$1\" = \"info\" ]; then echo '" + securityOptions + "'; exit 0; fi\n"
                + "exit 0\n");
        script.toFile().setExecutable(true);
        env.put("PATH", bin.toString());
        return script;
    }

    /** A TLS directory with the three files a container node needs. */
    private Path tlsDir() throws IOException {
        Path dir = Files.createDirectories(work.resolve("tls"));
        for (String name : new String[]{"node.crt", "node.key", "ca.crt"}) {
            Files.writeString(dir.resolve(name), name + "\n");
        }
        return dir;
    }

    @Test
    void detectEngineRefusesClearlyWhenNoEngineIsOnThePath() throws IOException {
        Path empty = Files.createDirectories(work.resolve("empty-bin"));
        env.put("PATH", empty.toString());

        assertThatThrownBy(() -> NodeInstall.detectEngine(io(), null))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("docker")
                .hasMessageContaining("podman")
                .hasMessageContaining("not on the PATH");
    }

    @Test
    void detectEngineAsksTheDaemonWhetherItIsRootless() throws IOException {
        // A rootful daemon: the socket may still be reachable by the user, but it is root-equivalent (S6).
        fakeEngine("docker", "[name=apparmor,profile=default name=seccomp,profile=builtin name=cgroupns]");
        NodeInstall.Engine rootful = NodeInstall.detectEngine(io(), null);
        assertThat(rootful.name()).isEqualTo("docker");
        assertThat(rootful.rootless()).isFalse();

        // And a rootless one, which is what --worker-containers requires (D-145).
        fakeEngine("docker", "[name=rootless name=seccomp,profile=builtin]");
        assertThat(NodeInstall.detectEngine(io(), null).rootless()).isTrue();
    }

    @Test
    void detectEngineReportsUnknownRootlessWhenTheDaemonDoesNotAnswer() throws IOException {
        // The CLI is present but `info` says nothing: an install may still proceed without a daemon (§1.9).
        Path bin = Files.createDirectories(work.resolve("bin-silent"));
        Path script = bin.resolve("docker");
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        script.toFile().setExecutable(true);
        env.put("PATH", bin.toString());

        NodeInstall.Engine engine = NodeInstall.detectEngine(io(), null);
        assertThat(engine.name()).isEqualTo("docker");
        assertThat(engine.rootless()).isNull();
    }

    @Test
    void aContainerInstallWritesEnvComposeMetaAndCertificates() throws Exception {
        fakeEngine("docker", "[name=rootless name=seccomp]");

        NodeInstall.installContainer(io(), false, null, null, "cluster93-node:test", null, tlsDir(),
                "0.0.0.0", true, false, true);

        NodeInstall.Layout layout = NodeInstall.containerLayout(io(), false, null);
        assertThat(layout.envFile()).exists();
        assertThat(layout.composeFile()).exists();
        assertThat(layout.tlsDir().resolve("node.crt")).exists();
        assertThat(layout.tlsDir().resolve("node.key")).exists();
        // ca.key is the cluster's signing key and must never land on the node (S5).
        assertThat(layout.tlsDir().resolve("ca.key")).doesNotExist();
        assertThat(layout.composeFile()).content().contains("image: cluster93-node:test");

        Map<String, String> meta = NodeCommand.loadKeyValues(NodeInstall.metaFile(layout));
        assertThat(meta).containsEntry("engine", "docker");
        assertThat(meta).containsEntry("engine_rootless", "true");
        assertThat(meta).containsEntry("image", "cluster93-node:test");
    }

    @Test
    void anInstalledContainerNodeIsFoundAgainWithItsComposeFile() throws Exception {
        fakeEngine("docker", "[name=rootless name=seccomp]");
        NodeInstall.installContainer(io(), false, null, null, "img", null, tlsDir(), "0.0.0.0", true, false, true);

        // installedLayout must round-trip the container kind, or the lifecycle verbs look for a service unit.
        NodeInstall.Layout found = NodeInstall.installedLayout(io());
        assertThat(found.container()).isTrue();
        assertThat(found.composeFile()).isEqualTo(NodeInstall.containerLayout(io(), false, null).composeFile());
        assertThat(found.dataDir()).isEqualTo(NodeInstall.containerLayout(io(), false, null).dataDir());
        assertThat(NodeService.detect(io(), found)).isEqualTo(NodeService.Backend.CONTAINER);
    }

    @Test
    void aContainerInstallRefusesASystemProcessInstallThatWouldShareItsPorts() throws Exception {
        Path otherEnv = Files.writeString(work.resolve("system-node.env"), "C93_NODE_ID=other\n");
        NodeInstall.Layout container = NodeInstall.containerLayout(io(), false, null);

        // A user container node facing a system process install: the ports would collide (D-146, extending D-140).
        assertThatThrownBy(() -> NodeInstall.refuseIfAlsoInstalled(container, otherEnv, false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("A system node is already installed")
                .hasMessageContaining("(c93 node uninstall --system)")
                .hasMessageContaining("same ports")
                .hasMessageContaining("--force");

        // --force installs both deliberately; a missing file is not a clash.
        NodeInstall.refuseIfAlsoInstalled(container, otherEnv, true);
        NodeInstall.refuseIfAlsoInstalled(container, work.resolve("missing.env"), false);
        NodeInstall.refuseIfAlsoInstalled(container, null, false);
    }

    @Test
    void buildingFromTheCheckoutRefusesClearlyWhenThereIsNothingToBuildFrom() {
        // The test runs from the cli module, so there is no server/target/c93.jar: the failure must name the fix,
        // not fail obscurely at `docker build`.
        NodeInstall.Engine engine = new NodeInstall.Engine("docker", Boolean.TRUE, "/usr/bin/docker");

        assertThatThrownBy(() -> NodeInstall.buildCheckoutImage(io(), engine, "local", true))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("server/target/c93.jar")
                .hasMessageContaining("pass --image");
    }

    @Test
    void theReleaseImageDockerfileShipsTheWorkersAndNeedsNoBuildStage() {
        String dockerfile = NodeInstall.dockerfileNode();

        // Without the workers a node image can only run embedded, which is the demo's limitation (D-142).
        assertThat(dockerfile).contains("COPY worker-javet worker-graaljs /opt/cluster93/bin/");
        assertThat(dockerfile).contains("chmod +x /opt/cluster93/bin/worker-javet");
        // It assembles artifacts, so it must not try to compile anything.
        assertThat(dockerfile).doesNotContain("mvnw");
        assertThat(dockerfile).doesNotContain("eclipse-temurin:21-jdk");
        // Javet's natives are glibc, and the heap ceiling keeps Ignite's off-heap region fitting beside the JVM.
        assertThat(dockerfile).contains("FROM docker.io/library/eclipse-temurin:21-jre");
        assertThat(dockerfile).contains("MaxRAMPercentage=40");
        assertThat(dockerfile).contains("USER c93");
    }

    @Test
    void aContainerInstallValidatesTheComposeItGenerated() throws Exception {
        // The fake engine answers `compose config -q` with success, which is what the install path now requires
        // before it will finish (§1.9).
        Path bin = Files.createDirectories(work.resolve("bin-validate"));
        Path script = bin.resolve("docker");
        Files.writeString(script, "#!/bin/sh\n"
                + "if [ \"$1\" = \"info\" ]; then echo '[name=rootless]'; exit 0; fi\n"
                + "if [ \"$1\" = \"compose\" ]; then exit 0; fi\n"
                + "exit 0\n");
        script.toFile().setExecutable(true);
        env.put("PATH", bin.toString());

        NodeInstall.installContainer(io(), false, null, null, "img", null, tlsDir(), "0.0.0.0", true, false, true);

        assertThat(NodeInstall.containerLayout(io(), false, null).composeFile()).exists();
    }

    @Test
    void anInstallIsRefusedWhenAPortItNeedsIsAlreadyHeld() throws Exception {
        // Hold a port the way a running node would, then try to install over it.
        try (java.net.ServerSocket held = new java.net.ServerSocket()) {
            held.bind(new java.net.InetSocketAddress("0.0.0.0", 0));
            int port = held.getLocalPort();
            env.remove("C93_SKIP_PORT_CHECK");

            assertThat(NodeInstall.portsInUse(java.util.List.of(port))).containsExactly(port);
            assertThatThrownBy(() -> NodeInstall.refuseIfPortsHeld(io(), java.util.List.of(port), false))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining(String.valueOf(port))
                    .hasMessageContaining("already in use")
                    .hasMessageContaining("--force");

            // --force installs anyway, and a free port is never a clash.
            NodeInstall.refuseIfPortsHeld(io(), java.util.List.of(port), true);
            NodeInstall.refuseIfPortsHeld(io(), java.util.List.of(), false);
        }
    }

    @Test
    void thePortCheckNamesEveryPortTheNodeWouldBind() {
        Map<String, String> container = NodeInstall.containerConfig("node-1");
        // The API, ingress and metrics, plus both of Ignite's ports when the node is clustered (D-142).
        assertThat(NodeInstall.hostPorts(container)).containsExactly(9393, 9394, 9395, 5701, 5801);

        Map<String, String> standalone = new java.util.HashMap<>(container);
        standalone.put("C93_CLUSTERED", "false");
        assertThat(NodeInstall.hostPorts(standalone)).containsExactly(9393, 9394, 9395);
    }

    @Test
    void aContainerInstallRefusesWhenItsPortsAreHeld() throws Exception {
        fakeEngine("docker", "[name=rootless name=seccomp]");
        env.remove("C93_SKIP_PORT_CHECK");
        // If this host already holds 9395 there is nothing left to prove here — that is the condition the check
        // catches, so skip rather than fail (a developer with a node running would otherwise see a red test).
        assumeThat(canBind(9395)).isTrue();
        try (java.net.ServerSocket held = new java.net.ServerSocket()) {
            held.bind(new java.net.InetSocketAddress("0.0.0.0", 9395));
            assertThatThrownBy(() -> NodeInstall.installContainer(io(), false, null, null, "img", null, tlsDir(),
                    "0.0.0.0", true, false, true))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("9395");
        }
    }

    /** Whether a port can be bound here, so a test that needs to hold one can skip on a busy host. */
    private static boolean canBind(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.bind(new java.net.InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void aSecondInstallOfTheOtherKindIsRefused(@TempDir Path dir) throws Exception {
        Path otherEnv = Files.writeString(dir.resolve("node.env"), "C93_NODE_ID=other\n");
        NodeInstall.Layout user = NodeInstall.userLayout(io(), dir.resolve("prog"));

        // A user install that finds a system install: both would use the same ports.
        assertThatThrownBy(() -> NodeInstall.refuseIfAlsoInstalled(user, otherEnv, false))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("A system node is already installed")
                .hasMessageContaining("(c93 node uninstall --system)")
                .hasMessageContaining("same ports")
                .hasMessageContaining("--force");

        // And the system install sees the user's env file the other way round.
        assertThatThrownBy(() -> NodeInstall.refuseIfAlsoInstalled(NodeInstall.systemLayout(), otherEnv, false))
                .hasMessageContaining("A user node is already installed")
                .hasMessageContaining("(c93 node uninstall)");

        // --force installs both deliberately (they then need different ports); no other install is a no-op.
        NodeInstall.refuseIfAlsoInstalled(user, otherEnv, true);
        NodeInstall.refuseIfAlsoInstalled(user, dir.resolve("missing.env"), false);
        NodeInstall.refuseIfAlsoInstalled(user, null, false);
    }

    @Test
    void thePasswdCheckMatchesTheUserExactly() {
        String passwd = "root:x:0:0:root:/root:/bin/bash\n"
                + "cluster93:x:999:999::/var/lib/cluster93:/usr/sbin/nologin\n"
                + "cluster930:x:1000:1000::/home/x:/bin/bash\n";

        assertThat(NodeInstall.passwdHasUser(passwd, "cluster93")).isTrue();
        assertThat(NodeInstall.passwdHasUser(passwd, "cluster930")).isTrue();
        assertThat(NodeInstall.passwdHasUser(passwd, "clustr")).isFalse();
        assertThat(NodeInstall.passwdHasUser("root:x:0:0:root:/root:/bin/bash\n", "cluster93")).isFalse();
    }
}
