package automately.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/** Reruns the one-line installer (D-049), which replaces this binary with the latest release, or a pinned one. */
@Command(name = "upgrade", description = "Install the latest release of the CLI (or --version).")
final class UpgradeCommand extends Base {

    static final String DEFAULT_RELEASES = "adamnlynn/automately-v2";

    @Option(names = "--version", paramLabel = "TAG", description = "A release tag, e.g. v2.1.0.")
    String version;

    @Override
    int execute() throws Exception {
        String base = installBase(io());
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        List<String> command = windows
                ? List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "irm " + base + "/install.ps1 | iex")
                : List.of("sh", "-c", "curl -fsSL " + base + "/install.sh | sh");
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        if (version != null) {
            builder.environment().put("AUTOMATELY_VERSION", version);
        }
        out().println("Running the installer from " + base);
        return builder.start().waitFor();
    }

    /** Where install.sh, install.ps1 and the binaries are published: AUTOMATELY_INSTALL_URL, else GitHub releases. */
    static String installBase(Io io) {
        String explicit = io.env().get("AUTOMATELY_INSTALL_URL");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.endsWith("/") ? explicit.substring(0, explicit.length() - 1) : explicit;
        }
        String repo = io.env().getOrDefault("AUTOMATELY_RELEASES", DEFAULT_RELEASES);
        return "https://github.com/" + repo + "/releases/latest/download";
    }
}
