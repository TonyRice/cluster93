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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The one-line installer (D-049), run for real against a local stand-in for the release host, with releases signed by
 * a key made for the test (D-069) and passed in with AUTOMATELY_RELEASE_PUBLIC_KEY.
 */
class InstallScriptTest {

    private static final Path INSTALL_SH = Path.of("..", "install", "install.sh").toAbsolutePath().normalize();
    private static final Path INSTALL_PS1 = Path.of("..", "install", "install.ps1").toAbsolutePath().normalize();
    private static final Path RELEASE_PEM = Path.of("..", "install", "automately-release.pem").toAbsolutePath().normalize();

    @TempDir
    Path work;

    private HttpServer server;
    private Path releases;
    private KeyPair releaseKey;
    private Path releasePem;

    static KeyPair newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String pem(KeyPair key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** What {@code openssl dgst -sha256 -sign} writes: a DER ECDSA signature. */
    static byte[] sign(byte[] data, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(data);
        return signer.sign();
    }

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

    /** A release of a stand-in binary, its SHA256SUMS signed by {@code signer} (none if null). */
    private void publish(String checksumOverride, PrivateKey signer) throws Exception {
        byte[] binary = "#!/bin/sh\necho 'automately 9.9.9'\n".getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve(asset()), binary);
        String sum = checksumOverride != null ? checksumOverride : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        byte[] sums = (sum + "  " + asset() + "\n" + "0".repeat(64) + "  automately-windows-x64.exe\n").getBytes(StandardCharsets.UTF_8);
        Files.write(releases.resolve("SHA256SUMS"), sums);
        if (signer != null) {
            Files.write(releases.resolve("SHA256SUMS.sig"), sign(sums, signer));
        }
    }

    private void publish(String checksumOverride) throws Exception {
        publish(checksumOverride, releaseKey.getPrivate());
    }

    private Process install(Path dir) throws IOException {
        return install(dir, Map.of());
    }

    private Process install(Path dir, Map<String, String> extra) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("sh", INSTALL_SH.toString()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("AUTOMATELY_INSTALL_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        env.put("AUTOMATELY_INSTALL_DIR", dir.toString());
        env.put("AUTOMATELY_RELEASE_PUBLIC_KEY", releasePem.toString());
        env.put("HOME", work.toString());
        env.putAll(extra);
        return builder.start();
    }

    private record Ran(int code, String output) {
    }

    private Ran run(Process process) throws Exception {
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Ran(process.exitValue(), output);
    }

    private static void assumeUnixWithCurlAndOpenssl() {
        assumeThat(System.getProperty("os.name").toLowerCase(Locale.ROOT)).doesNotStartWith("windows");
        assumeThat(onPath("curl")).isTrue();
        assumeThat(onPath("openssl")).isTrue();
    }

    private static boolean onPath(String command) {
        try {
            return new ProcessBuilder("sh", "-c", "command -v " + command).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void installsTheBinaryForThisPlatformAfterCheckingItsSignatureAndChecksum() throws Exception {
        assumeUnixWithCurlAndOpenssl();
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
        assumeUnixWithCurlAndOpenssl();
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
    void sumsSignedByAnotherKeyInstallNothing() throws Exception {
        assumeUnixWithCurlAndOpenssl();
        publish(null, newKey().getPrivate());
        Path bin = work.resolve("bin");

        Ran ran = run(install(bin));

        assertThat(ran.code()).isEqualTo(1);
        assertThat(ran.output()).contains("isn't signed by the automately release key").contains("Nothing was installed");
        assertThat(Files.exists(bin.resolve("automately"))).isFalse();
    }

    @Test
    void anUnsignedReleaseInstallsNothingUnlessSkippingIsAskedFor() throws Exception {
        assumeUnixWithCurlAndOpenssl();
        publish(null, null);
        Path bin = work.resolve("bin");

        Ran refused = run(install(bin));
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.output()).contains("SHA256SUMS.sig").contains("an unsigned one isn't installed");
        assertThat(Files.exists(bin.resolve("automately"))).isFalse();

        Ran skipped = run(install(bin, Map.of("AUTOMATELY_INSECURE_SKIP_SIGNATURE", "1")));
        assertThat(skipped.code()).as(skipped.output()).isZero();
        assertThat(skipped.output()).contains("not checking the release's signature");
    }

    @Test
    void theBuiltInKeyIsTheOneInTheRepositoryEverywhere() throws Exception {
        String key = Files.readString(RELEASE_PEM).strip();
        assertThat(key).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(Files.readString(INSTALL_SH)).contains("RELEASE_PUBLIC_KEY='" + key + "'");
        assertThat(Files.readString(INSTALL_PS1).replace("\r\n", "\n")).contains("@'\n" + key + "\n'@");
        try (var in = ReleaseSignature.class.getResourceAsStream("/automately-release.pem")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip()).isEqualTo(key);
        }
        // And it's a P-256 key the CLI can read.
        assertThat(ReleaseSignature.parse(key).getAlgorithm()).isEqualTo("EC");
    }

    @Test
    void theWindowsInstallerChecksSignaturesWithDotNet() throws Exception {
        assumeThat(onPath("pwsh")).as("PowerShell is installed").isTrue();
        byte[] sums = "abc  automately-windows-x64.exe\n".getBytes(StandardCharsets.UTF_8);
        Path sumsFile = Files.write(work.resolve("SHA256SUMS"), sums);
        // Many signatures, so r and s of every length (leading zeros dropped, or a 0x00 added) come up.
        for (int i = 0; i < 20; i++) {
            Files.write(work.resolve("good" + i + ".sig"), sign(sums, releaseKey.getPrivate()));
        }
        Files.write(work.resolve("other.sig"), sign(sums, newKey().getPrivate()));
        String script = "$ErrorActionPreference = 'Stop'; . ([ScriptBlock]::Create(((Get-Content -Raw '" + INSTALL_PS1
                + "') -replace '(?m)^Install-Automately\\s*$', '')));"
                + " foreach ($i in 0..19) { Confirm-AutomatelySignature '" + sumsFile + "' (Join-Path '" + work + "' \"good$i.sig\") };"
                + " Write-Output 'good ok';"
                + " try { Confirm-AutomatelySignature '" + sumsFile + "' '" + work.resolve("other.sig") + "'; Write-Output 'other accepted' }"
                + " catch { Write-Output ('other refused: ' + $_.Exception.Message) }";
        ProcessBuilder builder = new ProcessBuilder("pwsh", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true);
        builder.environment().put("AUTOMATELY_RELEASE_PUBLIC_KEY", releasePem.toString());
        Ran ran = run(builder.start());

        assertThat(ran.code()).as(ran.output()).isZero();
        assertThat(ran.output()).contains("good ok").contains("other refused: SHA256SUMS isn't signed by the automately release key");
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
