package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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

/**
 * The checkout stack behind {@code c93 dev} (D-190): a profile-scoped process with a fixed admin token, seeded
 * tenant/admin/console, and no service unit. It replaces {@code scripts/local.sh}'s master/dev profiles with one
 * persistent data directory and an explicit {@code reset}.
 */
final class DevService {

    static final String PROFILE = "dev";
    static final String DEFAULT_TOKEN = "dev-admin-token-1234567890abcdef";
    static final int DEFAULT_HTTP_PORT = 9393;
    static final int DEFAULT_INGRESS_PORT = 9394;
    static final int DEFAULT_METRICS_PORT = 9395;

    record State(Path dataDir, int httpPort, int ingressPort, int metricsPort, int internalPort, int clusterPort,
                 String engine) {
        String url() {
            return "http://localhost:" + httpPort;
        }

        String ingress() {
            return "http://localhost:" + ingressPort;
        }
    }

    private final Io io;
    private final Path root;

    DevService(Io io, Path root) {
        this.io = io;
        this.root = root;
    }

    static DevService open(Io io) {
        return new DevService(io, Checkout.require(io));
    }

    Path profileDir() {
        return ProfileStore.dir(io, PROFILE);
    }

    Path envFile() {
        return profileDir().resolve("dev.env");
    }

    State state() {
        JsonNode dev = ProfileStore.meta(io, PROFILE).path("dev");
        Path data = dev.path("dataDir").asText("").isBlank()
                ? NodeCommand.localHome(io).resolve("dev-data")
                : Path.of(dev.path("dataDir").asText());
        int http = dev.path("httpPort").asInt(DEFAULT_HTTP_PORT);
        int ingress = dev.path("ingressPort").asInt(DEFAULT_INGRESS_PORT);
        int metrics = dev.path("metricsPort").asInt(DEFAULT_METRICS_PORT);
        int internal = dev.path("internalPort").asInt(9396);
        int cluster = dev.path("clusterPort").asInt(5701);
        String engine = dev.path("engine").asText("graaljs");
        return new State(data, http, ingress, metrics, internal, cluster, engine);
    }

    void saveState(State state) {
        ObjectNode meta = ProfileStore.meta(io, PROFILE);
        meta.set("dev", Json.object()
                .put("dataDir", state.dataDir().toString())
                .put("httpPort", state.httpPort())
                .put("ingressPort", state.ingressPort())
                .put("metricsPort", state.metricsPort())
                .put("internalPort", state.internalPort())
                .put("clusterPort", state.clusterPort())
                .put("engine", state.engine()));
        ProfileStore.writeMeta(io, PROFILE, meta);
    }

    NodeInstall.Layout layout(State state) {
        return new NodeInstall.Layout(false, root.resolve("server").resolve("target"), envFile(),
                state.dataDir(), state.dataDir().resolve("logs"), null, NodeInstall.Layout.Kind.NONE,
                System.getProperty("user.name", ""));
    }

    private Map<String, String> config(State state) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("C93_NODE_ID", "local-dev");
        config.put("C93_ROLES", "api,ingress,worker,data,console");
        config.put("C93_CLUSTERED", "true");
        config.put("C93_CLUSTER_SIZE", "1");
        config.put("C93_CLUSTER_MEMBERS", "127.0.0.1:" + state.clusterPort());
        config.put("C93_CLUSTER_PORT", String.valueOf(state.clusterPort()));
        config.put("C93_CLUSTER_TLS", "off");
        config.put("C93_ADMIN_TOKEN", io.env().getOrDefault("C93_DEV_ADMIN_TOKEN", DEFAULT_TOKEN));
        config.put("C93_TENANT", PROFILE);
        config.put("C93_DATA_DIR", state.dataDir().toString());
        config.put("C93_LOG_DIR", state.dataDir().resolve("logs").toString());
        config.put("C93_HTTP_PORT", String.valueOf(state.httpPort()));
        config.put("C93_INGRESS_PORT", String.valueOf(state.ingressPort()));
        config.put("C93_METRICS_PORT", String.valueOf(state.metricsPort()));
        config.put("C93_INTERNAL_PORT", String.valueOf(state.internalPort()));
        config.put("C93_WORKER_MODE", "process");
        config.put("C93_WORKER_DEFAULT_MODE", state.engine());
        config.put("C93_WORKER_BINARY_JAVET", root.resolve("bin/worker-javet").toString());
        config.put("C93_WORKER_BINARY_GRAALJS", root.resolve("bin/worker-graaljs").toString());
        config.put("C93_EGRESS_ALLOW_PRIVATE", "true");
        return config;
    }

    private void writeEnv(State state) throws IOException {
        NodeInstall.writeEnv(layout(state), config(state));
        saveState(state);
    }

    /** Build/start/seed, and make the dev profile active. Idempotent when the stack is already up. */
    int up(State requested, boolean noSeed, boolean json) throws Exception {
        State state = requested;
        Checkout.ensureBuilt(io, root, json);
        writeEnv(state);
        NodeService.Target target = NodeService.target(io, layout(state));
        if (!NodeService.running(io, target)) {
            int code = NodeService.start(io, target, !json);
            if (code != 0) {
                return code;
            }
        } else if (!json) {
            io.out().println("Already running at " + state.url());
        }
        if (!noSeed) {
            seed(state, json);
        }
        ProfileStore.use(io, PROFILE);
        writeMcp(state);
        if (!json) {
            io.out().println();
            io.out().println("Cluster93 development stack");
            io.out().println("  API:      " + state.url());
            io.out().println("  Console:  " + state.ingress());
            io.out().println("  Data:     " + state.dataDir());
            io.out().println("  Login:    profile " + PROFILE + " (c93 profiles use " + PROFILE + ")");
            io.out().println("  Stop:     c93 dev down    Reset: c93 dev reset --yes");
        }
        return 0;
    }

    /** Create the system/dev tenants and the admin operator, then save the operator login to the dev profile. */
    int seed(State state, boolean json) throws Exception {
        String adminToken = io.env().getOrDefault("C93_DEV_ADMIN_TOKEN", DEFAULT_TOKEN);
        String url = state.url();
        Api admin = new Api(new Config(url, adminToken));
        tenant(admin, "system");
        tenant(admin, PROFILE);

        String token = null;
        Config saved = Config.load(io, PROFILE);
        if (saved != null && saved.token() != null && saved.url() != null && saved.url().equals(url)) {
            try {
                JsonNode who = new Api(saved).get("/v1/whoami");
                if ("admin".equals(Json.text(who, "user"))) {
                    token = saved.token();
                }
            } catch (CliException ignored) {
                // the token is stale or the user was removed; mint a fresh one below
            }
        }
        if (token == null) {
            JsonNode invite = admin.post("/v1/admin/invites", Json.object().put("note", "dev operator"));
            String code = invite.path("code").asText("");
            if (code.isBlank()) {
                throw new CliException("Couldn't invite the dev operator: " + invite);
            }
            JsonNode redeemed = new Api(new Config(url, null)).post("/v1/invites/redeem",
                    Json.object().put("code", code).put("name", "admin").put("tokenName", "operator"));
            token = redeemed.path("token").path("token").asText("");
            if (token.isBlank()) {
                throw new CliException("Couldn't redeem the dev operator invite: " + redeemed);
            }
        }
        new Config(url, token, PROFILE).save(io, PROFILE);
        ensureConsole(state, admin);
        if (!json) {
            io.out().println("Seeded tenant '" + PROFILE + "' and operator 'admin' (" + url + ").");
        }
        return 0;
    }

    private void tenant(Api admin, String name) {
        try {
            admin.post("/v1/tenants", Json.object().put("name", name));
        } catch (CliException ignored) {
            // an existing tenant is the normal case on a second run; a real authorization error surfaces below
        }
    }

    private void ensureConsole(State state, Api admin) {
        Path console = root.resolve("console");
        if (!Files.isDirectory(console)) {
            return;
        }
        Path index = console.resolve("out").resolve("index.html");
        if (!Files.isRegularFile(index)) {
            if (!Files.isRegularFile(console.resolve("package.json")) || !hasCommand("npm")) {
                return;
            }
            try {
                Process process = new ProcessBuilder("npm", "run", "build").directory(console.toFile())
                        .inheritIO().start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                return;
            }
        }
        if (!Files.isRegularFile(index) || servesConsole(state)) {
            return;
        }
        try {
            JsonNode minted = admin.post("/v1/tenants/system/tokens",
                    Json.object().put("name", "console-deploy").put("scopes", Json.array().add("*")));
            String system = minted.path("token").asText("");
            if (!system.isBlank()) {
                Main.run(io, "--profile", PROFILE, "--url", state.url(), "--token", system,
                        "apps", "deploy", console.toString());
            }
        } catch (CliException ignored) {
            // A console is a convenience; the dev stack still works without it.
        }
    }

    private boolean servesConsole(State state) {
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create(state.ingress())).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.body() != null && response.body().contains("Cluster93");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasCommand(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir).resolve(name))) {
                return true;
            }
        }
        return false;
    }

    void writeMcp(State state) throws IOException {
        Config config = Config.load(io, PROFILE);
        if (config == null || config.token() == null) {
            return;
        }
        ObjectNode server = Json.object().put("type", "http").put("url", state.url() + "/mcp");
        server.set("headers", Json.object().put("Authorization", "Bearer " + config.token()));
        ObjectNode mcp = Json.object();
        ObjectNode servers = Json.object();
        servers.set("c93", server);
        mcp.set("mcpServers", servers);
        ProfileStore.writeAtomically(NodeCommand.mcpFile(io), Json.pretty(mcp) + System.lineSeparator());
    }

    int status(State state, boolean json) {
        NodeService.Target target = NodeService.target(io, layout(state));
        boolean running = NodeService.running(io, target);
        long pid = NodeService.pidOf(io, target);
        boolean health = NodeCommand.awaitHealth(state.url(), 2);
        String nodes = "";
        try {
            JsonNode answer = new Api(new Config(state.url(), io.env().getOrDefault("C93_DEV_ADMIN_TOKEN", DEFAULT_TOKEN)))
                    .get("/v1/nodes");
            List<String> ids = new ArrayList<>();
            answer.path("nodes").forEach(node -> ids.add(node.path("id").asText("")));
            nodes = String.join(", ", ids);
        } catch (CliException ignored) {
            // leave blank when the stack is not up yet
        }
        if (json) {
            Output.json(io.out(), Json.object().put("profile", PROFILE).put("url", state.url())
                    .put("ingress", state.ingress()).put("dataDir", state.dataDir().toString())
                    .put("running", running).put("pid", pid).put("health", health ? "ok" : "unreachable")
                    .put("nodes", nodes));
            return 0;
        }
        io.out().println("Cluster93 development stack");
        io.out().println("  Node ID:        local-dev");
        io.out().println("  Data:           " + state.dataDir());
        io.out().println("  API:            " + state.url());
        io.out().println("  Console:        " + state.ingress());
        io.out().println("  Worker engine:  " + state.engine());
        io.out().println("  PID:            " + (pid > 0 ? pid + (running ? " (running)" : "") : "(not running)"));
        io.out().println("  health:         " + (health ? "ok" : "unreachable"));
        io.out().println("  nodes:          " + (nodes.isBlank() ? "-" : nodes));
        return 0;
    }

    int logs(State state, int lines, boolean follow) throws IOException, InterruptedException {
        Path log = state.dataDir().resolve("logs").resolve("server.log");
        if (!Files.isRegularFile(log)) {
            throw new CliException("No log file at " + log + " (is the stack started?)", 1);
        }
        List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
        for (int i = Math.max(0, all.size() - lines); i < all.size(); i++) {
            io.out().println(all.get(i));
        }
        if (follow) {
            long position = Files.size(log);
            while (true) {
                Thread.sleep(500);
                long size = Files.size(log);
                if (size < position) {
                    position = 0;
                }
                if (size > position) {
                    try (var channel = Files.newByteChannel(log)) {
                        channel.position(position);
                        byte[] bytes = new byte[(int) (size - position)];
                        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                        channel.read(buffer);
                        io.out().print(new String(bytes, StandardCharsets.UTF_8));
                    }
                    position = size;
                }
            }
        }
        return 0;
    }

    int down(State state, boolean json) {
        int code = NodeService.stop(io, NodeService.target(io, layout(state)), !json);
        if (!json) {
            io.out().println(code == 0 ? "Stopped the development stack." : "The stack didn't stop cleanly.");
        }
        return code;
    }

    int reset(State state, boolean yes, boolean json) throws IOException {
        if (!yes) {
            if (!io.interactive()) {
                throw new CliException("Refusing to delete " + state.dataDir() + " without --yes", 2);
            }
            io.out().print("Delete " + state.dataDir() + " and its data? [y/N] ");
            io.out().flush();
            String answer = new java.io.BufferedReader(new java.io.InputStreamReader(io.in(), StandardCharsets.UTF_8)).readLine();
            if (answer == null || !answer.trim().toLowerCase(java.util.Locale.ROOT).startsWith("y")) {
                return 1;
            }
        }
        down(state, true);
        NodeInstall.deleteTree(state.dataDir());
        if (!json) {
            io.out().println("Removed " + state.dataDir() + ". Run: c93 dev up");
        }
        return 0;
    }

    int build(boolean json) throws IOException, InterruptedException {
        Checkout.buildServer(io, root, json);
        Checkout.ensureBuilt(io, root, json);
        if (!json) {
            io.out().println("Build complete.");
        }
        return 0;
    }

    int testExamples(State state) {
        Path examples = root.resolve("examples/js");
        if (!Files.isDirectory(examples)) {
            throw new CliException("No examples at " + examples, 1);
        }
        Config login = Config.load(io, PROFILE);
        if (login == null || login.token() == null) {
            throw new CliException("No dev login; run: c93 dev up", 2);
        }
        try (var files = Files.list(examples)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".js")).sorted().toList()) {
                String name = file.getFileName().toString();
                String source = Files.readString(file);
                if (source.startsWith("// c93: service")) {
                    String service = name.substring(0, name.length() - 3);
                    if (Main.run(io, "--profile", PROFILE, "services", "deploy", service, file.toString()) != 0) {
                        throw new CliException("Example " + name + " failed to deploy", 1);
                    }
                    boolean served = false;
                    for (int i = 0; i < 30 && !served; i++) {
                        try {
                            HttpResponse<String> response = HttpClient.newHttpClient().send(
                                    HttpRequest.newBuilder(URI.create("http://localhost:" + state.ingressPort() + "/"))
                                            .header("Host", service + "--" + PROFILE + ".localhost").GET().build(),
                                    HttpResponse.BodyHandlers.ofString());
                            served = response.statusCode() < 500;
                        } catch (Exception e) {
                            served = false;
                        }
                        if (!served) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    Main.run(io, "--profile", PROFILE, "services", "rm", service);
                    if (!served) {
                        throw new CliException("Example " + name + " never answered on the ingress", 1);
                    }
                } else if (Main.run(io, "--profile", PROFILE, "run", file.toString()) != 0) {
                    throw new CliException("Example " + name + " failed", 1);
                }
                io.out().println(name + ": OK");
            }
        } catch (IOException e) {
            throw new CliException("Couldn't read examples: " + e.getMessage(), 1);
        }
        io.out().println("All examples passed.");
        return 0;
    }
}
