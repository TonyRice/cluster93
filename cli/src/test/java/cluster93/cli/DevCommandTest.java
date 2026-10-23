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

/** `c93 dev` and the one-release `c93 local` alias (D-190), without starting a real node. */
class DevCommandTest {

    @TempDir
    Path home;

    private Path repo;

    private void checkout() throws Exception {
        repo = Files.createDirectories(home.resolve("repo"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        Files.createDirectories(repo.resolve("server"));
        Files.createDirectories(repo.resolve("worker"));
        Files.createDirectories(repo.resolve("server/target"));
        Files.write(repo.resolve("server/target/c93.jar"), new byte[] {1});
        Files.createDirectories(repo.resolve("bin"));
        for (String worker : new String[] {"worker-javet", "worker-graaljs"}) {
            Path binary = repo.resolve("bin").resolve(worker);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true, false);
        }
        Path mvnw = repo.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nmkdir -p server/target\n: > server/target/c93.jar\n");
        mvnw.toFile().setExecutable(true, false);
        Files.createDirectories(repo.resolve("examples/js"));
    }

    private Result run(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io io = new Io(Map.of("HOME", home.toString(), "C93_REPO", repo.toString()), home.resolve("config"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(io, args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void buildAndStatusWorkFromACheckout() throws Exception {
        checkout();
        Result build = run("dev", "build");
        assertThat(build.code()).as(build.err()).isZero();
        assertThat(build.out()).contains("Build complete");

        Result status = run("dev", "status");
        assertThat(status.code()).as(status.err()).isZero();
        assertThat(status.out()).contains("Cluster93 development stack").contains("not running");
    }

    @Test
    void localIsTheDeprecatedAliasOfDev() throws Exception {
        checkout();
        Result bare = run("local");
        assertThat(bare.code()).isZero();
        assertThat(bare.err()).contains("deprecated").contains("c93 dev");
        assertThat(bare.out()).contains("up").contains("status");
    }

    @Test
    void resetDeletesTheDevDataAfterStopping() throws Exception {
        checkout();
        Io io = new Io(Map.of("HOME", home.toString(), "C93_REPO", repo.toString()), home.resolve("config"),
                new ByteArrayInputStream(new byte[0]), System.out, System.err, false);
        com.fasterxml.jackson.databind.node.ObjectNode meta = Json.object();
        meta.set("dev", Json.object().put("dataDir", home.resolve("dev-data").toString()));
        ProfileStore.writeMeta(io, "dev", meta);
        Files.createDirectories(home.resolve("dev-data"));

        Result reset = run("dev", "reset", "--yes");
        assertThat(reset.code()).as(reset.err()).isZero();
        assertThat(home.resolve("dev-data")).doesNotExist();
    }

    @Test
    void resetRefusesWithoutYesWhenNotInteractive() throws Exception {
        checkout();
        Result reset = run("dev", "reset");
        assertThat(reset.code()).isNotZero();
        assertThat(reset.err()).contains("--yes");
    }

    private record Result(int code, String out, String err) {
    }
}
