package cluster93.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Installs a Cluster93 node from a signed GitHub release: fetch the artifacts, check the signature and every
 * checksum, lay out the program tree, write {@code node.env} and a service unit. The one implementation behind both
 * {@code c93 node install} and the shell installer's {@code --node} (D-134).
 *
 * <p>It talks to a release host with the JDK's {@link HttpClient} only, so a native {@code c93} needs nothing else on
 * the machine. Every placement is a {@link Layout}, resolved from the flags and the platform, and rendered into the
 * unit rather than shipping absolute paths, so {@code --dir} works.
 */
final class NodeInstall {

    static final String SERVER_JAR = "c93.jar";
    static final String CLI_JAR = "cluster93-cli.jar";
    static final String WORKER_JAVET = "worker-javet.gz";
    static final String WORKER_GRAALJS = "worker-graaljs.gz";
    static final String SUMS = "SHA256SUMS";
    static final String SIGNATURE = "SHA256SUMS.sig";

    /** The unit templates, mirrored for reference at {@code install/systemd/} and {@code install/launchd/}. */
    static final String LAUNCHD_LABEL = "com.cluster93.node";
    /** The system user a system install runs as (the unit's {@code User=}), created by the installer (D-136). */
    static final String SYSTEM_USER = "cluster93";
    /** The key {@code C93_INSECURE_SKIP_SIGNATURE} that turns off the signature check, as in the installers. */
    static final String SKIP_SIGNATURE = "C93_INSECURE_SKIP_SIGNATURE";

    private NodeInstall() {
    }

    /** Where an installed node's pieces live: resolved from the platform and the flags. */
    record Layout(boolean system, Path programDir, Path envFile, Path dataDir, Path logDir, Path unitFile,
                  Kind unitKind, String owner) {

        /** What kind of unit (if any) this layout was installed with. */
        enum Kind { SYSTEMD_SYSTEM, SYSTEMD_USER, LAUNCHD, NONE }
    }

    // ----------------------------------------------------------------------
    // layouts
    // ----------------------------------------------------------------------

    /** The invoking user's home: {@code HOME}, else the JVM's. */
    static Path home(Io io) {
        String home = value(io.env().get("HOME"));
        return home != null ? Path.of(home) : Path.of(System.getProperty("user.home", "."));
    }

    /** Per-user data root: {@code XDG_DATA_HOME}, else {@code ~/.local/share}. */
    static Path dataHome(Io io) {
        String xdg = value(io.env().get("XDG_DATA_HOME"));
        return xdg != null ? Path.of(xdg) : home(io).resolve(".local").resolve("share");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    /** The system install's layout (Linux only): {@code /opt}, {@code /etc}, {@code /var/lib}, root-owned. */
    static Layout systemLayout() {
        return new Layout(true, Path.of("/opt/cluster93"), Path.of("/etc/cluster93/node.env"),
                Path.of("/var/lib/cluster93"), null, Path.of("/etc/systemd/system/cluster93.service"),
                Layout.Kind.SYSTEMD_SYSTEM, "cluster93");
    }

    /**
     * The invoking user's layout. {@code dir} overrides the program directory; the config, data and unit locations
     * follow the caller's home and config directory, so a test with a temp config never touches the real machine.
     */
    static Layout userLayout(Io io, Path dir) {
        Path programDir = dir != null ? dir.toAbsolutePath() : dataHome(io).resolve("cluster93");
        Path envFile = io.configDir().resolve("node.env");
        Path dataDir = dataHome(io).resolve("cluster93").resolve("data");
        Path logDir = dataDir.resolve("logs");
        Path unitFile;
        Layout.Kind kind;
        if (isMac()) {
            unitFile = home(io).resolve("Library").resolve("LaunchAgents").resolve(LAUNCHD_LABEL + ".plist");
            kind = Layout.Kind.LAUNCHD;
        } else if (isWindows()) {
            unitFile = null;
            kind = Layout.Kind.NONE;
        } else {
            Path configHome = io.configDir().getParent() != null ? io.configDir().getParent() : io.configDir();
            unitFile = configHome.resolve("systemd").resolve("user").resolve("cluster93.service");
            kind = Layout.Kind.SYSTEMD_USER;
        }
        return new Layout(false, programDir, envFile, dataDir, logDir, unitFile, kind, System.getProperty("user.name", ""));
    }

    /** The layout of the node that is installed, system when its config exists, else the user's. */
    static Layout installedLayout(Io io) {
        if (Files.isRegularFile(systemLayout().envFile())) {
            return systemLayout();
        }
        Layout user = userLayout(io, null);
        // `--dir` isn't derivable from the config path, so `install` records where it put the node; read it back.
        Map<String, String> meta = NodeCommand.loadKeyValues(metaFile(user));
        String programDir = meta.get("program_dir");
        if (programDir != null && !programDir.isBlank()) {
            return userLayout(io, Path.of(programDir));
        }
        return user;
    }

    /**
     * Refuses a second install of the other kind (user vs system) unless {@code force}. Both default to the same
     * ports, so one has to go — or they must be given different ports deliberately (D-140).
     */
    static void refuseIfAlsoInstalled(Layout layout, Path otherEnv, boolean force) {
        if (force || otherEnv == null || !Files.isRegularFile(otherEnv)) {
            return;
        }
        String other = layout.system() ? "user" : "system";
        String uninstall = layout.system() ? "c93 node uninstall" : "c93 node uninstall --system";
        throw new CliException("A " + other + " node is already installed (" + otherEnv + "), and both would use "
                + "the same ports. Uninstall it first (" + uninstall + "), or pass --force to install both "
                + "deliberately (give them different ports in node.env).");
    }

    /** {@code node.meta}: where an install put its pieces, beside {@code node.env}. */
    static Path metaFile(Layout layout) {
        return layout.envFile().getParent().resolve("node.meta");
    }

    /** Records the resolved layout, so {@code status}/{@code start}/{@code uninstall} find a {@code --dir} install. */
    static void writeMeta(Layout layout, String version) throws IOException {
        StringBuilder meta = new StringBuilder();
        meta.append("installed_at=").append(java.time.Instant.now().toString()).append('\n');
        meta.append("system=").append(layout.system()).append('\n');
        meta.append("program_dir=").append(layout.programDir()).append('\n');
        meta.append("data_dir=").append(layout.dataDir()).append('\n');
        meta.append("log_dir=").append(layout.logDir() == null ? "" : layout.logDir()).append('\n');
        meta.append("env_file=").append(layout.envFile()).append('\n');
        meta.append("unit_file=").append(layout.unitFile() == null ? "" : layout.unitFile()).append('\n');
        meta.append("version=").append(version == null || version.isBlank() ? "latest" : version).append('\n');
        Path file = metaFile(layout);
        Files.writeString(file, meta.toString());
        restrict(file);
    }

    static boolean isInstalled(Io io) {
        return Files.isRegularFile(installedLayout(io).envFile());
    }

    // ----------------------------------------------------------------------
    // install
    // ----------------------------------------------------------------------

    /**
     * Fetches, checks and lays out a node. {@code version} pins a release tag; {@code dir} overrides the program
     * directory; {@code force} replaces an existing install.
     */
    static ObjectNode install(Io io, boolean system, Path dir, String version, boolean force, boolean quiet)
            throws IOException, InterruptedException {
        if (system && isWindows()) {
            throw new CliException("A system install is Linux only; Windows nodes run in Docker or WSL2", 2);
        }
        if (system && isMac()) {
            throw new CliException("macOS has a per-user LaunchAgent only; there is no system install", 2);
        }
        if (system && !NodeService.isRoot()) {
            throw new CliException("A --system install must run as root (try: sudo c93 node install --system)", 2);
        }
        Layout layout = system ? systemLayout() : userLayout(io, dir);
        if (Files.exists(layout.envFile()) && !force) {
            throw new CliException("A node is already installed (" + layout.envFile() + "); use --force to replace it");
        }
        // A user node and a system node default to the same ports, so a second install of the other kind would
        // leave them fighting over 9393/9394 (D-140).
        refuseIfAlsoInstalled(layout, (system ? userLayout(io, null) : systemLayout()).envFile(), force);

        String base = UpgradeCommand.installBase(io, version);
        if (!base.startsWith("https://") && io.env().get("C93_INSTALL_URL") == null) {
            throw new CliException("Releases are downloaded over HTTPS only: " + base);
        }

        if (!quiet) {
            io.out().println("Installing a Cluster93 node into " + layout.programDir());
        }
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        byte[] sums = fetch(http, base + "/" + SUMS);
        verifySignature(io, http, base, sums);

        Files.createDirectories(layout.programDir());
        Files.createDirectories(layout.dataDir());
        if (layout.logDir() != null) {
            Files.createDirectories(layout.logDir());
        }

        downloadVerified(http, base, SERVER_JAR, sums, layout.programDir().resolve(SERVER_JAR), false);
        Path bin = layout.programDir().resolve("bin");
        Files.createDirectories(bin);
        downloadGunzipped(http, base, WORKER_JAVET, sums, bin.resolve("worker-javet"));
        downloadGunzipped(http, base, WORKER_GRAALJS, sums, bin.resolve("worker-graaljs"));
        // The portable CLI jar travels alongside the server, but a release without it still installs.
        Path cli = layout.programDir().resolve(CLI_JAR);
        try {
            downloadVerified(http, base, CLI_JAR, sums, cli, false);
        } catch (CliException missing) {
            if (!missing.getMessage().contains("HTTP 404")) {
                throw missing;
            }
        }

        Map<String, String> config = defaultConfig(io, layout);
        writeEnv(layout, config);
        if (layout.unitFile() != null) {
            Files.createDirectories(layout.unitFile().getParent());
            Files.writeString(layout.unitFile(), renderUnit(io, layout, config));
        }
        writeMeta(layout, version);
        if (system) {
            provisionSystemUser(io, layout);
        }
        if (!quiet) {
            io.out().println("Installed the node into " + layout.programDir());
            if (layout.unitFile() != null) {
                io.out().println("Wrote " + layout.envFile() + " and " + layout.unitFile());
            } else {
                io.out().println("Wrote " + layout.envFile());
            }
        }

        ObjectNode result = Json.object()
                .put("programDir", layout.programDir().toString())
                .put("envFile", layout.envFile().toString())
                .put("dataDir", layout.dataDir().toString())
                .put("system", layout.system())
                .put("unitFile", layout.unitFile() == null ? "" : layout.unitFile().toString());
        return result;
    }

    /** Removes a node's program files and unit; {@code purge} also removes its data. */
    static void uninstall(Io io, boolean system, boolean purge, Path dir) {
        Layout layout = system ? systemLayout() : userLayout(io, dir);
        if (NodeService.isInstalled(io, layout) && NodeService.running(io, NodeService.target(io, layout))) {
            // Stop only a node that is actually up: stopping an unloaded unit is noise, not a failure.
            NodeService.stop(io, NodeService.target(io, layout), false);
        }
        Path programDir = !system && dir != null ? dir.toAbsolutePath() : layout.programDir();
        delete(programDir.resolve(SERVER_JAR));
        delete(programDir.resolve(CLI_JAR));
        deleteTree(programDir.resolve("bin"));
        try {
            Files.deleteIfExists(programDir);
        } catch (IOException ignored) {
            // not empty (data lives here on a default install)
        }
        delete(layout.unitFile());
        delete(layout.envFile());
        delete(metaFile(layout));
        if (purge) {
            deleteTree(layout.dataDir());
        }
    }

    // ----------------------------------------------------------------------
    // node.env
    // ----------------------------------------------------------------------

    /** The environment the server reads: {@code C93_*} names, so a systemd {@code EnvironmentFile} feeds it directly. */
    static Map<String, String> defaultConfig(Io io, Layout layout) {
        boolean workers = isLinux() && Files.isExecutable(layout.programDir().resolve("bin").resolve("worker-javet"));
        Map<String, String> config = new LinkedHashMap<>();
        config.put("C93_NODE_ID", defaultNodeId());
        config.put("C93_ROLES", "api,ingress,worker,data,console");
        config.put("C93_DATA_DIR", layout.dataDir().toString());
        config.put("C93_LOG_DIR", layout.logDir() == null ? "" : layout.logDir().toString());
        config.put("C93_HTTP_PORT", "9393");
        config.put("C93_INGRESS_PORT", "9394");
        config.put("C93_METRICS_PORT", "9395");
        config.put("C93_WORKER_MODE", workers ? "process" : "embedded");
        config.put("C93_WORKER_DEFAULT_MODE", "graaljs");
        config.put("C93_ADMIN_TOKEN", generateToken());
        config.put("C93_TENANT", "local");
        return config;
    }

    /** A safe node id from the host name, so a fresh install is unique without prompting. */
    static String defaultNodeId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String safe = host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-").replaceAll("^-+|-+$", "");
            if (!safe.isBlank()) {
                return safe.length() > 48 ? safe.substring(0, 48) : safe;
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return "node-1";
    }

    /** 32 random bytes as hex: the admin token a fresh node starts with. */
    static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Writes {@code node.env}, readable by its owner only. */
    static void writeEnv(Layout layout, Map<String, String> config) throws IOException {
        Files.createDirectories(layout.envFile().getParent());
        StringBuilder text = new StringBuilder();
        text.append("# Cluster93 node configuration (c93 node install).\n");
        text.append("# Read by the service unit and `c93 node ...`. Keep it private: it holds the admin token.\n");
        for (Map.Entry<String, String> entry : config.entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        Files.writeString(layout.envFile(), text.toString());
        restrict(layout.envFile());
    }

    /** The service unit (systemd or launchd) for a layout, with the resolved paths rendered in. */
    static String renderUnit(Io io, Layout layout, Map<String, String> config) {
        return switch (layout.unitKind()) {
            case SYSTEMD_SYSTEM -> systemdUnit(io, layout, config, true);
            case SYSTEMD_USER -> systemdUnit(io, layout, config, false);
            case LAUNCHD -> launchAgent(io, layout, config);
            case NONE -> "";
        };
    }

    static String systemdUnit(Io io, Layout layout, Map<String, String> config, boolean system) {
        String java = system ? "/usr/bin/java" : NodeCommand.javaBinary(io);
        StringBuilder unit = new StringBuilder();
        unit.append("[Unit]\n");
        unit.append("Description=Cluster93 node\n");
        unit.append("After=network-online.target\n");
        unit.append("Wants=network-online.target\n\n");
        unit.append("[Service]\n");
        unit.append("Type=simple\n");
        if (system) {
            unit.append("User=").append(layout.owner()).append('\n');
        }
        unit.append("WorkingDirectory=").append(layout.programDir()).append('\n');
        unit.append("EnvironmentFile=").append(layout.envFile()).append('\n');
        unit.append("Environment=\"JDK_JAVA_OPTIONS=").append(String.join(" ", NodeCommand.IGNITE_OPENS)).append("\"\n");
        unit.append("ExecStart=").append(java).append(" -jar ").append(layout.programDir().resolve(SERVER_JAR)).append('\n');
        unit.append("Restart=on-failure\n");
        unit.append("Delegate=yes\n");
        unit.append("NoNewPrivileges=yes\n");
        unit.append("ProtectSystem=strict\n");
        unit.append("ProtectHome=yes\n");
        unit.append("ReadWritePaths=").append(layout.dataDir()).append("\n\n");
        unit.append("[Install]\n");
        unit.append("WantedBy=").append(system ? "multi-user.target" : "default.target").append('\n');
        return unit.toString();
    }

    static String launchAgent(Io io, Layout layout, Map<String, String> config) {
        Path log = layout.logDir() != null ? layout.logDir().resolve("server.log") : layout.dataDir().resolve("server.log");
        StringBuilder plist = new StringBuilder();
        plist.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        plist.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        plist.append("<plist version=\"1.0\">\n<dict>\n");
        plist.append("  <key>Label</key>\n  <string>").append(LAUNCHD_LABEL).append("</string>\n");
        plist.append("  <key>ProgramArguments</key>\n  <array>\n");
        plist.append("    <string>").append(escapeXml(NodeCommand.javaBinary(io))).append("</string>\n");
        plist.append("    <string>-jar</string>\n");
        plist.append("    <string>").append(escapeXml(layout.programDir().resolve(SERVER_JAR).toString())).append("</string>\n");
        plist.append("  </array>\n");
        plist.append("  <key>WorkingDirectory</key>\n  <string>").append(escapeXml(layout.programDir().toString())).append("</string>\n");
        plist.append("  <key>EnvironmentVariables</key>\n  <dict>\n");
        for (Map.Entry<String, String> entry : config.entrySet()) {
            plist.append("    <key>").append(escapeXml(entry.getKey())).append("</key>\n");
            plist.append("    <string>").append(escapeXml(entry.getValue())).append("</string>\n");
        }
        plist.append("  </dict>\n");
        plist.append("  <key>KeepAlive</key>\n  <true/>\n");
        plist.append("  <key>RunAtLoad</key>\n  <true/>\n");
        plist.append("  <key>StandardOutPath</key>\n  <string>").append(escapeXml(log.toString())).append("</string>\n");
        plist.append("  <key>StandardErrorPath</key>\n  <string>").append(escapeXml(log.toString())).append("</string>\n");
        plist.append("</dict>\n</plist>\n");
        return plist.toString();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ----------------------------------------------------------------------
    // the system install's dedicated user (D-136)
    // ----------------------------------------------------------------------

    /**
     * Makes the rendered system unit consistent: creates the {@code cluster93} system user (idempotently), gives it
     * the data directory, and lets it read its own {@code node.env}. Only a {@code --system} install calls this, and
     * that already requires root, so {@code useradd} and {@code chown} are permitted.
     */
    static void provisionSystemUser(Io io, Layout layout) throws IOException, InterruptedException {
        if (!passwdHasUser(readPasswd(), SYSTEM_USER)) {
            String useradd = findCommand(io, "useradd");
            if (useradd == null) {
                throw new CliException("A --system install runs as a dedicated " + SYSTEM_USER + " user, and useradd "
                        + "isn't available to create it. Create it and retry: useradd --system --user-group "
                        + "--home-dir " + layout.dataDir() + " --no-create-home --shell " + nologinShell() + " " + SYSTEM_USER);
            }
            List<String> command = useraddCommand(layout.dataDir(), nologinShell());
            command.set(0, useradd);
            run(io, command, "create the " + SYSTEM_USER + " system user");
        }
        ownDataDir(layout);
        // The unit's EnvironmentFile is read by systemd (root), but group-read lets the service read its own config.
        grantGroupRead(layout.envFile());
        grantGroupRead(metaFile(layout));
    }

    /** The {@code useradd} invocation for the node's service account: a system user with its own group, no login. */
    static List<String> useraddCommand(Path dataDir, String shell) {
        return new ArrayList<>(List.of("useradd", "--system", "--user-group", "--home-dir", dataDir.toString(),
                "--no-create-home", "--shell", shell, SYSTEM_USER));
    }

    /** Whether a {@code /etc/passwd}-style file already names the user (so {@code useradd} is skipped). */
    static boolean passwdHasUser(String passwd, String user) {
        for (String line : passwd.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equals(user)) {
                return true;
            }
        }
        return false;
    }

    /** A shell that can't log in, for a service account; the first one that exists on this system. */
    static String nologinShell() {
        for (String candidate : List.of("/usr/sbin/nologin", "/sbin/nologin", "/bin/false")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "/usr/sbin/nologin";
    }

    private static String readPasswd() {
        try {
            return Files.readString(Path.of("/etc/passwd"));
        } catch (IOException e) {
            return "";
        }
    }

    /** Chowns the data directory (and everything under it) to the service account. */
    static void ownDataDir(Layout layout) {
        if (layout.dataDir() == null || !Files.exists(layout.dataDir())) {
            return;
        }
        try {
            UserPrincipal user = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(SYSTEM_USER);
            GroupPrincipal group = groupPrincipal();
            try (var walk = Files.walk(layout.dataDir())) {
                for (Path path : walk.toList()) {
                    Files.setOwner(path, user);
                    if (group != null) {
                        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
                        if (view != null) {
                            view.setGroup(group);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new CliException("Couldn't give " + SYSTEM_USER + " the data directory " + layout.dataDir()
                    + ": " + e.getMessage());
        }
    }

    /** Makes a config file readable by the service account ({@code 0640 root:cluster93}), when that group exists. */
    static void grantGroupRead(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        GroupPrincipal group = groupPrincipal();
        if (group == null) {
            return;
        }
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view != null) {
                view.setGroup(group);
                view.setPermissions(PosixFilePermissions.fromString("rw-r-----"));
            }
        } catch (IOException ignored) {
            // 0600 root:root is still fine: systemd reads EnvironmentFile as root.
        }
    }

    private static GroupPrincipal groupPrincipal() {
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByGroupName(SYSTEM_USER);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** A command by name: the caller's {@code PATH} first, then the usual sbin directories (often absent from sudo's). */
    static String findCommand(Io io, String name) {
        String onPath = NodeService.executable(io, name);
        if (onPath != null) {
            return onPath;
        }
        for (String dir : List.of("/usr/sbin", "/sbin", "/usr/bin", "/bin")) {
            Path candidate = Path.of(dir).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static void run(Io io, List<String> command, String what) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new CliException("Couldn't " + what + ": " + String.join(" ", command) + " exited " + process.exitValue());
        }
    }

    // ----------------------------------------------------------------------
    // fetch + verify
    // ----------------------------------------------------------------------

    /** Checks SHA256SUMS against its signature, unless the caller asked to skip it. */
    static void verifySignature(Io io, HttpClient http, String base, byte[] sums) throws IOException, InterruptedException {
        if ("1".equals(io.env().get(SKIP_SIGNATURE))) {
            io.err().println("Warning: not checking the release's signature (" + SKIP_SIGNATURE + "=1)");
            return;
        }
        byte[] signature;
        try {
            signature = fetch(http, base + "/" + SIGNATURE);
        } catch (CliException e) {
            throw new CliException(e.getMessage() + ". Releases are signed; an unsigned one isn't installed.");
        }
        if (!ReleaseSignature.verify(sums, signature, ReleaseSignature.key(io))) {
            throw new CliException("SHA256SUMS isn't signed by the c93 release key. Nothing was installed.");
        }
    }

    /** Downloads an asset to {@code target}, checking it against the signed SHA256SUMS. */
    static void downloadVerified(HttpClient http, String base, String asset, byte[] sums, Path target, boolean executable)
            throws IOException, InterruptedException {
        String expected = UpgradeCommand.checksum(new String(sums, StandardCharsets.UTF_8), asset);
        Path temp = Files.createTempFile(target.getParent(), ".cluster93-", ".download");
        try {
            String actual = download(http, base + "/" + asset, temp);
            if (!actual.equals(expected)) {
                throw new CliException("Checksum mismatch for " + asset + ": expected " + expected + ", got " + actual
                        + ". Nothing was installed.");
            }
            if (executable) {
                temp.toFile().setExecutable(true, false);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Downloads a {@code .gz} asset, checks the compressed bytes, and gunzips it into place. */
    static void downloadGunzipped(HttpClient http, String base, String asset, byte[] sums, Path target)
            throws IOException, InterruptedException {
        String expected = UpgradeCommand.checksum(new String(sums, StandardCharsets.UTF_8), asset);
        Path temp = Files.createTempFile(target.getParent(), ".cluster93-", ".gz");
        try {
            String actual = download(http, base + "/" + asset, temp);
            if (!actual.equals(expected)) {
                throw new CliException("Checksum mismatch for " + asset + ": expected " + expected + ", got " + actual
                        + ". Nothing was installed.");
            }
            try (InputStream in = new GZIPInputStream(Files.newInputStream(temp));
                 OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
            target.toFile().setExecutable(true, false);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static byte[] fetch(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new CliException("Download failed: " + url + " (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    /** Streams a URL to a file, returning its SHA-256. */
    static String download(HttpClient http, String url, Path into) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
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

    // ----------------------------------------------------------------------
    // small helpers
    // ----------------------------------------------------------------------

    /** Recursively deletes a path, best effort. */
    static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            for (int i = paths.size() - 1; i >= 0; i--) {
                delete(paths.get(i));
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    static void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // not POSIX (Windows): nothing to do
        }
    }

    private static String value(String candidate) {
        return candidate == null || candidate.isBlank() ? null : candidate.trim();
    }
}
