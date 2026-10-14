package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * {@code c93 version}: the running build's identity, the checkout it was built from, whether the checkout
 * has moved on, and the cluster it talks to — all without a server.
 */
class VersionCommandTest {

    @TempDir
    Path home;

    private Path configDir;
    private Map<String, String> env;
    private HttpServer server;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void setUp() {
        configDir = home.resolve("config");
        env = new HashMap<>();
        env.put("C93_HOME", home.toString());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
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

    @Test
    void versionPrintsTheBuildWithoutACluster() {
        Result result = run("version");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out()).startsWith("c93 ").contains(Main.version());
        assertThat(result.out()).doesNotContain("cluster ");
        assertThat(result.out()).doesNotContain("Update Available");
    }

    @Test
    void versionJsonCarriesTheBuildAndRepoMetadata() {
        Result result = run("version", "--json");

        assertThat(result.code()).as(result.err()).isZero();
        JsonNode json = Json.parse(result.out());
        assertThat(json.get("version").asText()).isEqualTo(Main.version());
        assertThat(json.get("commit").asText()).isEqualTo(Main.commit());
        assertThat(json.get("builtAt").asText()).isEqualTo(Main.builtAt());
        assertThat(json.has("repoRoot")).isTrue();
        assertThat(json.has("repoHead")).isTrue();
        assertThat(json.get("updateAvailable").isBoolean()).isTrue();
    }

    @Test
    void updateIsNotFlaggedForADevBuildOrAMatchingCommit() {
        assertThat(VersionCommand.updateAvailable("dev", "2".repeat(40))).isFalse();
        assertThat(VersionCommand.updateAvailable("2".repeat(40), "2".repeat(40))).isFalse();
        assertThat(VersionCommand.updateAvailable("1".repeat(40), null)).isFalse();
    }

    @Test
    void versionFlagsAnUpdateWhenTheRepoHeadDiffersFromTheBuildCommit() {
        String head = NodeCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        String build = "0".repeat(40);
        assumeThat(build).as("the simulated build commit differs from HEAD").isNotEqualTo(head);
        System.setProperty("cluster93.commit", build);
        try {
            Result text = run("version");
            assertThat(text.code()).as(text.err()).isZero();
            assertThat(text.out())
                    .contains("c93 " + Main.version() + " (commit " + build.substring(0, 8) + ")")
                    .contains("Update Available")
                    .contains("Current CLI build: " + build.substring(0, 8))
                    .contains("Current repo HEAD: " + head.substring(0, 8))
                    .contains("scripts/build-cli.sh")
                    .contains("c93 update --node");

            Result json = run("--json", "version");
            assertThat(json.code()).as(json.err()).isZero();
            JsonNode parsed = Json.parse(json.out());
            assertThat(parsed.get("commit").asText()).isEqualTo(build);
            assertThat(parsed.get("repoHead").asText()).isEqualTo(head);
            assertThat(parsed.get("updateAvailable").asBoolean()).isTrue();
        } finally {
            System.clearProperty("cluster93.commit");
        }
    }

    @Test
    void versionIsGracefulOutsideAGitRepository() throws Exception {
        // A repo-root candidate without a .git directory: detection finds the checkout, but no HEAD.
        Path checkout = home.resolve("checkout");
        Files.createDirectories(checkout.resolve("scripts"));
        Files.writeString(checkout.resolve("scripts").resolve("local.sh"), "#!/usr/bin/env bash\n");
        env.put("C93_REPO", checkout.toString());

        Result result = run("--json", "version");

        assertThat(result.code()).as(result.err()).isZero();
        JsonNode json = Json.parse(result.out());
        assertThat(json.get("repoRoot").asText()).isEqualTo(checkout.toString());
        assertThat(json.get("repoHead").asText()).isEmpty();
        assertThat(json.get("updateAvailable").asBoolean()).isFalse();
    }

    @Test
    void versionIsGracefulWhenTheClusterIsOffline() throws Exception {
        saveLogin("http://127.0.0.1:1");

        Result result = run("version");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out()).contains("cluster http://127.0.0.1:1 (unreachable:");
    }

    @Test
    void versionJsonReportsAnOfflineCluster() throws Exception {
        saveLogin("http://127.0.0.1:1");

        Result result = run("--json", "version");

        assertThat(result.code()).as(result.err()).isZero();
        JsonNode json = Json.parse(result.out());
        assertThat(json.at("/cluster/url").asText()).isEqualTo("http://127.0.0.1:1");
        assertThat(json.at("/cluster/status").asText()).isEqualTo("unreachable");
    }

    private void saveLogin(String url) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"),
                "{\"url\": \"" + url + "\", \"token\": \"c93_local_test_token\"}\n");
    }

    // ----------------------------------------------------------------------
    // web console status (admin-only)
    // ----------------------------------------------------------------------

    @Test
    void consoleStatusIsANonAdminNoOpWithoutALogin() {
        VersionCommand.ConsoleStatus status = VersionCommand.checkConsoleStatus(root(), repoRoot(), io());

        assertThat(status.isAdmin()).isFalse();
        assertThat(status.deployedVersion()).isEmpty();
        assertThat(status.deployedCommit()).isEmpty();
        assertThat(status.updateAvailable()).isFalse();
    }

    @Test
    void adminSeesTheDeployedConsoleAndAnUpdateWhenTheCheckoutMovedOn() throws Exception {
        String head = NodeCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        String deployed = "0".repeat(40);
        assumeThat(deployed).as("the simulated console build differs from HEAD").isNotEqualTo(head);
        startCluster(true, deployed);

        Result text = run("version");
        assertThat(text.code()).as(text.err()).isZero();
        assertThat(text.out())
                .contains("console 2.0.0 (commit " + deployed.substring(0, 8) + ")")
                .contains("Update Available! Web Console build: " + deployed.substring(0, 8))
                .contains("Local repo HEAD: " + head.substring(0, 8))
                .contains("c93 update --node")
                .contains("c93 apps deploy console");

        Result json = run("--json", "version");
        JsonNode parsed = Json.parse(json.out());
        assertThat(parsed.at("/console/isAdmin").asBoolean()).isTrue();
        assertThat(parsed.at("/console/deployedVersion").asText()).isEqualTo("2.0.0");
        assertThat(parsed.at("/console/deployedCommit").asText()).isEqualTo(deployed);
        assertThat(parsed.at("/console/localCommit").asText()).isEqualTo(head);
        assertThat(parsed.at("/console/updateAvailable").asBoolean()).isTrue();
    }

    @Test
    void adminSeesNoUpdateWhenTheDeployedCommitMatchesHead() throws Exception {
        String head = NodeCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        startCluster(true, head);

        Result text = run("version");

        assertThat(text.code()).as(text.err()).isZero();
        assertThat(text.out()).contains("console 2.0.0 (commit " + head.substring(0, 8) + ")");
        assertThat(text.out()).doesNotContain("Web Console build");

        Result json = run("--json", "version");
        assertThat(Json.parse(json.out()).at("/console/updateAvailable").asBoolean()).isFalse();
    }

    @Test
    void nonAdminNeverSeesTheConsoleOrAnUpdatePrompt() throws Exception {
        String head = NodeCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        startCluster(false, "0".repeat(40));

        Result text = run("version");

        assertThat(text.code()).as(text.err()).isZero();
        assertThat(text.out()).doesNotContain("console ").doesNotContain("Web Console build");

        Result json = run("--json", "version");
        JsonNode parsed = Json.parse(json.out());
        assertThat(parsed.at("/console/isAdmin").asBoolean()).isFalse();
        assertThat(parsed.at("/console/updateAvailable").asBoolean()).isFalse();
    }

    @Test
    void aMissingConsoleDeploymentIsGraceful() throws Exception {
        String head = NodeCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        startCluster(true, null);

        Result text = run("version");

        assertThat(text.code()).as(text.err()).isZero();
        assertThat(text.out()).doesNotContain("console ").doesNotContain("Web Console build");

        Result json = run("--json", "version");
        JsonNode parsed = Json.parse(json.out());
        assertThat(parsed.at("/console/isAdmin").asBoolean()).isTrue();
        assertThat(parsed.at("/console/deployedCommit").asText()).isEmpty();
        assertThat(parsed.at("/console/updateAvailable").asBoolean()).isFalse();
    }

    /** A stub cluster: {@code /health}, {@code /v1/whoami}, and (when {@code deployedCommit != null}) {@code system/console}. */
    private void startCluster(boolean admin, String deployedCommit) throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("/health", "{\"node\": \"stub\", \"status\": \"ok\"}");
        responses.put("/v1/whoami", admin
                ? "{\"admin\": true, \"user\": \"admin\"}"
                : "{\"admin\": false, \"tenant\": \"acme\", \"user\": \"alice\", \"scopes\": [\"jobs:read\"]}");
        if (deployedCommit != null) {
            responses.put("/v1/apps/console", "{\"name\": \"console\", \"activeDeploymentId\": \"dpl_console1\"}");
            responses.put("/v1/apps/console/deployments/dpl_console1",
                    "{\"deploymentId\": \"dpl_console1\", \"manifest\": {\"name\": \"console\", \"version\": \"2.0.0\"},"
                            + " \"commitHash\": \"" + deployedCommit + "\"}");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = responses.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        saveLogin("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private cluster93.cli.Config root() {
        return Config.resolve(io(), null, null, null);
    }

    private Path repoRoot() {
        return NodeCommand.repoRoot(io());
    }
}
