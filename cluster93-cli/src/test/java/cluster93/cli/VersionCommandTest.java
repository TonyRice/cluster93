package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
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
 * {@code c93 version}: the running build's identity, the checkout it was built from, whether the checkout
 * has moved on, and the cluster it talks to — all without a server.
 */
class VersionCommandTest {

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
        env.put("C93_HOME", home.toString());
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
        String head = LocalCommand.gitHead(io());
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
                    .contains("c93 local update");

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
}
