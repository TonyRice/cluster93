package cluster93.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Replaces this binary with the latest release, or a pinned one (D-049). It does what the installer does, in-process:
 * checks SHA256SUMS against the release key built into this binary (D-069), then the download against SHA256SUMS, and
 * only then swaps the file. Nothing from the network runs before it's checked.
 */
@Command(name = "upgrade", description = "Install the latest release of the CLI (or --version), after checking its signature.")
final class UpgradeCommand extends Base {

    static final String DEFAULT_RELEASES = "adamnlynn/cluster93";

    @Option(names = "--version", paramLabel = "TAG", description = "A release tag, e.g. v2.1.0.")
    String version;

    @Option(names = "--to", paramLabel = "FILE", description = "The binary to replace (default: this one).")
    Path to;

    @Override
    int execute() throws Exception {
        String base = installBase(io(), version);
        if (!base.startsWith("https://") && io().env().get("C93_INSTALL_URL") == null) {
            throw new CliException("Releases are downloaded over HTTPS only: " + base);
        }
        Path target = to != null ? to.toAbsolutePath() : currentBinary();
        String asset = asset(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

        out().println("Downloading " + asset + " from " + base);
        byte[] sums = fetch(http, base + "/SHA256SUMS");
        byte[] signature;
        try {
            signature = fetch(http, base + "/SHA256SUMS.sig");
        } catch (CliException e) {
            throw new CliException(e.getMessage() + ". Releases are signed; an unsigned one isn't installed.");
        }
        if (!ReleaseSignature.verify(sums, signature, ReleaseSignature.key(io()))) {
            throw new CliException("SHA256SUMS isn't signed by the c93 release key. Nothing was installed.");
        }
        String expected = checksum(new String(sums, StandardCharsets.UTF_8), asset);

        Path download = Files.createTempFile(target.getParent(), ".cluster93-", ".download");
        try {
            String actual = download(http, base + "/" + asset, download);
            if (!actual.equals(expected)) {
                throw new CliException("Checksum mismatch for " + asset + ": expected " + expected + ", got " + actual + ". Nothing was installed.");
            }
            download.toFile().setExecutable(true, false);
            if (isWindows()) {
                // A running .exe can't be overwritten, but it can be renamed out of the way.
                Path old = target.resolveSibling(target.getFileName() + ".old");
                Files.deleteIfExists(old);
                if (Files.exists(target)) {
                    Files.move(target, old);
                }
            }
            Files.move(download, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(download);
        }
        out().println("Installed " + asset + " (signature and checksum checked) to " + target);
        return 0;
    }

    /** Where the binaries are published: C93_INSTALL_URL, else GitHub releases (a tag's, or the latest). */
    static String installBase(Io io, String version) {
        String explicit = io.env().get("C93_INSTALL_URL");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.endsWith("/") ? explicit.substring(0, explicit.length() - 1) : explicit;
        }
        String repo = io.env().getOrDefault("C93_RELEASES", DEFAULT_RELEASES);
        return version != null
                ? "https://github.com/" + repo + "/releases/download/" + version
                : "https://github.com/" + repo + "/releases/latest/download";
    }

    /** The release asset for a platform, named as release.yml names them. */
    static String asset(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new CliException("No c93 build for " + osArch + " processors");
        };
        if (os.startsWith("windows")) {
            if (!arch.equals("x64")) {
                throw new CliException("No c93 build for " + osArch + " Windows yet; builds exist for x64");
            }
            return "cluster93-windows-x64.exe";
        }
        if (os.startsWith("mac")) {
            return "cluster93-macos-" + arch;
        }
        if (os.startsWith("linux")) {
            return "cluster93-linux-" + arch;
        }
        throw new CliException("No c93 build for " + osName);
    }

    /** The asset's SHA-256 from a SHA256SUMS file ({@code hash  name}, or {@code hash *name}). */
    static String checksum(String sums, String asset) {
        for (String line : sums.split("\n")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2 && (parts[1].equals(asset) || parts[1].equals("*" + asset))) {
                return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        throw new CliException("SHA256SUMS has no entry for " + asset);
    }

    private static Path currentBinary() {
        // Set by native-image; on a JVM, "this binary" would be java itself.
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
            throw new CliException("upgrade replaces the native binary, and this is running on a JVM; pass --to FILE");
        }
        return ProcessHandle.current().info().command().map(Path::of)
                .orElseThrow(() -> new CliException("Can't tell where this binary is; pass --to FILE"))
                .toAbsolutePath();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static byte[] fetch(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new CliException("Download failed: " + url + " (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    /** Streams the file to disk, returning its SHA-256. */
    private static String download(HttpClient http, String url, Path into) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new CliException("Download failed: " + url + " (HTTP " + response.statusCode() + ")");
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(into)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    sha256.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
