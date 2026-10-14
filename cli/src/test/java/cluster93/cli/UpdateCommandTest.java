package cluster93.cli;

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
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code c93 update [--cli] [--node]} (D-138): the front door for updating. {@code --cli} replaces the binary or jar
 * after checking the release's signature and checksum; {@code --node} updates the node; with neither, both run. The
 * node side is a checkout in these tests (a fake {@code scripts/local.sh} records its invocation), so nothing rebuilds.
 */
class UpdateCommandTest {

    @TempDir
    Path work;

    private HttpServer server;
    private Path releases;
    private KeyPair releaseKey;
    private Path releasePem;
    private Path checkout;
    private Path nodeCalls;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void setUp() throws Exception {
        releaseKey = InstallScriptTest.newKey();
        releasePem = Files.writeString(work.resolve("release.pem"), InstallScriptTest.pem(releaseKey));
        releases = Files.createDirectories(work.resolve("releases"));
        nodeCalls = work.resolve("node-update.calls");
        // A fake checkout, so `--node` takes the source path instead of running the real scripts/local.sh.
        checkout = Files.createDirectories(work.resolve("checkout"));
        Files.createDirectories(checkout.resolve("scripts"));
        Path script = Files.writeString(checkout.resolve("scripts").resolve("local.sh"),
                "#!/bin/sh\necho \"$*\" >> \"" + nodeCalls + "\"\n");
        script.toFile().setExecutable(true);

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
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Publishes a signed {@code cluster93-cli.jar} (what {@code --cli} fetches for a jar target). */
    private void publishCliJar() throws Exception {
        byte[] jar = "new cli jar".getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve(UpgradeCommand.CLI_JAR), jar);
        byte[] sums = (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)) + "  " + UpgradeCommand.CLI_JAR + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve("SHA256SUMS"), sums);
        Files.write(releases.resolve("SHA256SUMS.sig"), InstallScriptTest.sign(sums, releaseKey.getPrivate()));
    }

    private Result run(String... args) {
        Map<String, String> env = new HashMap<>();
        env.put("C93_INSTALL_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        env.put("C93_RELEASE_PUBLIC_KEY", releasePem.toString());
        env.put("C93_REPO", checkout.toString());
        env.put("HOME", work.toString());
        env.put("C93_CONFIG_DIR", work.resolve("config").toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io io = new Io(env, work.resolve("config"), new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(io, args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path cliTarget() throws Exception {
        return Files.writeString(work.resolve("c93.jar"), "old cli jar");
    }

    @Test
    void updateCliReplacesTheJarAfterCheckingTheRelease() throws Exception {
        publishCliJar();
        Path target = cliTarget();

        Result result = run("update", "--cli", "--to", target.toString(), "--version", "v9.9.9");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out()).contains(UpgradeCommand.CLI_JAR).contains("signature and checksum checked");
        assertThat(Files.readString(target)).isEqualTo("new cli jar");
        assertThat(nodeCalls).as("--cli alone leaves the node alone").doesNotExist();
    }

    @Test
    void withNeitherFlagItUpdatesBoth() throws Exception {
        publishCliJar();
        Path target = cliTarget();

        Result result = run("update", "--to", target.toString());

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(Files.readString(target)).as("the CLI was updated").isEqualTo("new cli jar");
        assertThat(Files.readString(nodeCalls)).as("the node update ran too").contains("update");
    }

    @Test
    void updateNodeAloneLeavesTheCliAlone() throws Exception {
        Path target = cliTarget();

        Result result = run("update", "--node");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(Files.readString(nodeCalls)).contains("update");
        assertThat(Files.readString(target)).as("the CLI was not touched").isEqualTo("old cli jar");
    }

    @Test
    void theNodeUpdateAliasStillWorks() throws Exception {
        Result result = run("node", "update");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(Files.readString(nodeCalls)).contains("update");
    }

    @Test
    void aTamperedJarIsRefused() throws Exception {
        // The checksum names a different jar than the one served.
        Files.write(releases.resolve(UpgradeCommand.CLI_JAR), "tampered".getBytes(StandardCharsets.UTF_8));
        byte[] sums = ("f".repeat(64) + "  " + UpgradeCommand.CLI_JAR + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve("SHA256SUMS"), sums);
        Files.write(releases.resolve("SHA256SUMS.sig"), InstallScriptTest.sign(sums, releaseKey.getPrivate()));
        Path target = cliTarget();

        Result result = run("update", "--cli", "--to", target.toString());

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("Checksum mismatch").contains("Nothing was installed");
        assertThat(Files.readString(target)).isEqualTo("old cli jar");
    }

    @Test
    void theHelpListsUpdateButNotTheHiddenAliases() {
        Result help = run("--help");

        assertThat(help.code()).isZero();
        assertThat(help.out()).contains("update");
        assertThat(help.out()).as("`c93 upgrade` is a hidden alias").doesNotContain("  upgrade ");
    }
}
