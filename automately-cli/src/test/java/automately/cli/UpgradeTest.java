package automately.cli;

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
import java.security.PrivateKey;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/** {@code automately upgrade} (D-069): checks the release's signature and checksum before replacing anything. */
class UpgradeTest {

    private static final byte[] NEW_BINARY = "new automately".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path work;

    private HttpServer server;
    private Path releases;
    private KeyPair releaseKey;
    private Path releasePem;
    private Path binary;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void setUp() throws Exception {
        releaseKey = InstallScriptTest.newKey();
        releasePem = Files.writeString(work.resolve("release.pem"), InstallScriptTest.pem(releaseKey));
        binary = Files.writeString(work.resolve("automately"), "old automately");
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
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String asset() {
        return UpgradeCommand.asset(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private byte[] publish(byte[] content, PrivateKey signer) throws Exception {
        Files.write(releases.resolve(asset()), content);
        byte[] sums = (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(NEW_BINARY)) + "  " + asset() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve("SHA256SUMS"), sums);
        if (signer != null) {
            Files.write(releases.resolve("SHA256SUMS.sig"), InstallScriptTest.sign(sums, signer));
        }
        return sums;
    }

    private Result upgrade() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Map<String, String> env = Map.of("AUTOMATELY_INSTALL_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                "AUTOMATELY_RELEASE_PUBLIC_KEY", releasePem.toString());
        Io io = new Io(env, work.resolve("config"), new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(io, new String[]{"upgrade", "--to", binary.toString()});
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void replacesTheBinaryWhenTheSignatureAndChecksumHold() throws Exception {
        publish(NEW_BINARY, releaseKey.getPrivate());

        Result result = upgrade();

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(result.out()).contains("signature and checksum checked");
        assertThat(Files.readAllBytes(binary)).isEqualTo(NEW_BINARY);
        assertThat(Files.isExecutable(binary)).isTrue();
        try (var leftovers = Files.list(work)) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).noneMatch(name -> name.endsWith(".download"));
        }
    }

    @Test
    void anotherKeysSignatureChangesNothing() throws Exception {
        publish(NEW_BINARY, InstallScriptTest.newKey().getPrivate());

        Result result = upgrade();

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("isn't signed by the automately release key");
        assertThat(Files.readString(binary)).isEqualTo("old automately");
    }

    @Test
    void anUnsignedReleaseChangesNothing() throws Exception {
        publish(NEW_BINARY, null);

        Result result = upgrade();

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("SHA256SUMS.sig").contains("an unsigned one isn't installed");
        assertThat(Files.readString(binary)).isEqualTo("old automately");
    }

    @Test
    void aBinaryThatDoesntMatchItsSignedChecksumChangesNothing() throws Exception {
        publish("tampered".getBytes(StandardCharsets.UTF_8), releaseKey.getPrivate());

        Result result = upgrade();

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("Checksum mismatch").contains("Nothing was installed");
        assertThat(Files.readString(binary)).isEqualTo("old automately");
        try (var leftovers = Files.list(work)) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).noneMatch(name -> name.endsWith(".download"));
        }
    }

    @Test
    void acceptsWhatOpensslSignsWithAKeyFileLikeTheMaintainers() throws Exception {
        assumeThat(new ProcessBuilder("sh", "-c", "command -v openssl").start().waitFor()).isZero();
        Path key = work.resolve("signing.key");
        assertThat(new ProcessBuilder("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", key.toString())
                .inheritIO().start().waitFor()).isZero();
        assertThat(new ProcessBuilder("openssl", "pkey", "-in", key.toString(), "-pubout", "-out", releasePem.toString())
                .inheritIO().start().waitFor()).isZero();
        publish(NEW_BINARY, null);
        Process sign = new ProcessBuilder("openssl", "dgst", "-sha256", "-sign", key.toString(), "-out", releases.resolve("SHA256SUMS.sig").toString(),
                releases.resolve("SHA256SUMS").toString()).inheritIO().start();
        assertThat(sign.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(sign.exitValue()).isZero();

        Result result = upgrade();

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(Files.readAllBytes(binary)).isEqualTo(NEW_BINARY);
    }

    @Test
    void namesTheAssetForEachPlatformAsTheReleaseDoes() {
        assertThat(UpgradeCommand.asset("Linux", "amd64")).isEqualTo("automately-linux-x64");
        assertThat(UpgradeCommand.asset("Linux", "aarch64")).isEqualTo("automately-linux-arm64");
        assertThat(UpgradeCommand.asset("Mac OS X", "aarch64")).isEqualTo("automately-macos-arm64");
        assertThat(UpgradeCommand.asset("Mac OS X", "x86_64")).isEqualTo("automately-macos-x64");
        assertThat(UpgradeCommand.asset("Windows 11", "amd64")).isEqualTo("automately-windows-x64.exe");
        assertThatThrownBy(() -> UpgradeCommand.asset("Linux", "riscv64")).isInstanceOf(CliException.class);
        assertThat(UpgradeCommand.checksum("ab  x\ncd *automately-linux-x64\n", "automately-linux-x64")).isEqualTo("cd");
    }

    @Test
    void onAJvmItAsksWhichBinaryToReplace() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io io = new Io(Map.of("AUTOMATELY_INSTALL_URL", "http://127.0.0.1:1"), work, new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8), false);
        assertThat(Main.run(io, new String[]{"upgrade"})).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("pass --to FILE");
    }
}
