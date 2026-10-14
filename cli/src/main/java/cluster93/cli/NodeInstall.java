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
import java.util.Optional;
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
    /** The un-gzipped worker names, as they sit in {@code bin/} and in a node image. */
    static final String WORKER_JAVET_BIN = "worker-javet";
    static final String WORKER_GRAALJS_BIN = "worker-graaljs";
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
        enum Kind { SYSTEMD_SYSTEM, SYSTEMD_USER, LAUNCHD, NONE, CONTAINER }

        /** Whether this node runs as a container rather than as a service unit or a bare process. */
        boolean container() {
            return unitKind == Kind.CONTAINER;
        }

        /**
         * A container node's compose file, beside {@code node.env}; null for every other kind. Derived rather than
         * stored, so a new layout factory cannot forget to fill it in.
         */
        Path composeFile() {
            return container() ? envFile.getParent().resolve("compose.yaml") : null;
        }

        /** A container node's certificates, beside {@code node.env}; null for every other kind. */
        Path tlsDir() {
            return container() ? envFile.getParent().resolve("tls") : null;
        }
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
     * A container node's layout (D-141). There is no program directory on the host — the program lives in the image —
     * so {@code programDir} is the configuration root; the compose file and TLS directory derive from it.
     */
    static Layout containerLayout(Io io, boolean system, Path dir) {
        Path configDir = system ? Path.of("/etc/cluster93") : io.configDir();
        Path dataDir = dir != null ? dir.toAbsolutePath()
                : system ? Path.of("/var/lib/cluster93") : dataHome(io).resolve("cluster93").resolve("data");
        return new Layout(system, configDir, configDir.resolve("node.env"), dataDir,
                dataDir.resolve("logs"), null, Layout.Kind.CONTAINER,
                system ? SYSTEM_USER : System.getProperty("user.name", ""));
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
            Map<String, String> meta = NodeCommand.loadKeyValues(metaFile(systemLayout()));
            return "container".equals(meta.get("kind")) ? containerLayout(io, true, dataDirOf(meta)) : systemLayout();
        }
        Layout user = userLayout(io, null);
        // `--dir` isn't derivable from the config path, so `install` records where it put the node; read it back.
        Map<String, String> meta = NodeCommand.loadKeyValues(metaFile(user));
        // A container install is rebuilt from its kind and host data dir, so the verbs find the compose file (D-141).
        if ("container".equals(meta.get("kind"))) {
            return containerLayout(io, false, dataDirOf(meta));
        }
        String programDir = meta.get("program_dir");
        if (programDir != null && !programDir.isBlank()) {
            return userLayout(io, Path.of(programDir));
        }
        return user;
    }

    /** The host data dir a container install recorded, so its layout round-trips. */
    private static Path dataDirOf(Map<String, String> meta) {
        String dir = meta.get("host_data_dir");
        return dir == null || dir.isBlank() ? null : Path.of(dir);
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
        // A container install is rebuilt from node.meta, so its compose file and certificates are found (D-141).
        Layout installed = installedLayout(io);
        if (installed.container()) {
            layout = installed;
        }
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
        if (layout.container()) {
            delete(layout.composeFile());
            deleteTree(layout.tlsDir());
            Path caDir = layout.envFile().getParent().resolve("ca");
            // ca.key signs every node in this cluster: --purge removes it, but never silently (S5).
            if (purge) {
                deleteTree(caDir);
            } else if (Files.isRegularFile(caDir.resolve("ca.key"))) {
                io.out().println("Kept " + caDir.resolve("ca.key") + " — it signs this cluster's nodes. Remove it "
                        + "yourself once no node needs it, or re-run with --purge.");
            }
        }
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

    // ----------------------------------------------------------------------
    // container nodes (D-141, D-142)
    // ----------------------------------------------------------------------

    /**
     * The environment a container node reads. Clustered size 1 from the first start, so durable Ignite is on
     * immediately (D-142); TLS under {@code /tls}; data at {@code /var/lib/cluster93}, which compose bind-mounts.
     * Ports here are the <em>container's</em> ports — the host mapping lives in the compose file.
     */
    static Map<String, String> containerConfig(String nodeId) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("C93_NODE_ID", nodeId);
        config.put("C93_ROLES", "api,ingress,worker,data,console");
        config.put("C93_CLUSTERED", "true");
        config.put("C93_CLUSTER_SIZE", "1");
        config.put("C93_CLUSTER_MEMBERS", nodeId);
        config.put("C93_CLUSTER_BIND", nodeId);
        config.put("C93_TLS_DIR", "/tls");
        config.put("C93_DATA_DIR", "/var/lib/cluster93");
        config.put("C93_HTTP_PORT", "9393");
        config.put("C93_INGRESS_PORT", "9394");
        config.put("C93_METRICS_PORT", "9395");
        // The image ships bin/worker-*, so a container node runs its workers as processes by default (D-142).
        config.put("C93_WORKER_MODE", "process");
        config.put("C93_WORKER_DEFAULT_MODE", "graaljs");
        config.put("C93_ADMIN_TOKEN", generateToken());
        config.put("C93_TENANT", "local");
        return config;
    }

    /** The invoking user's numeric uid:gid, which a user container runs as so the bind-mounted data dir is writable. */
    static String currentUidGid() {
        try {
            String status = Files.readString(Path.of("/proc/self/status"));
            String uid = null;
            String gid = null;
            for (String line : status.split("\n")) {
                if (line.startsWith("Uid:")) {
                    uid = line.trim().split("\\s+")[1];
                } else if (line.startsWith("Gid:")) {
                    gid = line.trim().split("\\s+")[1];
                }
            }
            if (uid != null && gid != null) {
                return uid + ":" + gid;
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through: the image's own user is the safe default
        }
        return "10001:10001";
    }

    /**
     * The generated compose file: one node, its data bind-mounted so {@code --purge}, backups and paths match a
     * process install, and its certificates read-only (D-141, D-142). Never hand-edited.
     *
     * <p>Ports: 9393/9394/9395 and 5701/5801 are published, so the console, API and peers can reach them; 9396 is
     * only {@code expose}d, because it is the ingress-proxy endpoint, it is mTLS-protected, and publishing it adds
     * no capability (D-148, S8).
     */
    static String renderCompose(Layout layout, Map<String, String> config, String engine, String image,
                                String bindAddress, boolean publishCluster) {
        String nodeId = config.get("C93_NODE_ID");
        String project = "cluster93-" + nodeId;
        String user = layout.system() ? "10001:10001" : currentUidGid();
        StringBuilder compose = new StringBuilder();
        compose.append("# Generated by `c93 node install --docker`. Edit node.env, not this file.\n");
        compose.append("# Engine: ").append(engine).append("  Image: ").append(image).append("\n");
        compose.append("services:\n");
        compose.append("  node:\n");
        compose.append("    image: ").append(image).append("\n");
        compose.append("    container_name: ").append(project).append("\n");
        compose.append("    hostname: ").append(nodeId).append("\n");
        compose.append("    restart: unless-stopped\n");
        compose.append("    user: \"").append(user).append("\"\n");
        compose.append("    env_file: node.env\n");
        // Hardening (S4): no capabilities, no setuid escalation, a read-only root, and a bounded process table.
        compose.append("    cap_drop: [ALL]\n");
        compose.append("    security_opt: [no-new-privileges:true]\n");
        compose.append("    read_only: true\n");
        compose.append("    tmpfs: [/tmp]\n");
        compose.append("    pids_limit: 2048\n");
        compose.append("    ports:\n");
        compose.append("      - \"").append(bindAddress).append(":9393:9393\"\n");
        compose.append("      - \"").append(bindAddress).append(":9394:9394\"\n");
        compose.append("      - \"").append(bindAddress).append(":9395:9395\"\n");
        if (publishCluster) {
            compose.append("      - \"").append(bindAddress).append(":5701:5701\"\n");
            compose.append("      - \"").append(bindAddress).append(":5801:5801\"\n");
        }
        // 9396 stays cluster-internal: mTLS-protected, and peers reach it over the docker network.
        compose.append("    expose: [\"9396\"]\n");
        compose.append("    volumes:\n");
        compose.append("      - \"").append(layout.dataDir()).append(":/var/lib/cluster93\"\n");
        compose.append("      - \"").append(layout.tlsDir()).append(":/tls:ro\"\n");
        compose.append("    healthcheck:\n");
        compose.append("      test: [\"CMD\", \"bash\", \"-c\", \"exec 3<>/dev/tcp/localhost/9393 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3\"]\n");
        compose.append("      interval: 5s\n");
        compose.append("      retries: 12\n");
        return compose.toString();
    }

    /** Records a container install: the engine, its rootless-ness, the image, and both data-dir paths. */
    static void writeContainerMeta(Layout layout, String engine, String rootless, String image, String version,
                                   String nodeId) throws IOException {
        writeMeta(layout, version);
        StringBuilder extra = new StringBuilder();
        extra.append("kind=container\n");
        extra.append("engine=").append(engine).append('\n');
        extra.append("engine_rootless=").append(rootless).append('\n');
        extra.append("image=").append(image).append('\n');
        extra.append("compose_file=").append(layout.composeFile()).append('\n');
        extra.append("tls_dir=").append(layout.tlsDir()).append('\n');
        // The host data dir and the in-container one differ; --purge, backups and the bind mount all depend on it.
        extra.append("host_data_dir=").append(layout.dataDir()).append('\n');
        extra.append("in_container_data_dir=/var/lib/cluster93\n");
        extra.append("project=cluster93-").append(nodeId).append('\n');
        Path file = metaFile(layout);
        Files.writeString(file, Files.readString(file) + extra);
        restrict(file);
    }

    /**
     * How a container node's engine is running: its name, whether the daemon is rootless, and its CLI path.
     * {@code rootless} is null when the daemon didn't answer — an install may proceed without one (S1.9), and only
     * the things that need it (starting, and {@code --worker-containers}) insist (D-145).
     */
    record Engine(String name, Boolean rootless, String cli) {
    }

    /**
     * Detects the container engine, and asks its daemon whether it runs rootless. The CLI must be on the PATH; the
     * daemon may be absent, in which case rootless-ness is simply unknown.
     */
    static Engine detectEngine(Io io, String requested) {
        List<String> candidates = requested != null && !requested.isBlank()
                ? List.of(requested.trim())
                : List.of("docker", "podman");
        List<String> tried = new ArrayList<>();
        for (String name : candidates) {
            String cli = NodeService.executable(io, name);
            if (cli == null) {
                tried.add(name + ": not on the PATH");
                continue;
            }
            boolean podman = "podman".equals(name);
            // Ask the daemon rather than guessing from the socket path: a rootful daemon's socket can be chowned to a
            // user and is still root-equivalent (S6).
            Optional<String> info = podman
                    ? NodeService.capture(io, cli, "info", "--format", "{{.Host.Security.Rootless}}")
                    : NodeService.capture(io, cli, "info", "--format", "{{.SecurityOptions}}");
            if (info.isEmpty() || info.get().isBlank()) {
                return new Engine(name, null, cli);
            }
            boolean rootless = podman
                    ? info.get().strip().toLowerCase(Locale.ROOT).startsWith("true")
                    : info.get().contains("rootless");
            return new Engine(name, rootless, cli);
        }
        throw new CliException("No usable container engine (tried " + String.join("; ", tried)
                + "). Install one, or pass --engine");
    }

    /** Copies a cluster's certificates into a container install's TLS dir, which compose mounts read-only. */
    static void copyCertificates(Path from, Path to) throws IOException {
        for (String name : List.of("node.crt", "node.key", "ca.crt")) {
            Path source = from.resolve(name);
            if (!Files.isRegularFile(source)) {
                throw new CliException("No " + name + " in " + from + "; a node needs node.crt, node.key and ca.crt");
            }
            Files.copy(source, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Generates a size-1 cluster's certificates with {@code cluster93.tls.ClusterInit}. Only {@code node.crt},
     * {@code node.key} and {@code ca.crt} stay in the node's TLS dir — {@code ca.key} is the cluster's signing key
     * and must not sit on the node (S5).
     *
     * @return the directory holding {@code ca.key}, for the operator to keep
     */
    static Path generateCertificates(Io io, Layout layout, String nodeId, Path jar) throws IOException {
        Path caHome = layout.envFile().getParent().resolve("ca");
        Files.createDirectories(caHome);
        Files.createDirectories(layout.tlsDir());
        List<String> command = new ArrayList<>();
        command.add(NodeCommand.javaBinary(io));
        command.add("-cp");
        command.add(jar.toString());
        command.add("cluster93.tls.ClusterInit");
        command.add(caHome.toString());
        command.add(nodeId);
        if (NodeService.capture(io, command.toArray(String[]::new)).isEmpty()) {
            throw new CliException("Couldn't generate the node's certificates; pass --tls-dir to use an existing cluster's");
        }
        copyCertificates(caHome.resolve(nodeId), layout.tlsDir());
        return caHome;
    }

    /** The checkout's server jar, when this is a source install; null otherwise. */
    static Path checkoutJar() {
        Path jar = Path.of("server", "target", SERVER_JAR).toAbsolutePath();
        return Files.isRegularFile(jar) ? jar : null;
    }

    /**
     * Builds a node image from the checkout, on the repo's own {@code node} stage — which is the stage that ships
     * {@code bin/worker-*}, so the node can run {@code C93_WORKER_MODE=process}. The jar must already be built; this
     * never runs Maven.
     *
     * <p>The build runs in a temp context, as {@code scripts/demo.sh} does: {@code .dockerignore} excludes
     * {@code server/target/}, so the repo root as a context cannot see the jar the stage copies.
     */
    static String buildCheckoutImage(Io io, Engine engine, String version, boolean quiet) throws IOException {
        Path jar = checkoutJar();
        if (jar == null) {
            throw new CliException("No server/target/" + SERVER_JAR + " to build an image from. Build it first "
                    + "(./mvnw -q -pl server -am package -DskipTests), or pass --image");
        }
        for (String worker : List.of(WORKER_JAVET_BIN, WORKER_GRAALJS_BIN)) {
            if (!Files.isExecutable(Path.of("bin", worker).toAbsolutePath())) {
                throw new CliException("No bin/" + worker + " to build an image from. Run scripts/build-workers.sh "
                        + "first, or pass --image");
            }
        }
        // `cluster93-node:local` for a checkout, `cluster93-node:<version>-local` for a pinned one — never "local-local".
        String tag = version == null || version.isBlank() ? "cluster93-node:local" : "cluster93-node:" + version + "-local";
        Path context = Files.createTempDirectory("cluster93-image-");
        try {
            Files.copy(Path.of("Dockerfile"), context.resolve("Dockerfile"));
            Files.createDirectories(context.resolve("server").resolve("target"));
            Files.copy(jar, context.resolve("server").resolve("target").resolve(SERVER_JAR));
            Files.createDirectories(context.resolve("bin"));
            for (String worker : List.of(WORKER_JAVET_BIN, WORKER_GRAALJS_BIN)) {
                Files.copy(Path.of("bin", worker), context.resolve("bin").resolve(worker));
            }
            if (!quiet) {
                io.out().println("Building " + tag + " from the checkout (this can take a few minutes)");
            }
            List<String> command = new ArrayList<>(List.of(engine.cli(), "build", "--target", "node", "-t", tag,
                    "-f", context.resolve("Dockerfile").toString(), context.toString()));
            if (NodeService.runVisible(io, command, 3600) != 0) {
                throw new CliException("Building " + tag + " failed; pass --image to use an image you already have");
            }
            return tag;
        } finally {
            deleteTree(context);
        }
    }

    /**
     * Builds a node image from a release: the signed {@code c93.jar} and the native workers, assembled in a temp
     * context from the mirrored {@code Dockerfile.node}. Nothing is fetched without verifying it first.
     */
    static String buildReleaseImage(Io io, Engine engine, String version, boolean quiet)
            throws IOException, InterruptedException {
        String base = UpgradeCommand.installBase(io, version);
        if (!base.startsWith("https://") && io.env().get("C93_INSTALL_URL") == null) {
            throw new CliException("Releases are downloaded over HTTPS only: " + base);
        }
        String tag = "cluster93-node:" + version;
        if (!quiet) {
            io.out().println("Building " + tag + " from the " + version + " release (this can take a few minutes)");
        }
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        byte[] sums = fetch(http, base + "/" + SUMS);
        verifySignature(io, http, base, sums);

        Path context = Files.createTempDirectory("cluster93-image-");
        try {
            Files.writeString(context.resolve("Dockerfile"), dockerfileNode());
            downloadVerified(http, base, SERVER_JAR, sums, context.resolve(SERVER_JAR), false);
            downloadGunzipped(http, base, WORKER_JAVET, sums, context.resolve(WORKER_JAVET_BIN));
            downloadGunzipped(http, base, WORKER_GRAALJS, sums, context.resolve(WORKER_GRAALJS_BIN));
            List<String> command = new ArrayList<>(List.of(engine.cli(), "build", "-t", tag, context.toString()));
            if (NodeService.runVisible(io, command, 3600) != 0) {
                throw new CliException("Building " + tag + " failed");
            }
            return tag;
        } finally {
            deleteTree(context);
        }
    }

    /**
     * The image a release assembles: the server jar and the native workers on the same JRE the repo's {@code runtime}
     * stage uses, with the same heap ceiling so Ignite's off-heap region and the V8 isolates fit beside it. Mirrored
     * for reference at {@code install/docker/Dockerfile.node}.
     */
    static String dockerfileNode() {
        return """
                # A Cluster93 node image built from a release: c93.jar plus the native workers, so the node can run
                # C93_WORKER_MODE=process. Javet ships glibc natives, so the base must not be Alpine/musl.
                FROM eclipse-temurin:21-jre
                RUN useradd --system --uid 10001 c93 \\
                 && mkdir -p /var/lib/cluster93 /opt/cluster93/bin && chown -R c93 /var/lib/cluster93 /opt/cluster93
                WORKDIR /opt/cluster93
                EXPOSE 9393 9394 9396 9395 5701 5801
                VOLUME /var/lib/cluster93
                ENV C93_DATA_DIR=/var/lib/cluster93
                ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=40 \\
                  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \\
                  --add-opens=java.base/java.nio=ALL-UNNAMED \\
                  --add-opens=java.base/java.io=ALL-UNNAMED \\
                  --add-opens=java.base/java.lang=ALL-UNNAMED \\
                  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \\
                  --add-opens=java.base/java.util=ALL-UNNAMED \\
                  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \\
                  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \\
                  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \\
                  --add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED"
                COPY c93.jar /opt/cluster93/c93.jar
                COPY worker-javet worker-graaljs /opt/cluster93/bin/
                RUN chmod +x /opt/cluster93/bin/worker-javet /opt/cluster93/bin/worker-graaljs
                USER c93
                ENTRYPOINT ["java", "-jar", "/opt/cluster93/c93.jar"]
                """;
    }

    /**
     * Installs a node as a container (D-141): node.env, the compose file, certificates and node.meta. The image is
     * {@code --image}, or {@code --version} assembled from the release, or the checkout's {@code node} stage.
     */
    static ObjectNode installContainer(Io io, boolean system, Path dir, String requestedEngine, String image,
                                       String version, Path tlsDir, String bindAddress, boolean publishCluster,
                                       boolean force, boolean quiet) throws IOException, InterruptedException {
        if (system && !NodeService.isRoot()) {
            throw new CliException("A --system install must run as root (try: sudo c93 node install --docker --system)", 2);
        }
        Layout layout = containerLayout(io, system, dir);
        if (Files.exists(layout.envFile()) && !force) {
            throw new CliException("A node is already installed (" + layout.envFile() + "); use --force to replace it");
        }
        // A container node and a process node default to the same host ports, so one has to go (D-146). A *user*
        // process install shares this config dir, so the "already installed" check above already covers it; the one
        // that needs looking up is the system install. A system container install runs as root, whose config dir is
        // not the invoking user's, so there is nothing to look up from there.
        refuseIfAlsoInstalled(layout, layout.system() ? null : systemLayout().envFile(), force);

        Engine engine = detectEngine(io, requestedEngine);
        String nodeId = defaultNodeId();
        String version0 = version == null || version.isBlank() ? "local" : version;
        // No --image: build one. A pinned --version assembles from that release; otherwise from the checkout (§1.8).
        String imageRef;
        if (image != null && !image.isBlank()) {
            imageRef = image.trim();
        } else if (version != null && !version.isBlank()) {
            imageRef = buildReleaseImage(io, engine, version0, quiet);
        } else {
            imageRef = buildCheckoutImage(io, engine, version, quiet);
        }

        Files.createDirectories(layout.envFile().getParent());
        Files.createDirectories(layout.dataDir());
        if (layout.logDir() != null) {
            Files.createDirectories(layout.logDir());
        }
        Map<String, String> config = containerConfig(nodeId);
        writeEnv(layout, config);

        Path caHome = null;
        if (tlsDir != null) {
            Files.createDirectories(layout.tlsDir());
            copyCertificates(tlsDir, layout.tlsDir());
        } else {
            Path jar = checkoutJar();
            if (jar == null) {
                throw new CliException("Generating certificates needs the server jar; build it, or pass --tls-dir "
                        + "with an existing cluster's node.crt, node.key and ca.crt");
            }
            caHome = generateCertificates(io, layout, nodeId, jar);
        }

        Files.writeString(layout.composeFile(),
                renderCompose(layout, config, engine.name(), imageRef, bindAddress, publishCluster));
        // Validate the render with the engine's own parser before anything starts, so a bad path or a typo in the
        // generated YAML fails here with a clear message rather than at `up` (§1.9).
        if (NodeService.validateCompose(io, layout) != 0) {
            throw new CliException("The generated compose file didn't validate: " + layout.composeFile());
        }
        // "unknown" is honest: the daemon didn't answer, so rootless-ness is not established (D-145).
        String rootless = engine.rootless() == null ? "unknown" : engine.rootless().toString();
        writeContainerMeta(layout, engine.name(), rootless, imageRef, version0, nodeId);
        if (system) {
            provisionSystemUser(io, layout);
        }

        if (!quiet) {
            io.out().println("Installed a " + engine.name() + " node (" + rootless + ")");
            io.out().println("Wrote " + layout.envFile() + " and " + layout.composeFile());
            if (caHome != null) {
                io.out().println("Keep " + caHome.resolve("ca.key") + " safe: it signs this cluster's nodes, and it is "
                        + "not mounted into the container.");
            }
        }
        return Json.object()
                .put("container", true)
                .put("engine", engine.name())
                .put("engineRootless", engine.rootless())
                .put("image", imageRef)
                .put("envFile", layout.envFile().toString())
                .put("composeFile", layout.composeFile().toString())
                .put("dataDir", layout.dataDir().toString())
                .put("system", system);
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
            // A container node has no unit: the compose file is its definition and Docker supervises it (D-141).
            case CONTAINER -> "";
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
