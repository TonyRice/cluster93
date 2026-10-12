package automately.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The {@code automately local ...} commands against a temp {@code $AUTOMATELY_HOME}: no daemon, no
 * server, and no built jar, so every check is the CLI reading the on-disk state the shell script writes.
 */
class LocalCommandTest {

    @TempDir
    Path home;

    private Path configDir;
    private Map<String, String> env;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void setUp() {
        configDir = home.resolve("config");
        env = new HashMap<>();
        env.put("AUTOMATELY_HOME", home.toString());
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
        Files.writeString(home.resolve("local.env"), """
                # Automately local master node configuration.
                NODE_ID="local-master"
                ROLES="api,ingress,worker,data"
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

    @Test
    void statusSaysSoWhenNothingIsSetUp() {
        Result result = run("local", "status");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("No local master node is set up").contains("scripts/local.sh setup");
    }

    @Test
    void statusReportsTheConfiguredNode() throws Exception {
        writeLocalEnv();

        Result result = run("local", "status");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out())
                .contains("Local master node")
                .contains("local-master")
                .contains("api,ingress,worker,data")
                .contains(home.resolve("data").toString())
                .contains("http://localhost:8080")
                .contains("http://localhost:8000")
                .contains("process")
                .contains("PID:            (not running)");
    }

    @Test
    void statusJsonHasTheState() throws Exception {
        writeLocalEnv();

        Result result = run("--json", "local", "status");

        assertThat(result.code()).as(result.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(result.out());
        assertThat(json.get("nodeId").asText()).isEqualTo("local-master");
        assertThat(json.get("apiUrl").asText()).isEqualTo("http://localhost:8080");
        assertThat(json.get("running").asBoolean()).isFalse();
    }

    @Test
    void statusFlagsAnUpdateWhenTheCheckoutMovedOn() throws Exception {
        writeLocalEnv();
        String head = LocalCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        Files.writeString(home.resolve("node.meta"), "git_commit=" + "0".repeat(40) + "\n");

        Result result = run("local", "status");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("Update Available").contains(head)
                .contains("scripts/local.sh update").contains("automately local update");
    }

    @Test
    void statusIsQuietWhenTheBuildMatchesHead() throws Exception {
        writeLocalEnv();
        String head = LocalCommand.gitHead(io());
        assumeThat(head).as("run in a git checkout").isNotNull();
        Files.writeString(home.resolve("node.meta"), "git_commit=" + head + "\n");

        Result result = run("local", "status");

        assertThat(result.code()).isZero();
        assertThat(result.out()).doesNotContain("Update Available");
    }

    @Test
    void mcpPrintsTheSavedConfiguration() throws Exception {
        Files.writeString(home.resolve("mcp.json"), """
                {"mcpServers": {"automately": {"type": "http", "url": "http://localhost:8080/mcp"}}}
                """);

        Result result = run("local", "mcp");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out()).contains("http://localhost:8080/mcp").contains("mcpServers");
    }

    @Test
    void mcpGeneratesTheConfigurationFromTheSavedLogin() throws Exception {
        writeLocalEnv();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), """
                {"url": "http://localhost:9999", "token": "atly_local_test_token"}
                """);

        Result result = run("local", "mcp");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out())
                .contains("http://localhost:9999/mcp")
                .contains("atly_local_test_token")
                .contains("claude mcp add --transport http automately")
                .contains("stdio bridge");
    }

    @Test
    void mcpJsonIsMachineReadable() throws Exception {
        writeLocalEnv();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), """
                {"url": "http://localhost:9999", "token": "atly_local_test_token"}
                """);

        Result result = run("--json", "local", "mcp");

        assertThat(result.code()).as(result.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(result.out());
        assertThat(json.at("/mcpServers/automately/url").asText()).isEqualTo("http://localhost:9999/mcp");
    }

    @Test
    void localWithoutASubcommandShowsWhatItCanDo() {
        Result result = run("local");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("status").contains("start").contains("stop").contains("logs").contains("mcp");
    }

    @Test
    void helpAndBadArgumentsBehave() {
        assertThat(run("--help").out()).contains("local").contains("Manage the local Automately master node");
        assertThat(run("local").out()).contains("status").contains("start").contains("setup");

        Result badLines = run("local", "logs", "--lines", "not-a-number");
        assertThat(badLines.code()).isEqualTo(2);

        Result unknown = run("local", "not-a-command");
        assertThat(unknown.code()).isEqualTo(2);
    }
}
