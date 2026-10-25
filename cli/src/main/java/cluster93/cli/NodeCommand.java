package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code c93 node}: install a node from a release, drive it as a service, and manage cluster membership. This is the
 * unified control plane for a node — {@code install}/{@code uninstall}, the lifecycle verbs
 * ({@code start}/{@code stop}/{@code restart}/{@code status}/{@code logs}/{@code mcp}/{@code update}/{@code env}), and
 * the operator's {@code invite}/{@code revoke} (D-134).
 *
 * <p>An <em>installed</em> node ({@code node.env} + a service unit, driven through {@link NodeService}) is the
 * permanent shape. A checkout stack is {@code c93 dev}; the old {@code $C93_HOME/local.env} state is read only as a
 * one-release compatibility path.
 */
@Command(name = "node", description = "Install, run, and manage a Cluster93 node, and manage cluster membership.",
        subcommands = {NodeCommand.Install.class, NodeCommand.Uninstall.class,
                NodeCommand.Start.class, NodeCommand.Stop.class, NodeCommand.Restart.class, NodeCommand.Status.class,
                NodeCommand.Logs.class, NodeCommand.Mcp.class, NodeCommand.Update.class, NodeCommand.Env.class,
                NodeCommand.Join.class, NodeCommand.Invite.class, NodeCommand.Revoke.class, NodeCommand.Workers.class})
final class NodeCommand extends Base {

    static final String DEFAULT_NODE_ID = "local-master";
    static final String DEFAULT_ROLES = "api,ingress,worker,data,console";
    /** The master's own store, apart from the {@code dev-data} directory {@code scripts/dev.sh} uses. */
    static final String DEFAULT_DATA_DIR = "master-data";
    static final String DEFAULT_HTTP_PORT = "9393";
    static final String DEFAULT_INGRESS_PORT = "9394";
    static final String DEFAULT_METRICS_PORT = "9395";
    static final String DEFAULT_WORKER_MODE = "process";
    static final String DEFAULT_TENANT = "local";

    /** Apache Ignite 2.18 on JDK 21 needs these module opens, or the server won't start. */
    static final List<String> IGNITE_OPENS = List.of(
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED",
            "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
            "--add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED",
            "--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.base/java.math=ALL-UNNAMED",
            "--add-opens=java.sql/java.sql=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.time=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.management/sun.management=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED");

    /** The friendly {@code node.env} names the CLI accepts, mapped to the {@code C93_*} the server reads. */
    static final Map<String, String> ENV_ALIASES = Map.ofEntries(
            Map.entry("NODE_ID", "C93_NODE_ID"),
            Map.entry("ROLES", "C93_ROLES"),
            Map.entry("DATA_DIR", "C93_DATA_DIR"),
            Map.entry("LOG_DIR", "C93_LOG_DIR"),
            Map.entry("HTTP_PORT", "C93_HTTP_PORT"),
            Map.entry("INGRESS_PORT", "C93_INGRESS_PORT"),
            Map.entry("METRICS_PORT", "C93_METRICS_PORT"),
            Map.entry("WORKER_MODE", "C93_WORKER_MODE"),
            Map.entry("WORKER_ENGINE", "C93_WORKER_DEFAULT_MODE"),
            Map.entry("ADMIN_TOKEN", "C93_ADMIN_TOKEN"),
            Map.entry("TENANT", "C93_TENANT"));

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    // ----------------------------------------------------------------------
    // state locations (the development master's layout)
    // ----------------------------------------------------------------------

    /** {@code $C93_HOME}, else {@code $HOME/.cluster93}, else {@code user.home/.cluster93}. */
    static Path localHome(Io io) {
        String explicit = value(io.env().get("C93_HOME"));
        if (explicit != null) {
            return Path.of(explicit);
        }
        String home = value(io.env().get("HOME"));
        Path base = home != null ? Path.of(home) : Path.of(System.getProperty("user.home", "."));
        return base.resolve(".cluster93");
    }

    static Path envFile(Io io) {
        return localHome(io).resolve("local.env");
    }

    static Path metaFile(Io io) {
        return localHome(io).resolve("node.meta");
    }

    static Path pidFile(Io io) {
        return localHome(io).resolve("c93.pid");
    }

    static Path mcpFile(Io io) {
        return localHome(io).resolve("mcp.json");
    }

    static Path logFile(Io io) {
        String dir = loadConfig(io).get("LOG_DIR");
        Path base = dir != null && !dir.isBlank() ? Path.of(dir) : localHome(io).resolve("logs");
        return base.resolve("server.log");
    }

    /** The {@code local.env} values, or an empty map when the node isn't set up. */
    static Map<String, String> loadConfig(Io io) {
        return loadKeyValues(envFile(io));
    }

    static Map<String, String> loadMeta(Io io) {
        return loadKeyValues(metaFile(io));
    }

    static Map<String, String> loadKeyValues(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return parseKeyValues(Files.readString(file));
        } catch (IOException e) {
            return Map.of();
        }
    }

    static Map<String, String> parseKeyValues(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = trimmed.substring(0, equals).strip();
            String value = trimmed.substring(equals + 1).strip();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            map.put(key, value);
        }
        return map;
    }

    static Path repoRoot(Io io) {
        List<String> candidates = new ArrayList<>();
        addRepoCandidate(candidates, io.env().get("C93_REPO"));
        addRepoCandidate(candidates, System.getenv("C93_REPO"));
        addRepoCandidate(candidates, loadConfig(io).get("REPO_ROOT"));
        addRepoCandidate(candidates, loadMeta(io).get("repo_root"));
        for (String candidate : candidates) {
            Path path = Path.of(candidate).toAbsolutePath();
            if (Checkout.isCheckout(path)) {
                return path;
            }
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".git"))) {
                return dir;
            }
        }
        return null;
    }

    private static void addRepoCandidate(List<String> candidates, String candidate) {
        String value = value(candidate);
        if (value != null) {
            candidates.add(value);
        }
    }

    // ----------------------------------------------------------------------
    // the node's process
    // ----------------------------------------------------------------------

    static Optional<ProcessHandle> runningProcess(Io io) {
        Path file = pidFile(io);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            long pid = Long.parseLong(Files.readString(file).strip());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    static long readPid(Io io) {
        Path file = pidFile(io);
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try {
            return Long.parseLong(Files.readString(file).strip());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    static String httpBase(Map<String, String> config) {
        return "http://localhost:" + config.getOrDefault("HTTP_PORT", DEFAULT_HTTP_PORT);
    }

    /** A GET with a short timeout; null when the node is unreachable or answers non-2xx. */
    static HttpResponse<String> get(String url, String token, Duration timeout) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static boolean healthy(String base, Duration timeout) {
        HttpResponse<String> response = get(base + "/health", null, timeout);
        return response != null && response.statusCode() >= 200 && response.statusCode() < 300;
    }

    static boolean awaitHealth(String base, int seconds) {
        for (int attempt = 0; attempt < seconds; attempt++) {
            if (healthy(base, Duration.ofSeconds(2))) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static String gitHead(Io io) {
        Path root = repoRoot(io);
        if (root == null || !Files.isDirectory(root.resolve(".git"))) {
            return null;
        }
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 && !output.isBlank() ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ----------------------------------------------------------------------
    // install / uninstall
    // ----------------------------------------------------------------------

    @Command(name = "install", description = "Install this machine's node: build the checkout (the default inside one), "
            + "fetch a release, or run it as a container; write node.env and a unit/compose file, and start it.")
    static final class Install extends Base {

        @Option(names = "--system", description = "Install for the whole machine (Linux, root): /opt/cluster93, a cluster93 user, a system unit.")
        boolean system;

        @Option(names = "--dir", paramLabel = "DIR", description = "Install the program into DIR instead of the default.")
        Path dir;

        @Option(names = "--version", paramLabel = "TAG", description = "A release tag, e.g. v2.1.0 (default: the latest).")
        String version;

        @Option(names = "--no-start", description = "Lay out and configure the node, but don't start it.")
        boolean noStart;

        @Option(names = "--force", description = "Replace an existing install.")
        boolean force;

        @Option(names = "--docker", description = "Install the node as a container: a compose file and an image, instead of a service unit (D-141).")
        boolean docker;

        @Option(names = "--checkout", description = "Build this node from the current checkout (the default inside a checkout).")
        boolean checkout;

        @Option(names = "--release", description = "Install a release even when run from a checkout.")
        boolean release;

        @Option(names = "--join", paramLabel = "LINE", description = "A join line from `c93 cluster invite`; written to node.env.")
        String join;

        @Option(names = "--members", paramLabel = "HOST,HOST", description = "With --join: the cluster's discovery members, written to C93_CLUSTER_MEMBERS.")
        String members;

        @Option(names = "--roles", split = ",", paramLabel = "ROLE,ROLE", description = "The roles this node plays (default: api,ingress,worker,data,console).")
        List<String> roles;

        @Option(names = "--compute", description = "Shortcut for --roles worker: a private compute node with no API or ingress.")
        boolean compute;

        @Option(names = "--workers", paramLabel = "N", defaultValue = "0", description = "With --docker: run N local worker containers beside the node (D-191).")
        int workers;

        @Option(names = "--egress-placement", paramLabel = "worker|node", description = "With --docker --workers: whether worker containers decide egress (need a network) or the node does (network none).")
        String egressPlacement;

        @Option(names = "--http-port", paramLabel = "PORT", description = "With --docker: the node's API port (default 9393).")
        int httpPort;

        @Option(names = "--ingress-port", paramLabel = "PORT", description = "With --docker: the node's ingress port (default 9394).")
        int ingressPort;

        @Option(names = "--metrics-port", paramLabel = "PORT", description = "With --docker: the node's metrics port (default 9395).")
        int metricsPort;

        @Option(names = "--internal-port", paramLabel = "PORT", description = "With --docker: the node's internal port (default 9396).")
        int internalPort;

        @Option(names = "--cluster-port", paramLabel = "PORT", description = "With --docker: the Ignite discovery base port (default 5701).")
        int clusterPort;

        @Option(names = "--engine", paramLabel = "NAME", description = "The container engine to use: docker (the default) or podman.")
        String engine;

        @Option(names = "--image", paramLabel = "IMAGE", description = "The node image to run, instead of one built from the checkout.")
        String image;

        @Option(names = "--node-id", paramLabel = "ID", description = "The node's id (default: the host name).")
        String nodeId;

        @Option(names = "--tls-dir", paramLabel = "DIR", description = "Certificates from an existing cluster (node.crt, node.key, ca.crt), instead of generating a size-1 cluster's.")
        Path tlsDir;

        @Option(names = "--publish-address", paramLabel = "ADDR", description = "The host address published ports bind (default: 0.0.0.0).")
        String publishAddress = "0.0.0.0";

        @Option(names = "--no-publish-cluster-ports", description = "Keep the cluster ports (5701/5801) off the host network.")
        boolean noPublishClusterPorts;

        @Override
        int execute() throws Exception {
            Io io = io();
            if (checkout && release) {
                throw new CliException("--checkout and --release are mutually exclusive", 2);
            }
            if (checkout && version != null) {
                throw new CliException("--checkout builds the current tree; it cannot take --version", 2);
            }
            if (!docker && image != null) {
                throw new CliException("--image is for --docker; use --checkout or a release for a process node", 2);
            }
            if (!docker && workers > 0) {
                throw new CliException("--workers needs --docker: it creates local worker containers", 2);
            }
            if (!docker && egressPlacement != null) {
                throw new CliException("--egress-placement is for --docker --workers", 2);
            }
            if (workers < 0) {
                throw new CliException("--workers must be 0 or more", 2);
            }
            if (join != null && !cluster93.tls.NodeBundle.looksLikeOne(join.strip())) {
                throw new CliException("--join takes a c93_nodebundle_… or c93_nodecert_… line", 2);
            }
            String nodeRoles = compute ? "worker" : roles == null || roles.isEmpty() ? null : String.join(",", roles);
            if (docker) {
                return container(io, nodeRoles);
            }
            boolean checkoutMode = checkout || (!release && version == null && Checkout.root(io) != null);
            ObjectNode result = checkoutMode
                    ? NodeInstall.installCheckout(io, system, dir, nodeRoles, join, force, json())
                    : NodeInstall.install(io, system, dir, version, nodeRoles, join, force, json());
            NodeInstall.Layout layout = system ? NodeInstall.systemLayout() : NodeInstall.userLayout(io, dir);
            if (join != null || members != null) {
                Map<String, String> config = new LinkedHashMap<>(parseKeyValues(Files.readString(layout.envFile())));
                config.put("C93_CLUSTERED", "true");
                config.put("C93_CLUSTER_TLS", "required");
                if (join != null) {
                    config.put("C93_JOIN", join);
                }
                if (members != null) {
                    config.put("C93_CLUSTER_MEMBERS", members);
                }
                NodeInstall.writeEnv(layout, config);
            }
            if (!noStart) {
                int code = NodeService.start(io, NodeService.target(io, layout), !json());
                result.put("started", code == 0);
                if (code != 0) {
                    err().println("The node was installed but didn't start; see `c93 node logs`.");
                }
            } else {
                result.put("started", false);
            }
            if (json()) {
                Output.json(out(), result);
                return 0;
            }
            out().println(noStart
                    ? "Start it with: c93 node start"
                    : "Manage it with: c93 node status | c93 node logs --follow");
            return 0;
        }

        /** The container pathway: lay out the compose node, then start it unless {@code --no-start} (D-141). */
        private int container(Io io, String nodeRoles) throws Exception {
            Map<String, String> ports = new LinkedHashMap<>();
            if (httpPort > 0) ports.put("C93_HTTP_PORT", String.valueOf(httpPort));
            if (ingressPort > 0) ports.put("C93_INGRESS_PORT", String.valueOf(ingressPort));
            if (metricsPort > 0) ports.put("C93_METRICS_PORT", String.valueOf(metricsPort));
            if (internalPort > 0) ports.put("C93_INTERNAL_PORT", String.valueOf(internalPort));
            if (clusterPort > 0) ports.put("C93_CLUSTER_PORT", String.valueOf(clusterPort));
            ObjectNode result = NodeInstall.installContainer(io, system, dir, engine, image, version, tlsDir,
                    publishAddress, !noPublishClusterPorts, force, json(), workers, egressPlacement, join, nodeRoles,
                    ports, nodeId);
            NodeInstall.Layout layout = NodeInstall.containerLayout(io, system, dir);
            if (!noStart) {
                int code = NodeService.start(io, NodeService.target(io, layout), !json());
                result.put("started", code == 0);
                if (code != 0) {
                    err().println("The node was installed but didn't start; see `c93 node logs`.");
                }
            } else {
                result.put("started", false);
            }
            if (json()) {
                Output.json(out(), result);
                return 0;
            }
            out().println(noStart
                    ? "Start it with: c93 node start"
                    : "Manage it with: c93 node status | c93 node logs --follow");
            return 0;
        }
    }

    @Command(name = "uninstall", description = "Stop the node and remove its program and unit. --purge also deletes its data.")
    static final class Uninstall extends Base {

        @Option(names = "--system", description = "Uninstall a system install.")
        boolean system;

        @Option(names = "--purge", description = "Also delete the node's data directory (its database and logs).")
        boolean purge;

        @Option(names = "--dir", paramLabel = "DIR", description = "The program directory of a non-default install.")
        Path dir;

        @Override
        int execute() {
            Io io = io();
            NodeInstall.Layout layout = system ? NodeInstall.systemLayout() : NodeInstall.userLayout(io, dir);
            NodeInstall.uninstall(io, system, purge, dir);
            ObjectNode result = Json.object()
                    .put("programDir", layout.programDir().toString())
                    .put("dataDir", layout.dataDir().toString())
                    .put("purged", purge);
            if (printedJson(result)) {
                return 0;
            }
            out().println("Removed the node at " + layout.programDir()
                    + (purge ? " and its data at " + layout.dataDir() : " (its data at " + layout.dataDir() + " is kept)"));
            return 0;
        }
    }

    @Command(name = "env", description = "Show or change the node's node.env (the settings the service unit reads).")
    static final class Env extends Base {

        @Option(names = "--set", paramLabel = "K=V", description = "Set a value (repeatable). NODE_ID and C93_NODE_ID are both accepted.")
        List<String> set = new ArrayList<>();

        @Option(names = "--edit", description = "Open node.env in $EDITOR.")
        boolean edit;

        @Override
        int execute() throws Exception {
            Io io = io();
            NodeInstall.Layout layout = NodeInstall.installedLayout(io);
            if (!Files.isRegularFile(layout.envFile())) {
                throw new CliException("No installed node to configure (" + layout.envFile() + "). Run: c93 node install");
            }
            if (edit) {
                return openEditor(io, layout);
            }
            Map<String, String> config = new LinkedHashMap<>(parseKeyValues(Files.readString(layout.envFile())));
            for (String assignment : set) {
                int equals = assignment.indexOf('=');
                if (equals <= 0) {
                    throw new CliException("Expected K=V, got: " + assignment, 2);
                }
                String key = assignment.substring(0, equals).strip();
                String name = ENV_ALIASES.getOrDefault(key, key);
                config.put(name, assignment.substring(equals + 1).strip());
            }
            if (!set.isEmpty()) {
                NodeInstall.writeEnv(layout, config);
            }
            if (json()) {
                ObjectNode result = Json.object().put("envFile", layout.envFile().toString());
                for (Map.Entry<String, String> entry : config.entrySet()) {
                    result.put(entry.getKey(), entry.getValue());
                }
                Output.json(out(), result);
                return 0;
            }
            for (Map.Entry<String, String> entry : config.entrySet()) {
                out().println(entry.getKey() + "=" + entry.getValue());
            }
            if (!set.isEmpty()) {
                out().println();
                out().println("Updated " + layout.envFile() + "; restart to apply: c93 node restart");
            }
            return 0;
        }

        private int openEditor(Io io, NodeInstall.Layout layout) throws IOException, InterruptedException {
            String editor = io.env().getOrDefault("EDITOR", io.env().getOrDefault("VISUAL", "vi"));
            ProcessBuilder builder = new ProcessBuilder(editor, layout.envFile().toString());
            if (io.in() != System.in) {
                throw new CliException("--edit needs a terminal");
            }
            Process process = builder.inheritIO().start();
            return process.waitFor();
        }
    }

    // ----------------------------------------------------------------------
    // status
    // ----------------------------------------------------------------------

    @Command(name = "status", description = "Show the node's backend, PID, health, and nodes.")
    static final class Status extends Base {

        @Override
        int execute() {
            Io io = io();
            if (NodeInstall.isInstalled(io)) {
                return installed(io);
            }
            if (!Files.isRegularFile(envFile(io))) {
                out().println("No node is installed on this machine.");
                out().println("For a checkout stack, run: c93 dev up");
                out().println("For a permanent node, run: c93 node install");
                return 0;
            }
            return development(io);
        }

        /** An installed node: the service backend, and the node.env it reads. */
        private int installed(Io io) {
            NodeInstall.Layout layout = NodeInstall.installedLayout(io);
            NodeService.Target target = NodeService.target(io, layout);
            Map<String, String> config = target.config();
            String base = NodeService.httpBase(config);
            String token = config.get("C93_ADMIN_TOKEN");
            String nodeId = config.getOrDefault("C93_NODE_ID", DEFAULT_NODE_ID);
            boolean running = NodeService.running(io, target);
            long pid = NodeService.pidOf(io, target);
            boolean health = healthy(base, Duration.ofSeconds(3));
            String nodes = nodeIds(base, token);
            String backend = backendName(target.backend());
            Workers workers = workers(metricsBase(config, true));

            if (json()) {
                ObjectNode result = Json.object()
                        .put("nodeId", nodeId)
                        .put("backend", backend)
                        .put("system", layout.system())
                        .put("programDir", layout.programDir().toString())
                        .put("dataDir", layout.container() ? layout.dataDir().toString()
                                : config.getOrDefault("C93_DATA_DIR", layout.dataDir().toString()))
                        .put("apiUrl", base)
                        .put("workerMode", config.getOrDefault("C93_WORKER_MODE", DEFAULT_WORKER_MODE))
                        .put("running", running)
                        .put("health", health ? "ok" : "unreachable")
                        .put("nodes", nodes);
                putWorkers(result, workers);
                if (layout.container()) {
                    // A container node has no pid file: the engine owns its state (D-141).
                    result.put("container", NodeService.project(layout));
                    result.put("engine", NodeService.engine(layout));
                } else {
                    result.put("pid", pid > 0 ? pid : -1);
                }
                Output.json(out(), result);
                return 0;
            }
            out().println("Cluster93 node");
            out().println("  Node ID:        " + nodeId);
            out().println("  Backend:        " + backend);
            out().println(layout.container()
                    ? "  Compose:        " + layout.composeFile()
                    : "  Program:        " + layout.programDir());
            out().println("  Data directory: " + (layout.container() ? layout.dataDir().toString()
                    : config.getOrDefault("C93_DATA_DIR", layout.dataDir().toString())));
            out().println("  API:            " + base);
            out().println("  Worker mode:    " + config.getOrDefault("C93_WORKER_MODE", DEFAULT_WORKER_MODE));
            out().println(layout.container()
                    ? "  Container:      " + NodeService.project(layout) + (running ? " (running)" : " (not running)")
                    : "  PID:            " + (pid > 0 ? pid + (running ? " (running)" : " (not running)") : "(not running)"));
            out().println("  health:         " + (health ? "ok" : "unreachable"));
            out().println("  nodes:          " + nodes);
            out().println("  workers:        " + describeWorkers(workers));
            return 0;
        }

        /** The development master a checkout owns ({@code $C93_HOME/local.env}). */
        private int development(Io io) {
            Map<String, String> config = loadConfig(io);
            Map<String, String> meta = loadMeta(io);
            String base = httpBase(config);
            String token = config.get("ADMIN_TOKEN");
            String nodeId = config.getOrDefault("NODE_ID", DEFAULT_NODE_ID);
            long pid = readPid(io);
            boolean running = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            boolean health = healthy(base, Duration.ofSeconds(3));
            String nodes = nodeIds(base, token);
            Workers workers = workers(metricsBase(config, false));

            String installed = meta.get("git_commit");
            String head = gitHead(io);
            boolean update = installed != null && head != null && !installed.equals(head);

            String cliCommit = Main.commit();
            String cliBinary = meta.get("cli_binary");
            if (cliBinary == null || cliBinary.isBlank()) {
                Path root = repoRoot(io);
                cliBinary = root != null ? root.resolve("bin").resolve("c93").toString() : "";
            }
            boolean cliUpdate = VersionCommand.updateAvailable(cliCommit, head);

            // The local node owns system/console; ask it (as the admin token) whether the deployed console is behind.
            VersionCommand.ConsoleStatus console = VersionCommand.checkConsoleStatus(new Config(base, token), repoRoot(io), io);
            boolean consoleUpdate = console.updateAvailable();

            if (json()) {
                ObjectNode result = Json.object()
                        .put("nodeId", nodeId)
                        .put("backend", "development")
                        .put("roles", config.getOrDefault("ROLES", DEFAULT_ROLES))
                        .put("dataDir", config.getOrDefault("DATA_DIR", ""))
                        .put("logDir", config.getOrDefault("LOG_DIR", ""))
                        .put("apiUrl", base)
                        .put("ingressUrl", "http://localhost:" + config.getOrDefault("INGRESS_PORT", DEFAULT_INGRESS_PORT))
                        .put("workerMode", config.getOrDefault("WORKER_MODE", DEFAULT_WORKER_MODE))
                        .put("running", running)
                        .put("health", health ? "ok" : "unreachable");
                result.put("pid", pid > 0 ? pid : -1);
                result.put("nodes", nodes);
                putWorkers(result, workers);
                result.put("installedCommit", installed == null ? "" : installed);
                result.put("currentCommit", head == null ? "" : head);
                result.put("updateAvailable", update);
                result.put("cliCommit", cliCommit);
                result.put("cliBinary", cliBinary);
                result.put("cliUpdateAvailable", cliUpdate);
                result.put("consoleUpdateAvailable", consoleUpdate);
                Output.json(out(), result);
                return 0;
            }

            out().println("Local master node");
            out().println("  Node ID:        " + nodeId);
            out().println("  Roles:          " + config.getOrDefault("ROLES", DEFAULT_ROLES));
            out().println("  Data directory: " + config.getOrDefault("DATA_DIR", ""));
            out().println("  Log directory:  " + config.getOrDefault("LOG_DIR", ""));
            out().println("  API:            " + base);
            out().println("  Ingress:        http://localhost:" + config.getOrDefault("INGRESS_PORT", DEFAULT_INGRESS_PORT));
            out().println("  Worker mode:    " + config.getOrDefault("WORKER_MODE", DEFAULT_WORKER_MODE));
            out().println("  CLI commit:     " + cliCommit);
            out().println("  CLI binary:     " + cliBinary);
            out().println("  PID:            " + (pid > 0 ? pid + (running ? " (running)" : " (not running)") : "(not running)"));
            out().println("  health:         " + (health ? "ok" : "unreachable"));
            out().println("  nodes:          " + nodes);
            out().println("  workers:        " + describeWorkers(workers));
            if (update || cliUpdate || consoleUpdate) {
                out().println();
                if (update) {
                    out().println("\u26a0\ufe0f  Update Available! Running build: " + installed + ", Current repo HEAD: " + head
                            + ". Run 'c93 dev build' or 'c93 update --node'.");
                }
                if (cliUpdate) {
                    out().println("\u26a0\ufe0f  Update Available! Current CLI build: " + Main.shortCommit(cliCommit)
                            + ", Current repo HEAD: " + Main.shortCommit(head)
                            + ". Run 'scripts/build-cli.sh' or 'c93 update --node'.");
                }
                if (consoleUpdate) {
                    out().println("\u26a0\ufe0f  Update Available! Web Console build: " + Main.shortCommit(console.deployedCommit())
                            + ", Local repo HEAD: " + Main.shortCommit(console.localCommit())
                            + ". Run 'c93 update --node' or 'c93 apps deploy console'.");
                }
            }
            return 0;
        }

        private static String nodeIds(String base, String token) {
            HttpResponse<String> response = get(base + "/v1/nodes", token, Duration.ofSeconds(3));
            if (response == null || response.statusCode() >= 400) {
                return "(unreachable)";
            }
            try {
                JsonNode json = Json.parse(response.body());
                List<String> ids = new ArrayList<>();
                json.path("nodes").forEach(node -> ids.add(node.path("id").asText("?")));
                return ids.isEmpty() ? "(none)" : String.join(", ", ids);
            } catch (CliException e) {
                return "ok";
            }
        }

        // --- worker use ---------------------------------------------------------------------------------
        // A node's worker use (D-156) is a metric, not a REST field, so this reads the same /metrics gauges
        // Prometheus scrapes. Best-effort: an unreachable or disabled metrics port simply omits the line.

        /** The node gauges this reads; anything else in the exposition is ignored. */
        private static final Set<String> WORKER_GAUGES = Set.of(
                "c93_workers_running", "c93_workers_limit", "c93_workers_warm", "c93_tenants_active",
                "c93_workers_memory_used_bytes", "c93_workers_memory_limit_bytes");

        private record TenantWorkers(String tenant, int workers) {}

        private record Workers(int running, int limit, int warm, int tenantsActive,
                               long memoryUsedBytes, long memoryLimitBytes, List<TenantWorkers> tenants) {}

        private static String metricsBase(Map<String, String> config, boolean installed) {
            return "http://localhost:" + config.getOrDefault(installed ? "C93_METRICS_PORT" : "METRICS_PORT", DEFAULT_METRICS_PORT);
        }

        private static Workers workers(String metricsBase) {
            HttpResponse<String> response = get(metricsBase + "/metrics", null, Duration.ofSeconds(2));
            if (response == null || response.statusCode() >= 400) {
                return null;
            }
            Map<String, Double> gauges = new HashMap<>();
            List<TenantWorkers> tenants = new ArrayList<>();
            for (String line : response.body().split("\n")) {
                int space = line.lastIndexOf(' ');
                if (line.isBlank() || line.startsWith("#") || space < 0) {
                    continue;
                }
                String series = line.substring(0, space).trim();
                String name = series.contains("{") ? series.substring(0, series.indexOf('{')) : series;
                String value = line.substring(space + 1).trim();
                if (name.equals("c93_tenant_workers")) {
                    String tenant = tagValue(series, "tenant");
                    if (tenant != null) {
                        tenants.add(new TenantWorkers(tenant, (int) Math.round(number(value))));
                    }
                } else if (WORKER_GAUGES.contains(name)) {
                    gauges.put(name, number(value));
                }
            }
            return new Workers((int) Math.round(gauges.getOrDefault("c93_workers_running", -1.0)),
                    (int) Math.round(gauges.getOrDefault("c93_workers_limit", -1.0)),
                    (int) Math.round(gauges.getOrDefault("c93_workers_warm", 0.0)),
                    (int) Math.round(gauges.getOrDefault("c93_tenants_active", 0.0)),
                    Math.round(gauges.getOrDefault("c93_workers_memory_used_bytes", 0.0)),
                    Math.round(gauges.getOrDefault("c93_workers_memory_limit_bytes", 0.0)),
                    List.copyOf(tenants));
        }

        private static String tagValue(String series, String key) {
            String needle = key + "=\"";
            int at = series.indexOf(needle);
            if (at < 0) {
                return null;
            }
            int start = at + needle.length();
            int end = series.indexOf('"', start);
            return end < 0 ? null : series.substring(start, end);
        }

        private static double number(String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String describeWorkers(Workers workers) {
            if (workers == null) {
                return "(metrics unavailable)";
            }
            String limit = workers.limit() > 0 ? String.valueOf(workers.limit()) : "unbounded";
            String memory = workers.memoryLimitBytes() > 0
                    ? megabytes(workers.memoryUsedBytes()) + " / " + megabytes(workers.memoryLimitBytes())
                    : megabytes(workers.memoryUsedBytes());
            String perTenant = workers.tenants().isEmpty() ? ""
                    : " [" + workers.tenants().stream().map(t -> t.tenant() + ": " + t.workers()).collect(Collectors.joining(", ")) + "]";
            return workers.running() + " running / " + limit + ", " + workers.tenantsActive() + " tenant(s) active, "
                    + workers.warm() + " warm, " + memory + " memory" + perTenant;
        }

        private static String megabytes(long bytes) {
            return (bytes / (1024 * 1024)) + " MB";
        }

        private static void putWorkers(ObjectNode result, Workers workers) {
            if (workers == null) {
                return;
            }
            result.set("workers", Json.object()
                    .put("running", workers.running())
                    .put("limit", workers.limit())
                    .put("warm", workers.warm())
                    .put("tenantsActive", workers.tenantsActive())
                    .put("memoryUsedBytes", workers.memoryUsedBytes())
                    .put("memoryLimitBytes", workers.memoryLimitBytes()));
            ArrayNode tenants = Json.array();
            for (TenantWorkers tenant : workers.tenants()) {
                tenants.add(Json.object().put("tenant", tenant.tenant()).put("workers", tenant.workers()));
            }
            result.set("tenants", tenants);
        }
    }

    static String backendName(NodeService.Backend backend) {
        return switch (backend) {
            case SYSTEMD_USER -> "systemd --user";
            case SYSTEMD_SYSTEM -> "systemd (system)";
            case LAUNCHD -> "launchd";
            case DIRECT -> "direct process";
            case CONTAINER -> "container";
        };
    }

    // ----------------------------------------------------------------------
    // start / stop / restart
    // ----------------------------------------------------------------------

    @Command(name = "start", description = "Start the node: its service unit, or the process directly.")
    static final class Start extends Base {

        @Override
        int execute() {
            Io io = io();
            if (NodeInstall.isInstalled(io)) {
                return NodeService.start(io, NodeService.target(io, NodeInstall.installedLayout(io)));
            }
            throw new CliException("No installed node. For a checkout stack, use: c93 dev up", 2);
        }
    }

    @Command(name = "stop", description = "Stop the node (SIGTERM, then SIGKILL after 30s).")
    static final class Stop extends Base {

        @Override
        int execute() {
            Io io = io();
            if (NodeInstall.isInstalled(io)) {
                return NodeService.stop(io, NodeService.target(io, NodeInstall.installedLayout(io)), true);
            }
            throw new CliException("No installed node. For a checkout stack, use: c93 dev down", 2);
        }
    }

    @Command(name = "restart", description = "Restart the node.")
    static final class Restart extends Base {

        @Override
        int execute() {
            Io io = io();
            if (NodeInstall.isInstalled(io)) {
                return NodeService.restart(io, NodeService.target(io, NodeInstall.installedLayout(io)));
            }
            throw new CliException("No installed node. For a checkout stack, use: c93 dev down && c93 dev up", 2);
        }
    }

    static int stopLocal(Io io) {
        Optional<ProcessHandle> process = runningProcess(io);
        if (process.isEmpty()) {
            out(io).println("Not running.");
            try {
                Files.deleteIfExists(pidFile(io));
            } catch (IOException ignored) {
                // best effort
            }
            return 0;
        }
        long pid = process.get().pid();
        out(io).println("Stopping pid " + pid);
        process.get().destroy();
        for (int attempt = 0; attempt < 30; attempt++) {
            if (!process.get().isAlive()) {
                removePid(io);
                out(io).println("Stopped");
                return 0;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        process.get().destroyForcibly();
        removePid(io);
        out(io).println("Still alive after 30s; sent SIGKILL");
        return 0;
    }

    static int launchDirect(Io io) {
        Map<String, String> config = loadConfig(io);
        if (config.isEmpty()) {
            throw new CliException("No configuration at " + envFile(io) + ". Run: c93 dev up (checkout) or c93 node install");
        }
        Path jar = serverJar(io);
        if (!Files.isRegularFile(jar)) {
            throw new CliException("No server jar at " + jar + ". Run: c93 dev up (checkout) or c93 node install");
        }
        if (runningProcess(io).isPresent()) {
            out(io).println("Already running.");
            return 0;
        }
        Path log = logFile(io);
        try {
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(javaBinary(io), "-jar", jar.toString());
            builder.command().addAll(1, IGNITE_OPENS);
            builder.environment().putAll(serverEnvironment(config, io));
            builder.redirectOutput(log.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            Files.writeString(pidFile(io), Long.toString(process.pid()));
            out(io).println("Started pid " + process.pid() + " (log " + log + ")");
        } catch (IOException e) {
            throw new CliException("Couldn't start the node: " + e.getMessage());
        }
        String base = httpBase(config);
        if (awaitHealth(base, 30)) {
            out(io).println("Up at " + base + " (health OK)");
            return 0;
        }
        throw new CliException("The node didn't answer " + base + "/health within 30s; see " + log);
    }

    static Map<String, String> serverEnvironment(Map<String, String> config, Io io) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("C93_NODE_ID", config.getOrDefault("NODE_ID", DEFAULT_NODE_ID));
        env.put("C93_ROLES", config.getOrDefault("ROLES", DEFAULT_ROLES));
        env.put("C93_CLUSTERED", "true");
        env.put("C93_CLUSTER_SIZE", "1");
        env.put("C93_CLUSTER_MEMBERS", "127.0.0.1");
        env.put("C93_CLUSTER_TLS", "off");
        env.put("C93_DATA_DIR", config.getOrDefault("DATA_DIR", localHome(io).resolve(DEFAULT_DATA_DIR).toString()));
        env.put("C93_EGRESS_ALLOW_PRIVATE", "true");
        env.put("C93_ADMIN_TOKEN", config.getOrDefault("ADMIN_TOKEN", ""));
        env.put("C93_HTTP_PORT", config.getOrDefault("HTTP_PORT", DEFAULT_HTTP_PORT));
        env.put("C93_INGRESS_PORT", config.getOrDefault("INGRESS_PORT", DEFAULT_INGRESS_PORT));
        env.put("C93_METRICS_PORT", config.getOrDefault("METRICS_PORT", DEFAULT_METRICS_PORT));
        env.put("C93_WORKER_MODE", config.getOrDefault("WORKER_MODE", DEFAULT_WORKER_MODE));
        return env;
    }

    static Path serverJar(Io io) {
        String fromMeta = loadMeta(io).get("server_jar");
        if (fromMeta != null && !fromMeta.isBlank()) {
            return Path.of(fromMeta);
        }
        Path root = repoRoot(io);
        return root != null ? root.resolve("server").resolve("target").resolve("c93.jar") : Path.of("c93.jar");
    }

    static String javaBinary(Io io) {
        String explicit = value(io.env().get("C93_JAVA"));
        if (explicit != null) {
            return explicit;
        }
        String javaHome = value(io.env().get("JAVA_HOME"));
        if (javaHome != null && Files.isExecutable(Path.of(javaHome, "bin", "java"))) {
            return Path.of(javaHome, "bin", "java").toString();
        }
        return "java";
    }

    // ----------------------------------------------------------------------
    // logs
    // ----------------------------------------------------------------------

    @Command(name = "logs", description = "Show the node's server log.")
    static final class Logs extends Base {

        @Option(names = {"-n", "--lines"}, defaultValue = "50", paramLabel = "N",
                description = "How many lines to show first (default: 50).")
        int lines;

        @Option(names = {"-f", "--follow"}, description = "Keep the log open and print new lines as they arrive.")
        boolean follow;

        @Override
        int execute() throws IOException {
            Io io = io();
            boolean installed = NodeInstall.isInstalled(io);
            // A container node's log is the engine's: `c93 node logs` works the same way everywhere (D-141).
            if (installed && NodeInstall.installedLayout(io).container()) {
                return NodeService.logs(io, NodeService.target(io, NodeInstall.installedLayout(io)), lines, follow);
            }
            Path file = installed ? logFile(io) : logFile(io);
            if (!Files.isRegularFile(file)) {
                if (installed) {
                    return serviceLog(io);
                }
                err().println("No log file at " + file + " (is the node started?)");
                return 1;
            }
            return tail(file, lines, follow);
        }

        /** No file log: under systemd, the node's output is in the journal. */
        private int serviceLog(Io io) {
            String journalctl = NodeService.executable(io, "journalctl");
            if (journalctl == null) {
                err().println("No log file at " + logFile(io) + " and journalctl isn't available.");
                return 1;
            }
            NodeService.Target target = NodeService.target(io, NodeInstall.installedLayout(io));
            List<String> command = new ArrayList<>();
            command.add(journalctl);
            if (target.backend() == NodeService.Backend.SYSTEMD_USER) {
                command.add("--user");
            }
            command.add("-u");
            command.add(NodeService.UNIT);
            command.add("-n");
            command.add(Integer.toString(lines));
            if (follow) {
                command.add("-f");
            }
            try {
                Process process = new ProcessBuilder(command).inheritIO().start();
                return process.waitFor();
            } catch (IOException e) {
                throw new CliException("Couldn't run journalctl: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }

        private int tail(Path file, int lines, boolean follow) throws IOException {
            byte[] content = Files.readAllBytes(file);
            printTail(content, lines);
            if (!follow) {
                return 0;
            }
            long[] offset = {content.length};
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                byte[] all = Files.readAllBytes(file);
                if (all.length < offset[0]) {
                    offset[0] = 0;
                }
                if (all.length > offset[0]) {
                    out().print(new String(all, (int) offset[0], all.length - (int) offset[0], StandardCharsets.UTF_8));
                    out().flush();
                    offset[0] = all.length;
                }
            }
            return 0;
        }

        private void printTail(byte[] content, int count) {
            String text = new String(content, StandardCharsets.UTF_8);
            String[] all = text.split("\n", -1);
            int start = Math.max(0, all.length - Math.max(0, count));
            for (int i = start; i < all.length; i++) {
                if (i == all.length - 1 && all[i].isEmpty()) {
                    break;
                }
                out().println(all[i]);
            }
        }
    }

    // ----------------------------------------------------------------------
    // mcp
    // ----------------------------------------------------------------------

    @Command(name = "mcp", description = "Print the MCP configuration for the node.")
    static final class Mcp extends Base {

        @Override
        int execute() throws IOException {
            Io io = io();
            Path file = mcpFile(io);
            if (Files.isRegularFile(file)) {
                JsonNode saved = Json.parse(Files.readString(file));
                out().println(json() ? Json.write(saved) : Json.pretty(saved));
                return 0;
            }
            Map<String, String> config = NodeInstall.isInstalled(io)
                    ? NodeService.target(io, NodeInstall.installedLayout(io)).config()
                    : loadConfig(io);
            boolean installed = NodeInstall.isInstalled(io);
            String base = installed ? NodeService.httpBase(config) : httpBase(config);
            String admin = installed ? config.get("C93_ADMIN_TOKEN") : config.get("ADMIN_TOKEN");
            Config login = Config.load(io, profile());
            String url = login != null && login.url() != null && !login.url().isBlank()
                    ? login.url()
                    : base;
            String token = login != null && login.token() != null ? login.token() : "";
            String stdioToken = token.isBlank() ? (admin == null ? "" : admin) : token;

            ObjectNode http = Json.object().put("type", "http").put("url", url + "/mcp");
            http.set("headers", Json.object().put("Authorization", "Bearer " + token));
            ObjectNode servers = Json.object();
            servers.set("c93", http);

            ObjectNode stdio = Json.object().put("command", "c93");
            stdio.set("args", Json.strings(List.of("mcp")));
            stdio.set("env", Json.object().put("C93_URL", url).put("C93_TOKEN", stdioToken));
            ObjectNode stdioServers = Json.object();
            stdioServers.set("c93", stdio);

            ObjectNode httpConfig = Json.object();
            httpConfig.set("mcpServers", servers);
            ObjectNode stdioConfig = Json.object();
            stdioConfig.set("mcpServers", stdioServers);

            if (json()) {
                out().println(Json.write(httpConfig));
                return 0;
            }
            out().println("MCP configuration (endpoint " + url + "/mcp):");
            out().println();
            out().println("  Claude Code:");
            out().println("    claude mcp add --transport http c93 " + url + "/mcp \\");
            out().println("      --header \"Authorization: Bearer " + token + "\"");
            out().println();
            out().println("  Claude Desktop / Cursor / Antigravity (add to mcpServers):");
            out().println(indent(Json.pretty(httpConfig)));
            out().println();
            out().println("  Clients that only launch local processes (stdio bridge):");
            out().println(indent(Json.pretty(stdioConfig)));
            return 0;
        }

        private static String indent(String text) {
            StringBuilder out = new StringBuilder();
            for (String line : text.split("\n")) {
                out.append("    ").append(line).append('\n');
            }
            return out.toString().stripTrailing();
        }
    }

    // ----------------------------------------------------------------------
    // update
    // ----------------------------------------------------------------------

    /**
     * Updates the node: an installed node is re-fetched from the signed release (and restarted if it was running); a
     * a checkout stack is built by {@code c93 dev build}; a legacy checkout update script is still honoured for the
     * one-release transition. Shared by {@code c93 update --node} and the hidden {@code c93 node update} alias (D-138).
     */
    static int updateNode(Io io, String version, boolean json) throws Exception {
        if (NodeInstall.isInstalled(io)) {
            NodeInstall.Layout layout = NodeInstall.installedLayout(io);
            NodeService.Target target = NodeService.target(io, layout);
            boolean wasRunning = NodeService.running(io, target);
            if (!json) {
                io.out().println(wasRunning ? "Updating a running node; it will restart." : "Updating the installed node.");
            }
            NodeInstall.install(io, layout.system(), layout.system() ? null : layout.programDir(), version, true, json);
            if (wasRunning) {
                return NodeService.restart(io, target);
            }
            return 0;
        }
        throw new CliException("No installed node. For a checkout stack, use: c93 dev build && c93 dev down && c93 dev up.", 2);
    }

    @Command(name = "update", hidden = true, description = "Deprecated alias of `c93 update --node`.")
    static final class Update extends Base {

        @Option(names = "--version", paramLabel = "TAG", description = "A release tag to install (default: the latest).")
        String version;

        @Override
        int execute() throws Exception {
            return updateNode(io(), version, json());
        }
    }

    // ----------------------------------------------------------------------
    // operator: invite / revoke
    // ----------------------------------------------------------------------

    @Command(name = "join", description = "Join this node to an existing cluster with a join line from `c93 cluster invite`.")
    static final class Join extends Base {

        @Parameters(paramLabel = "LINE", description = "The c93_nodebundle_… or c93_nodecert_… line.")
        String line;

        @Option(names = "--no-restart", description = "Write node.env but do not restart the node.")
        boolean noRestart;

        @Option(names = "--members", paramLabel = "HOST,HOST", description = "The cluster's discovery members, written to C93_CLUSTER_MEMBERS.")
        String members;

        @Override
        int execute() throws Exception {
            if (line == null || !cluster93.tls.NodeBundle.looksLikeOne(line.strip())) {
                throw new CliException("That is not a join line (expected c93_nodebundle_… or c93_nodecert_…)", 2);
            }
            Io io = io();
            NodeInstall.Layout layout = NodeInstall.installedLayout(io);
            if (!Files.isRegularFile(layout.envFile())) {
                throw new CliException("No installed node at " + layout.envFile()
                        + ". Run `c93 node install` first.", 2);
            }
            Map<String, String> config = new LinkedHashMap<>(parseKeyValues(Files.readString(layout.envFile())));
            config.put("C93_JOIN", line.strip());
            config.put("C93_CLUSTERED", "true");
            config.put("C93_CLUSTER_TLS", "required");
            if (members != null && !members.isBlank()) {
                config.put("C93_CLUSTER_MEMBERS", members);
            }
            NodeInstall.writeEnv(layout, config);
            if (!noRestart) {
                NodeService.restart(io, NodeService.target(io, layout));
            }
            if (json()) {
                Output.json(out(), Json.object().put("node", config.getOrDefault("C93_NODE_ID", ""))
                        .put("joined", true).put("restarted", !noRestart));
                return 0;
            }
            out().println("Saved the join line to " + layout.envFile() + ".");
            out().println(noRestart ? "Restart the node when ready: c93 node restart" : "Restarted the node.");
            return 0;
        }
    }

    @Command(name = "invite", hidden = true, description = "Deprecated: use `c93 cluster invite`.")
    static final class Invite extends Base {
        @Parameters(paramLabel = "ID", description = "The node's id (C93_NODE_ID).")
        String nodeId;

        @Option(names = "--host", paramLabel = "ADDRESS", description = "An address other nodes reach it at (repeatable).")
        List<String> hosts = new ArrayList<>();

        @Option(names = "--ca", defaultValue = "./cluster-tls/ca.key", paramLabel = "FILE", description = "The CA key (ca.crt beside it).")
        Path ca;

        @Option(names = "--key", paramLabel = "PUBLIC_KEY", description = "The public key from `cluster93-server --prepare` on the node: "
                + "prints a certificate-only line, and the node's private key never leaves it.")
        String publicKey;

        @Override
        int execute() {
            return ClusterInvite.run(this, nodeId, hosts, ca, publicKey, null);
        }
    }

    @Command(name = "revoke", hidden = true, description = "Deprecated: use `c93 cluster revoke`.")
    static final class Revoke extends Base {
        @Parameters(paramLabel = "ID")
        String nodeId;

        @Override
        int execute() {
            return ClusterRevoke.run(this, nodeId, null);
        }
    }

    @Command(name = "workers", description = "Worker supply on this node: local containers, or ask an idle connected worker to leave.",
            subcommands = {NodeCommand.Workers.Release.class, NodeCommand.Workers.Scale.class,
                    NodeCommand.Workers.Add.class, NodeCommand.Workers.Remove.class})
    static final class Workers extends Base {

        @Override
        int execute() {
            spec.commandLine().usage(out());
            return 0;
        }

        @Command(name = "scale", description = "Set how many local worker containers this Docker install runs (D-191).")
        static final class Scale extends Base {

            @Parameters(paramLabel = "N", description = "The new worker count.")
            int count;

            @Override
            int execute() throws Exception {
                return resize(this, count);
            }
        }

        @Command(name = "add", description = "Add local worker containers to this Docker install.")
        static final class Add extends Base {

            @Parameters(paramLabel = "N", arity = "0..1", defaultValue = "1", description = "How many to add (default 1).")
            int count;

            @Override
            int execute() throws Exception {
                return resize(this, current(this) + count);
            }
        }

        @Command(name = "rm", description = "Remove local worker containers from this Docker install.")
        static final class Remove extends Base {

            @Parameters(paramLabel = "N", arity = "0..1", defaultValue = "1", description = "How many to remove (default 1).")
            int count;

            @Override
            int execute() throws Exception {
                return resize(this, Math.max(0, current(this) - count));
            }
        }

        /** Re-renders the compose project with a new worker count, validates it, and applies it. */
        private static int resize(Base base, int target) throws IOException {
            Io io = base.io();
            NodeInstall.Layout layout = NodeInstall.installedLayout(io);
            if (!layout.container()) {
                throw new CliException("Local worker containers need a `c93 node install --docker` node.", 2);
            }
            if (!Files.isRegularFile(layout.envFile())) {
                throw new CliException("No installed node at " + layout.envFile(), 2);
            }
            if (target < 0) {
                throw new CliException("The worker count cannot be negative.", 2);
            }
            Map<String, String> meta = loadKeyValues(NodeInstall.metaFile(layout));
            String image = meta.getOrDefault("image", "");
            if (image.isBlank()) {
                throw new CliException("This container install has no image recorded; reinstall it.", 2);
            }
            Map<String, String> config = new LinkedHashMap<>(parseKeyValues(Files.readString(layout.envFile())));
            config.keySet().removeIf(key -> key.startsWith("# C93_WORKER"));
            if (target > 0) {
                config.put("C93_WORKER_SOCKET", "/run/cluster93/workers/node.sock");
                config.put("C93_WORKER_SECRET_FILE", "/run/secrets/c93-worker-secret");
                config.put("C93_WORKER_MODE", "process");
                config.put("C93_WORKER_CONNECTIONS_MAX", String.valueOf(target + 1));
                NodeInstall.ensureWorkerSecret(layout);
                NodeInstall.prepareWorkerScratch(layout, target);
            }
            String engine = meta.getOrDefault("engine", NodeService.engine(layout));
            String bind = meta.getOrDefault("bind_address", "0.0.0.0");
            boolean publish = Boolean.parseBoolean(meta.getOrDefault("publish_cluster", "true"));
            String egress = meta.get("egress_placement");
            Files.writeString(layout.composeFile(), NodeInstall.renderCompose(layout, config, engine, image, bind,
                    publish, target, egress));
            NodeInstall.writeEnv(layout, config);
            if (NodeService.validateCompose(io, layout) != 0) {
                throw new CliException("The generated compose file didn't validate: " + layout.composeFile());
            }
            NodeInstall.writeContainerMeta(layout, engine, meta.getOrDefault("engine_rootless", "unknown"), image,
                    meta.getOrDefault("version", "local"), config.getOrDefault("C93_NODE_ID", ""), target, egress,
                    bind, publish);
            int code = NodeService.applyCompose(io, layout);
            if (base.json()) {
                Output.json(base.out(), Json.object().put("workers", target).put("applied", code == 0)
                        .put("composeFile", layout.composeFile().toString()));
            } else if (code == 0) {
                base.out().println("Workers: " + target + " (" + layout.composeFile() + ")");
            } else {
                throw new CliException("The compose project failed to apply; see the engine output above.", 1);
            }
            return code == 0 ? 0 : 1;
        }

        private static int current(Base base) {
            NodeInstall.Layout layout = NodeInstall.installedLayout(base.io());
            return Integer.parseInt(loadKeyValues(NodeInstall.metaFile(layout)).getOrDefault("workers", "0"));
        }

        @Command(name = "release", description = "Ask one idle connected worker to leave. It exits 0, and its supervisor decides "
                + "whether to replace it (D-187).")
        static final class Release extends Base {

            @Option(names = "--worker", paramLabel = "ID", description = "Release this connected worker (by the id in `c93 node status`).")
            String workerId;

            @Option(names = "--bound-tenant", paramLabel = "TENANT", description = "Release an idle worker bound to this tenant.")
            String tenant;

            @Option(names = "--reason", paramLabel = "TEXT", defaultValue = "over capacity for this node",
                    description = "Why it is being released; it is the worker's death cause and the API's response.")
            String reason;

            @Override
            int execute() {
                ObjectNode body = Json.object();
                if (workerId != null) {
                    body.put("workerId", workerId);
                }
                if (tenant != null) {
                    body.put("tenant", tenant);
                }
                body.put("reason", reason);
                JsonNode result = api().post("/v1/cluster/workers/release", body);
                if (!printedJson(result)) {
                    JsonNode released = result.path("released");
                    String boundTo = released.path("tenant").isTextual()
                            ? " (tenant " + released.path("tenant").asText() + ")" : "";
                    out().println("Released " + released.path("workerId").asText() + boundTo + ": "
                            + released.path("reason").asText());
                    out().println("The worker exits 0 (released, not crashed); its supervisor decides whether to replace it.");
                }
                return 0;
            }
        }
    }

    // ----------------------------------------------------------------------

    private static PrintStream out(Io io) {
        return io.out();
    }

    private static void removePid(Io io) {
        try {
            Files.deleteIfExists(pidFile(io));
        } catch (IOException ignored) {
            // best effort
        }
    }

    static String value(String candidate) {
        return candidate == null || candidate.isBlank() ? null : candidate.trim();
    }
}
