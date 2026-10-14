package cluster93.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Drives an installed node: through systemd (user or system), launchd on macOS, or the process directly when there
 * is no service manager (a container, WSL, or a plain shell). The backend is detected from what is on disk and on
 * the PATH, so {@code c93 node start} does the right thing wherever the node landed (D-134).
 */
final class NodeService {

    /** How a node is run on this machine. */
    enum Backend { SYSTEMD_USER, SYSTEMD_SYSTEM, LAUNCHD, DIRECT, CONTAINER }

    /** A node to drive: its backend, layout, and the {@code node.env} it reads. */
    record Target(Backend backend, NodeInstall.Layout layout, Map<String, String> config) {
    }

    static final String UNIT = "cluster93.service";

    private NodeService() {
    }

    /** Loads the node's layout and config, and detects the backend to drive it with. */
    static Target target(Io io, NodeInstall.Layout layout) {
        return new Target(detect(io, layout), layout, NodeCommand.parseKeyValues(read(io, layout.envFile())));
    }

    static boolean isInstalled(Io io, NodeInstall.Layout layout) {
        return layout != null && Files.isRegularFile(layout.envFile());
    }

    /** The backend, in preference order: a system unit, a user unit, a LaunchAgent, else the process itself. */
    static Backend detect(Io io, NodeInstall.Layout layout) {
        // A container node is defined by its compose file, whatever the host's service manager is (D-141).
        if (layout.container()) {
            return Backend.CONTAINER;
        }
        if (layout.unitKind() == NodeInstall.Layout.Kind.LAUNCHD) {
            if (layout.unitFile() != null && Files.isRegularFile(layout.unitFile()) && executable(io, "launchctl") != null) {
                return Backend.LAUNCHD;
            }
            return Backend.DIRECT;
        }
        if (executable(io, "systemctl") != null && layout.unitFile() != null && Files.isRegularFile(layout.unitFile())) {
            return layout.system() ? Backend.SYSTEMD_SYSTEM : Backend.SYSTEMD_USER;
        }
        return Backend.DIRECT;
    }

    static int start(Io io, Target target) {
        return start(io, target, true);
    }

    /** Starts the node; {@code print} gates the direct-process progress lines (off for {@code --json}). */
    static int start(Io io, Target target, boolean print) {
        return switch (target.backend()) {
            case SYSTEMD_USER -> systemctl(io, target, true, "start");
            case SYSTEMD_SYSTEM -> systemctl(io, target, false, "start");
            case LAUNCHD -> launchctl(io, "kickstart", "-k", launchTarget());
            case DIRECT -> directStart(io, target, print);
            case CONTAINER -> compose(io, target, "up", "-d");
        };
    }

    static int stop(Io io, Target target, boolean print) {
        return switch (target.backend()) {
            case SYSTEMD_USER -> systemctl(io, target, true, "stop");
            case SYSTEMD_SYSTEM -> systemctl(io, target, false, "stop");
            case LAUNCHD -> launchctl(io, "bootout", "gui/" + uid() + "/" + NodeInstall.LAUNCHD_LABEL);
            case DIRECT -> directStop(io, target, print);
            case CONTAINER -> compose(io, target, "stop");
        };
    }

    static int restart(Io io, Target target) {
        return switch (target.backend()) {
            case SYSTEMD_USER -> systemctl(io, target, true, "restart");
            case SYSTEMD_SYSTEM -> systemctl(io, target, false, "restart");
            case LAUNCHD -> launchctl(io, "kickstart", "-k", launchTarget());
            case DIRECT -> {
                directStop(io, target, false);
                yield directStart(io, target, true);
            }
            case CONTAINER -> compose(io, target, "up", "-d", "--force-recreate");
        };
    }

    static boolean running(Io io, Target target) {
        return switch (target.backend()) {
            case SYSTEMD_USER -> run(io, executable(io, "systemctl"), true, "is-active", "--quiet", UNIT) == 0;
            case SYSTEMD_SYSTEM -> run(io, executable(io, "systemctl"), false, "is-active", "--quiet", UNIT) == 0;
            case LAUNCHD -> run(io, executable(io, "launchctl"), true, "print", launchTarget()) == 0;
            case DIRECT -> pidOf(io, target) > 0;
            // `ps -q` prints a container id only while the service is running, so empty output means down.
            case CONTAINER -> containerRunning(io, target);
        };
    }

    /** The direct-process PID: the node's pid file, or -1 when it is not running. */
    static long pidOf(Io io, Target target) {
        Path file = pidFile(target.layout());
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try {
            long pid = Long.parseLong(Files.readString(file).strip());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(ProcessHandle::pid).orElse(-1L);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    static Path pidFile(NodeInstall.Layout layout) {
        return layout.dataDir().resolve("c93.pid");
    }

    // ----------------------------------------------------------------------
    // backends
    // ----------------------------------------------------------------------

    // ----------------------------------------------------------------------
    // container backend (D-141)
    // ----------------------------------------------------------------------

    /** The engine a container node runs under, from {@code node.meta}; docker when it isn't recorded. */
    static String engine(NodeInstall.Layout layout) {
        String engine = NodeCommand.loadKeyValues(NodeInstall.metaFile(layout)).get("engine");
        return engine == null || engine.isBlank() ? "docker" : engine;
    }

    /** The compose project a container node belongs to, from {@code node.meta}. */
    static String project(NodeInstall.Layout layout) {
        String project = NodeCommand.loadKeyValues(NodeInstall.metaFile(layout)).get("project");
        return project == null || project.isBlank() ? "cluster93" : project;
    }

    /** The engine's CLI for this layout, refusing clearly when it isn't installed. */
    private static String engineCli(Io io, NodeInstall.Layout layout) {
        String engine = engine(layout);
        String cli = executable(io, engine);
        if (cli == null) {
            throw new CliException("`" + engine + "` isn't on the PATH, so this node's container can't be driven here");
        }
        return cli;
    }

    /** Runs {@code <engine> compose -p <project> -f <compose> …} for a container node. */
    private static int compose(Io io, Target target, String... args) {
        List<String> command = composeCommand(io, target.layout());
        command.addAll(List.of(args));
        return run(io, command.toArray(String[]::new), false);
    }

    /** The container's log through the engine, so `c93 node logs` works the same way everywhere. */
    static int logs(Io io, Target target, int tail, boolean follow) {
        return follow
                ? compose(io, target, "logs", "--tail", String.valueOf(tail), "--follow")
                : compose(io, target, "logs", "--tail", String.valueOf(tail));
    }

    /** The compose command prefix, shared by the verbs and the state check. */
    private static List<String> composeCommand(Io io, NodeInstall.Layout layout) {
        List<String> command = new ArrayList<>();
        command.add(engineCli(io, layout));
        command.add("compose");
        command.add("-p");
        command.add(project(layout));
        command.add("-f");
        command.add(layout.composeFile().toString());
        return command;
    }

    /** Whether the node's container is up: {@code compose ps -q} prints an id only while it runs. */
    private static boolean containerRunning(Io io, Target target) {
        String cli = executable(io, engine(target.layout()));
        if (cli == null) {
            return false;
        }
        List<String> command = composeCommand(io, target.layout());
        command.add("ps");
        command.add("--status");
        command.add("running");
        command.add("-q");
        return capture(io, command.toArray(String[]::new)).map(out -> !out.isBlank()).orElse(false);
    }

    private static int systemctl(Io io, Target target, boolean user, String verb) {
        String systemctl = executable(io, "systemctl");
        if (systemctl == null) {
            throw new CliException("systemctl isn't on the PATH; this node's unit can't be driven here");
        }
        List<String> command = new ArrayList<>();
        if (target.backend() == Backend.SYSTEMD_SYSTEM && !isRoot()) {
            command.add(executable(io, "sudo") == null ? "sudo" : executable(io, "sudo"));
        }
        command.add(systemctl);
        if (user) {
            command.add("--user");
        }
        command.add("daemon-reload");
        int reload = run(io, command.toArray(String[]::new), false);
        // A failed daemon-reload (an old systemd, a chroot) doesn't stop the verb itself from being tried.
        if (reload != 0 && !"start".equals(verb)) {
            return reload;
        }
        command.remove(command.size() - 1);
        command.add(verb);
        command.add(UNIT);
        return run(io, command.toArray(String[]::new), false);
    }

    private static int launchctl(Io io, String... args) {
        String launchctl = executable(io, "launchctl");
        if (launchctl == null) {
            throw new CliException("launchctl isn't on the PATH");
        }
        List<String> command = new ArrayList<>();
        command.add(launchctl);
        command.addAll(List.of(args));
        return run(io, command.toArray(String[]::new), false);
    }

    private static int directStart(Io io, Target target, boolean print) {
        Path jar = target.layout().programDir().resolve(NodeInstall.SERVER_JAR);
        if (!Files.isRegularFile(jar)) {
            throw new CliException("No server jar at " + jar + ". Run: c93 node install");
        }
        if (pidOf(io, target) > 0) {
            if (print) {
                io.out().println("Already running.");
            }
            return 0;
        }
        Path log = target.layout().logDir() != null
                ? target.layout().logDir().resolve("server.log")
                : target.layout().dataDir().resolve("server.log");
        try {
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(NodeCommand.javaBinary(io), "-jar", jar.toString());
            builder.command().addAll(1, NodeCommand.IGNITE_OPENS);
            Map<String, String> environment = builder.environment();
            environment.putAll(target.config());
            builder.redirectOutput(log.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            Files.writeString(pidFile(target.layout()), Long.toString(process.pid()));
            if (print) {
                io.out().println("Started pid " + process.pid() + " (log " + log + ")");
            }
        } catch (IOException e) {
            throw new CliException("Couldn't start the node: " + e.getMessage());
        }
        String base = httpBase(target.config());
        if (NodeCommand.awaitHealth(base, 30)) {
            if (print) {
                io.out().println("Up at " + base + " (health OK)");
            }
            return 0;
        }
        throw new CliException("The node didn't answer " + base + "/health within 30s; see " + log);
    }

    private static int directStop(Io io, Target target, boolean print) {
        long pid = pidOf(io, target);
        if (pid <= 0) {
            if (print) {
                io.out().println("Not running.");
            }
            NodeInstall.delete(pidFile(target.layout()));
            return 0;
        }
        if (print) {
            io.out().println("Stopping pid " + pid);
        }
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            NodeInstall.delete(pidFile(target.layout()));
            return 0;
        }
        handle.destroy();
        for (int attempt = 0; attempt < 30; attempt++) {
            if (!handle.isAlive()) {
                NodeInstall.delete(pidFile(target.layout()));
                if (print) {
                    io.out().println("Stopped");
                }
                return 0;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        handle.destroyForcibly();
        NodeInstall.delete(pidFile(target.layout()));
        if (print) {
            io.out().println("Still alive after 30s; sent SIGKILL");
        }
        return 0;
    }

    // ----------------------------------------------------------------------
    // process helpers
    // ----------------------------------------------------------------------

    /** The HTTP base for health checks, from the node's config. */
    static String httpBase(Map<String, String> config) {
        return "http://localhost:" + config.getOrDefault("C93_HTTP_PORT", "9393");
    }

    /** The absolute path of a command on the caller's PATH, or null. */
    static String executable(Io io, String name) {
        String path = io.env().get("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static int run(Io io, String command, boolean quiet, String... args) {
        List<String> full = new ArrayList<>();
        full.add(command);
        full.addAll(List.of(args));
        return run(io, full.toArray(String[]::new), quiet);
    }

    private static int run(Io io, String[] command, boolean quiet) {
        if (command == null || command[0] == null) {
            throw new CliException("No command to run");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (quiet) {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            } else {
                builder.inheritIO();
            }
            Process process = builder.start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 1;
            }
            return process.exitValue();
        } catch (IOException e) {
            throw new CliException("Couldn't run " + String.join(" ", command) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** Captures a command's first output line, for status. */
    static Optional<String> capture(Io io, String... command) {
        if (command == null || command.length == 0 || command[0] == null) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The label a LaunchAgent is addressed by: {@code gui/<uid>/com.cluster93.node}. */
    static String launchTarget() {
        return "gui/" + uid() + "/" + NodeInstall.LAUNCHD_LABEL;
    }

    static long uid() {
        Object uid = unixUid();
        return uid instanceof Integer id ? id : -1;
    }

    static boolean isRoot() {
        return unixUid() instanceof Integer id && id == 0;
    }

    static Object unixUid() {
        try {
            return Files.getAttribute(Path.of(System.getProperty("user.home", ".")), "unix:uid");
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(Io io, Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    /** Merges the node's env with the process environment, for a direct launch. */
    static Map<String, String> environment(Map<String, String> config) {
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        environment.putAll(config);
        return environment;
    }
}
