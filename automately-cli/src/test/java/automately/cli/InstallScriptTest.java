package automately.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The one-line installer (D-049), run for real against a local stand-in for the release host. */
class InstallScriptTest {

    private static final Path INSTALL_SH = Path.of("..", "install", "install.sh").toAbsolutePath().normalize();
    private static final Path INSTALL_PS1 = Path.of("..", "install", "install.ps1").toAbsolutePath().normalize();

    @TempDir
    Path work;

    private HttpServer server;
    private Path releases;

    @BeforeEach
    void serveReleases() throws IOException {
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
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("mac") ? "macos" : "linux";
        String arch = List.of("aarch64", "arm64").contains(System.getProperty("os.arch")) ? "arm64" : "x64";
        return "automately-" + os + "-" + arch;
    }

    private void publish(String checksumOverride) throws Exception {
        byte[] binary = "#!/bin/sh\necho 'automately 9.9.9'\n".getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve(asset()), binary);
        String sum = checksumOverride != null ? checksumOverride : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        Files.writeString(releases.resolve("SHA256SUMS"), sum + "  " + asset() + "\n" + "0".repeat(64) + "  automately-windows-x64.exe\n");
    }

    private Process install(Path dir) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("sh", INSTALL_SH.toString()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("AUTOMATELY_INSTALL_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        env.put("AUTOMATELY_INSTALL_DIR", dir.toString());
        env.put("HOME", work.toString());
        return builder.start();
    }

    private static boolean onPath(String command) {
        try {
            return new ProcessBuilder("sh", "-c", "command -v " + command).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void installsTheBinaryForThisPlatformAfterCheckingItsChecksum() throws Exception {
        assumeThat(System.getProperty("os.name").toLowerCase(Locale.ROOT)).doesNotStartWith("windows");
        assumeThat(onPath("curl")).isTrue();
        publish(null);
        Path bin = work.resolve("bin");

        Process process = install(bin);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("Installed automately 9.9.9 to " + bin.resolve("automately"));
        assertThat(Files.isExecutable(bin.resolve("automately"))).isTrue();
    }

    @Test
    void aChecksumMismatchInstallsNothing() throws Exception {
        assumeThat(System.getProperty("os.name").toLowerCase(Locale.ROOT)).doesNotStartWith("windows");
        assumeThat(onPath("curl")).isTrue();
        publish("f".repeat(64));
        Path bin = work.resolve("bin");

        Process process = install(bin);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains("Checksum mismatch").contains("Nothing was installed");
        assertThat(Files.exists(bin.resolve("automately"))).isFalse();
    }

    @Test
    void theWindowsInstallerParses() throws Exception {
        assumeThat(onPath("pwsh")).as("PowerShell is installed").isTrue();
        Process process = new ProcessBuilder("pwsh", "-NoProfile", "-Command",
                "$errors = $null; [System.Management.Automation.Language.Parser]::ParseFile('" + INSTALL_PS1 + "', [ref]$null, [ref]$errors)"
                        + " | Out-Null; if ($errors.Count) { $errors | ForEach-Object { $_.ToString() }; exit 1 }").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
    }
}
