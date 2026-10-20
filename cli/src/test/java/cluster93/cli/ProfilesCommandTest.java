package cluster93.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Connection profiles (D-189): isolation, selection precedence, the legacy default, and the hidden old name. */
class ProfilesCommandTest {

    @TempDir
    Path home;

    private Io io() {
        return io(Map.of());
    }

    private Io io(Map<String, String> env) {
        return new Io(env, home.resolve("config"), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), false);
    }

    private Result run(String... args) {
        return run(Map.of(), args);
    }

    private Result run(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io io = new Io(env, home.resolve("config"), new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(io, args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {
    }

    @Test
    void createUseAndListProfiles() throws Exception {
        assertThat(run("profiles").out()).contains("default").contains("* default");

        Result created = run("profiles", "create", "work");
        assertThat(created.code()).as(created.err()).isZero();
        assertThat(Files.isDirectory(home.resolve("config").resolve("profiles/work"))).isTrue();

        assertThat(run("profiles", "use", "work").code()).isZero();
        assertThat(Files.readString(home.resolve("config").resolve("current-profile"))).contains("work");
        assertThat(run("profiles").out()).contains("* work").contains("  default");

        assertThat(run("profiles", "show", "work").out()).contains("Profile:  work").contains("Token:   -");
    }

    @Test
    void aProfileKeepsItsOwnLoginAndDoesNotTouchTheDefault() {
        Io io = io();
        new Config("http://prod:9393", "prod-token", "prod").save(io, "default");
        new Config("http://dev:9393", "dev-token", "dev").save(io, "dev");

        assertThat(Config.load(io, "default").token()).isEqualTo("prod-token");
        assertThat(Config.load(io, "dev").token()).isEqualTo("dev-token");
        assertThat(Config.resolve(io, "dev", null, null, null).token()).isEqualTo("dev-token");
        assertThat(Config.resolve(io, "default", null, null, null).token()).isEqualTo("prod-token");

        // The active profile is a marker, so a --profile invocation does not rewrite it.
        ProfileStore.use(io, "dev");
        assertThat(ProfileStore.active(io, null)).isEqualTo("dev");
        assertThat(ProfileStore.active(io, "default")).isEqualTo("default");
        assertThat(Config.load(io, "default").token()).isEqualTo("prod-token");
    }

    @Test
    void theLegacyConfigIsTheDefaultProfile() throws Exception {
        Path config = home.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("config.json"),
                "{\"url\":\"http://legacy:9393\",\"token\":\"legacy-token\",\"tenant\":\"legacy\"}");

        assertThat(run("profiles").out()).contains("* default");
        assertThat(run("profiles", "show").out()).contains("http://legacy:9393").contains("legacy");
        assertThat(Config.load(io(), "default").token()).isEqualTo("legacy-token");
    }

    @Test
    void removingProfilesRefusesTheActiveAndDefault() {
        assertThat(run("profiles", "create", "work").code()).isZero();
        assertThat(run("profiles", "use", "work").code()).isZero();
        Result active = run("profiles", "rm", "work");
        assertThat(active.code()).isNotZero();
        assertThat(active.err()).contains("active");

        assertThat(run("profiles", "use", "default").code()).isZero();
        assertThat(run("profiles", "rm", "work").code()).isZero();
        assertThat(Files.exists(home.resolve("config").resolve("profiles/work"))).isFalse();

        Result implicit = run("profiles", "rm", "default");
        assertThat(implicit.code()).isNotZero();
        assertThat(implicit.err()).contains("default");
    }

    @Test
    void aProfileCanPointAtAClusterBundle() throws Exception {
        Path bundle = Files.createDirectories(home.resolve("cluster-tls"));
        assertThat(run("profiles", "create", "prod", "--cluster", bundle.toString()).code()).isZero();
        assertThat(run("profiles", "show", "prod").out()).contains(bundle.toAbsolutePath().toString());
        assertThat(ProfileStore.meta(io(), "prod").get("cluster").asText())
                .isEqualTo(bundle.toAbsolutePath().toString());
    }

    @Test
    void theEnvironmentSelectsAProfileWhenNoFlagDoes() {
        new Config("http://env:9393", "env-token", "env").save(io(), "env");
        assertThat(Config.resolve(io(Map.of("C93_PROFILE", "env")), null, null, null, null).token())
                .isEqualTo("env-token");
        assertThat(ProfileStore.active(io(Map.of("C93_PROFILE", "env")), null)).isEqualTo("env");
        assertThat(ProfileStore.active(io(Map.of("C93_PROFILE", "env")), "default")).isEqualTo("default");
    }
}
