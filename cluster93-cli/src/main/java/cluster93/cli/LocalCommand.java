package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The dedicated local master node: one permanent, durable single-node cluster on this machine.
 *
 * <p>{@code scripts/local.sh} is the orchestrator and the source of truth for the configuration in
 * {@code $HOME/.cluster93/local.env}. This command inspects that state (and its provenance in
 * {@code node.meta}), and drives the node, so the same operations are available from the CLI that
 * installed the binary. The node it launches plays the {@code api,ingress,worker,data,console} roles at once.
 */
@Command(name = "local", description = "Manage the local Cluster93 master node.",
        subcommands = {LocalCommand.Status.class, LocalCommand.Start.class, LocalCommand.Stop.class,
                LocalCommand.Restart.class, LocalCommand.Logs.class, LocalCommand.Mcp.class,
                LocalCommand.Setup.class, LocalCommand.Update.class})
final class LocalCommand extends Base {

    static final String DEFAULT_NODE_ID = "local-master";
    static final String DEFAULT_ROLES = "api,ingress,worker,data,console";
    /** The master's own store, apart from the {@code dev-data} directory {@code scripts/dev.sh} uses. */
    static final String DEFAULT_DATA_DIR = "master-data";
    static final String DEFAULT_HTTP_PORT = "8080";
    static final String DEFAULT_INGRESS_PORT = "8000";
    static final String DEFAULT_METRICS_PORT = "9090";
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

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    // ----------------------------------------------------------------------
    // state locations (the script's layout)
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
        Path file = envFile(io);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return parseKeyValues(Files.readString(file));
        } catch (IOException e) {
            return Map.of();
        }
    }

    static Map<String, String> loadMeta(Io io) {
        Path file = metaFile(io);
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
            if (Files.isDirectory(path) && Files.isRegularFile(path.resolve("scripts").resolve("local.sh"))) {
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

    static Path findScript(Io io) {
        Path root = repoRoot(io);
        if (root == null) {
            return null;
        }
        Path script = root.resolve("scripts").resolve("local.sh");
        return Files.isRegularFile(script) ? script : null;
    }

    /** Delegates to {@code scripts/local.sh}; returns -1 when the script can't be found (caller falls back). */
    static int runScript(Io io, String... args) {
        Path script = findScript(io);
        if (script == null) {
            return -1;
        }
        List<String> command = new ArrayList<>();
        command.add(script.toString());
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            throw new CliException("Couldn't run " + script + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Interrupted while running " + script);
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
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
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
    // status
    // ----------------------------------------------------------------------

    @Command(name = "status", description = "Show the local node's PID, health, nodes, and whether the checkout moved on.")
    static final class Status extends Base {

        @Override
        int execute() {
            Io io = io();
            if (!Files.isRegularFile(envFile(io))) {
                out().println("No local master node is set up.");
                out().println("Run: scripts/local.sh setup");
                return 0;
            }
            Map<String, String> config = loadConfig(io);
            Map<String, String> meta = loadMeta(io);
            String base = httpBase(config);
            String token = config.get("ADMIN_TOKEN");
            String nodeId = config.getOrDefault("NODE_ID", DEFAULT_NODE_ID);
            long pid = readPid(io);
            boolean running = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            boolean health = healthy(base, Duration.ofSeconds(3));
            String nodes = nodeIds(base, token);

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

            if (json()) {
                ObjectNode result = Json.object()
                        .put("nodeId", nodeId)
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
                result.put("installedCommit", installed == null ? "" : installed);
                result.put("currentCommit", head == null ? "" : head);
                result.put("updateAvailable", update);
                result.put("cliCommit", cliCommit);
                result.put("cliBinary", cliBinary);
                result.put("cliUpdateAvailable", cliUpdate);
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
            if (update || cliUpdate) {
                out().println();
                if (update) {
                    out().println("\u26a0\ufe0f  Update Available! Running build: " + installed + ", Current repo HEAD: " + head
                            + ". Run 'scripts/local.sh update' or 'c93 local update'.");
                }
                if (cliUpdate) {
                    out().println("\u26a0\ufe0f  Update Available! Current CLI build: " + Main.shortCommit(cliCommit)
                            + ", Current repo HEAD: " + Main.shortCommit(head)
                            + ". Run 'scripts/build-cli.sh' or 'c93 local update'.");
                }
            }
            return 0;
        }

        private String nodeIds(String base, String token) {
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
    }

    // ----------------------------------------------------------------------
    // start / stop / restart
    // ----------------------------------------------------------------------

    @Command(name = "start", description = "Start the local node (via scripts/local.sh, or java directly).")
    static final class Start extends Base {

        @Override
        int execute() {
            int code = runScript(io(), "start");
            if (code >= 0) {
                return code;
            }
            return launchDirect(io());
        }
    }

    @Command(name = "stop", description = "Stop the local node (SIGTERM, then SIGKILL after 30s).")
    static final class Stop extends Base {

        @Override
        int execute() {
            int code = runScript(io(), "stop");
            if (code >= 0) {
                return code;
            }
            return stopLocal(io());
        }
    }

    @Command(name = "restart", description = "Restart the local node.")
    static final class Restart extends Base {

        @Override
        int execute() {
            int code = runScript(io(), "restart");
            if (code >= 0) {
                return code;
            }
            stopLocal(io());
            return launchDirect(io());
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
            throw new CliException("No configuration at " + envFile(io) + ". Run: scripts/local.sh setup");
        }
        Path jar = serverJar(io);
        if (!Files.isRegularFile(jar)) {
            throw new CliException("No server jar at " + jar + ". Run: scripts/local.sh setup");
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
        return root != null ? root.resolve("cluster93-server").resolve("target").resolve("c93.jar") : Path.of("c93.jar");
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

    @Command(name = "logs", description = "Tail the local node's server log.")
    static final class Logs extends Base {

        @Option(names = {"-n", "--lines"}, defaultValue = "50", paramLabel = "N",
                description = "How many lines to show first (default: 50).")
        int lines;

        @Option(names = {"-f", "--follow"}, description = "Keep the log open and print new lines as they arrive.")
        boolean follow;

        @Override
        int execute() throws IOException {
            Path file = logFile(io());
            if (!Files.isRegularFile(file)) {
                err().println("No log file at " + file + " (is the node started?)");
                return 1;
            }
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

    @Command(name = "mcp", description = "Print the MCP configuration for the local node.")
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
            Map<String, String> config = loadConfig(io);
            Config login = Config.load(io);
            String url = login != null && login.url() != null && !login.url().isBlank()
                    ? login.url()
                    : httpBase(config);
            String token = login != null && login.token() != null ? login.token() : "";
            String stdioToken = token.isBlank() ? "" : token;

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
    // setup / update (thin wrappers over the script)
    // ----------------------------------------------------------------------

    @Command(name = "setup", description = "Run the guided local node onboarding (scripts/local.sh setup).")
    static final class Setup extends Base {

        @Option(names = {"-y", "--yes"}, description = "Accept every default without prompting.")
        boolean yes;

        @Override
        int execute() {
            int code = runScript(io(), yes ? new String[] {"setup", "--yes"} : new String[] {"setup"});
            if (code < 0) {
                throw new CliException("scripts/local.sh wasn't found; run it from a checkout of the repository.");
            }
            return code;
        }
    }

    @Command(name = "update", description = "Rebuild from the current checkout and restart the node (scripts/local.sh update).")
    static final class Update extends Base {

        @Override
        int execute() {
            int code = runScript(io(), "update");
            if (code < 0) {
                throw new CliException("scripts/local.sh wasn't found; run it from a checkout of the repository.");
            }
            return code;
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
