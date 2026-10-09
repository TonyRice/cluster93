package automately.cli;

import automately.server.Automately;
import automately.server.Automately.RunningNode;
import automately.server.ServerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real npm libraries, end to end (D-093): installed from a pinned lockfile, bundled by the project's own esbuild,
 * published with the CLI, imported from jobs, and run in the isolate. Each library's output must equal what the same
 * module prints under real Node. Libraries that need what the isolate doesn't have (Node modules it lacks, such as http,
 * or native code) must be refused at publish with the reason, or fail clearly when they run.
 *
 * <p>Needs node, npm and (the first time) the network, so it runs only when {@code AUTOMATELY_TEST_REAL_NPM} names a
 * folder to work in; the installed {@code node_modules} is kept there between runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealNpmPackagesTest {

    private static final String ADMIN = "real-npm-admin-token-0123456789abcdef-0123456789abcdef";
    private static final Path FIXTURE = Path.of("src/test/resources/realnpm");

    /** Libraries that run in the isolate, one module each in src/; the parameterized test below names the same. */
    private static final List<String> WORKING = List.of("lodash", "datefns", "dayjs", "zod", "marked", "jsyaml", "yaml", "ajv", "semver", "immer",
            "rxjs", "mustache", "events", "validator", "uuid", "nanoid", "nodecrypto", "papaparse", "csvparse", "jwt", "nodecryptokeys", "nodemodules", "webcrypto", "globals", "immediates");
    /** The HTTP libraries (D-109), published as a second package: one would be over a version's 10 MB. */
    private static final List<String> NETWORK = List.of("httpclient", "axios", "nodefetch", "got", "sockets", "http2client", "gothttp2", "dnsclient", "webglobals", "wsclient", "wsglobal");
    /**
     * Database and mail drivers (D-116), run against real servers in containers: two more packages, since together
     * they'd be over a version's 10 MB.
     */
    private static final List<String> DRIVERS = List.of("pg", "mysql2", "ioredis", "nodemailer");
    private static final List<String> MONGO = List.of("mongodb");
    /** Web frameworks run as services (D-123): their fixtures only listen, under Node and through the ingress. */
    private static final List<String> WEB = List.of("express", "koa", "fastify");
    /** Set to run the drivers: they need Docker for their servers. */
    private static final boolean SERVICES = System.getenv("AUTOMATELY_TEST_REAL_SERVICES") != null;
    private final List<org.testcontainers.containers.GenericContainer<?>> containers = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private RunningNode node;
    /** The HTTP libraries' server (D-109), the same one for Node and the isolate: TEST_HTTP. */
    private io.vertx.core.Vertx serverVertx;
    /** The dnsclient fixture's DNS server (D-122), and mongodb+srv's: Node and the isolate both set it as their server. */
    private automately.core.host.dns.TestDnsServer dnsServer;
    private String testHttp;
    /** What both Node and the isolate are given: the servers' addresses, and the TLS servers' CA (D-116). */
    private final Map<String, String> testEnv = new java.util.LinkedHashMap<>();
    private ServerSocket halfClose;
    private Path project;
    private Path config;

    record Result(int code, String out, String err) {
    }

    @BeforeAll
    void setUp() throws Exception {
        String work = System.getenv("AUTOMATELY_TEST_REAL_NPM");
        Assumptions.assumeTrue(work != null && !work.isBlank(), "Set AUTOMATELY_TEST_REAL_NPM to a work folder to run real npm packages");
        project = Path.of(work).toAbsolutePath().resolve("realworld");
        // The isolate reports the Node release its stand-ins are checked against (D-115): compare against that one.
        Process version = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
        String nodeVersion = new String(version.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(nodeVersion).as("The isolate reports Node v26.8.1 (js/node/process.js): run this test with that release, "
                + "or move process.version and this check together").isEqualTo("v26.8.1");
        install();
        serverVertx = io.vertx.core.Vertx.vertx();
        testHttp = "http://127.0.0.1:" + await(serverVertx.createHttpServer().requestHandler(this::serve)
                .listen(0, "127.0.0.1")).actualPort();
        testEnv.put("TEST_HTTP", testHttp);
        startSocketServers();
        startDnsServer();
        startWebSocketServer();
        if (SERVICES) {
            startServices();
        }
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "real-npm"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", ADMIN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "4"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "128"),
                // The HTTP libraries' server is on loopback, which is this node: only the development switch reaches it,
                // and then not on the node's own ports (D-108).
                Map.entry("AUTOMATELY_EGRESS_ALLOW_PRIVATE", "true")))));
        config = Files.createTempDirectory("real-npm-cli").resolve("config");
        String url = "http://localhost:" + node.httpPort();
        assertThat(cli("--url", url, "--token", ADMIN, "tenants", "create", "realnpm").code()).isZero();
        String token = cli("--url", url, "--token", ADMIN, "tenants", "token", "realnpm", "test", "--scope", "*").out().strip();
        assertThat(cli(Map.of(), token + "\n", "login", url).code()).isZero();

        // One version exporting every working library: one bundle, shared chunks and all.
        StringBuilder exports = new StringBuilder();
        for (String lib : WORKING) {
            exports.append(exports.isEmpty() ? "" : ", ").append("\"./").append(lib).append("\": \"src/").append(lib).append(".js\"");
        }
        Files.writeString(project.resolve("automately.json"), "{\"name\": \"realworld\", \"version\": \"1.0.0\", \"exports\": {" + exports + "}}");
        Result published = cli("packages", "publish", project.toString());
        assertThat(published.code()).as(published.err()).isZero();
        Path net = project.resolve("variant-net");
        Files.createDirectories(net.resolve("src"));
        StringBuilder netExports = new StringBuilder();
        for (String lib : NETWORK) {
            Files.copy(project.resolve("src/" + lib + ".js"), net.resolve("src/" + lib + ".js"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            netExports.append(netExports.isEmpty() ? "" : ", ").append("\"./").append(lib).append("\": \"src/").append(lib).append(".js\"");
        }
        if (!Files.exists(net.resolve("node_modules"))) {
            Files.createSymbolicLink(net.resolve("node_modules"), project.resolve("node_modules"));
        }
        Files.writeString(net.resolve("automately.json"), "{\"name\": \"realworld-net\", \"version\": \"1.0.0\", \"exports\": {" + netExports + "}}");
        Result publishedNet = cli("packages", "publish", net.toString());
        assertThat(publishedNet.code()).as(publishedNet.err()).isZero();
        // One package each: together they're over the 10 MB a package version may be.
        for (String web : WEB) {
            publishVariant("realworld-" + web, List.of(web));
        }
        publishVariant("realworld-wsserver", List.of("wsserver"));
        if (SERVICES) {
            publishVariant("realworld-db", DRIVERS);
            publishVariant("realworld-mongo", MONGO);
        }
    }

    /** Publishes {@code libs} as one package of their own, sharing the installed node_modules. */
    private void publishVariant(String name, List<String> libs) throws IOException {
        Path dir = project.resolve("variant-" + name);
        Files.createDirectories(dir.resolve("src"));
        StringBuilder exports = new StringBuilder();
        for (String lib : libs) {
            Files.copy(project.resolve("src/" + lib + ".js"), dir.resolve("src/" + lib + ".js"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            exports.append(exports.isEmpty() ? "" : ", ").append("\"./").append(lib).append("\": \"src/").append(lib).append(".js\"");
        }
        if (!Files.exists(dir.resolve("node_modules"))) {
            Files.createSymbolicLink(dir.resolve("node_modules"), project.resolve("node_modules"));
        }
        Files.writeString(dir.resolve("automately.json"), "{\"name\": \"" + name + "\", \"version\": \"1.0.0\", \"exports\": {" + exports + "}}");
        Result published = cli("packages", "publish", dir.toString());
        assertThat(published.code()).as(name + ": " + published.err()).isZero();
    }

    /** Copies the fixture and installs its pinned lockfile, unless the same lockfile is already installed there. */
    private void install() throws IOException, InterruptedException {
        Files.createDirectories(project.resolve("src"));
        try (Stream<Path> files = Files.walk(FIXTURE)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = project.resolve(FIXTURE.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // The lockfile last installed here; a changed fixture lockfile installs again.
        Path stamp = project.resolve("node_modules/.automately-installed-lock.json");
        byte[] lock = Files.readAllBytes(project.resolve("package-lock.json"));
        if (Files.exists(stamp) && java.util.Arrays.equals(Files.readAllBytes(stamp), lock) && Files.exists(project.resolve("node_modules/esbuild"))) {
            return;
        }
        // --ignore-scripts: native packages are only inspected, never built.
        Process npm = new ProcessBuilder("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund").directory(project.toFile())
                .redirectErrorStream(true).start();
        String log = new String(npm.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(npm.waitFor()).as("npm ci: " + log).isZero();
        Files.write(stamp, lock);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (node != null) {
            await(node.stop());
        }
        if (dnsServer != null) {
            dnsServer.close();
        }
        if (serverVertx != null) {
            await(serverVertx.close());
        }
        if (halfClose != null) {
            halfClose.close();
        }

        containers.forEach(org.testcontainers.containers.GenericContainer::stop);
    }

    @ParameterizedTest(name = "{0} gives the same answer as under Node")
    @ValueSource(strings = {"lodash", "datefns", "dayjs", "zod", "marked", "jsyaml", "yaml", "ajv", "semver", "immer", "rxjs", "mustache", "events", "validator", "uuid", "nanoid", "nodecrypto", "papaparse", "csvparse", "jwt", "nodecryptokeys", "nodemodules", "immediates", "http2client", "gothttp2", "dnsclient", "webglobals", "wsclient", "wsglobal"})
    void aLibraryRunsInTheIsolateAsItDoesInNode(String lib) throws Exception {
        sameAsNode(lib);
    }

    @ParameterizedTest(name = "{0} talks to a real server as it does under Node")
    @ValueSource(strings = {"pg", "mysql2", "ioredis", "mongodb", "nodemailer"})
    void aDriverTalksToItsServerAsItDoesUnderNode(String lib) throws Exception {
        Assumptions.assumeTrue(SERVICES, "Set AUTOMATELY_TEST_REAL_SERVICES (and have Docker) to run the drivers against real servers");
        sameAsNode(lib);
    }

    @ParameterizedTest(name = "{0} serves through the ingress as it does under Node")
    @ValueSource(strings = {"express", "koa", "fastify"})
    void aWebFrameworkServesAsItDoesUnderNode(String lib) throws Exception {
        // Under real Node, listening on a free port.
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder("node", "--no-warnings", "--input-type=module", "-e", "await import('./src/" + lib + ".js')")
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(project.resolve(lib + ".node.log").toFile());
        builder.environment().put("PORT", String.valueOf(port));
        Process nodeApp = builder.start();
        io.vertx.core.http.HttpClient http = serverVertx.createHttpClient();
        try {
            awaitListening(port, nodeApp, lib);
            // In the cluster, as a service whose script only imports it: no default export (D-123).
            Path script = project.resolve("service-" + lib + ".mjs");
            Files.writeString(script, "import 'realworld-" + lib + "/" + lib + "';\n");
            Result deployed = cli("services", "deploy", lib, script.toString());
            assertThat(deployed.code()).as(lib + ": " + deployed.err()).isZero();
            String host = lib + "--realnpm.localhost";

            List<String> underNode = exchanges(http, new RequestOptions().setHost("127.0.0.1").setPort(port), false);
            List<String> inService = exchanges(http, new RequestOptions().setHost("127.0.0.1").setPort(node.ingressPort())
                    .putHeader("Host", host), true);
            assertThat(String.join("\n\n", inService)).as(() -> lib + " (the service: " + cli("services", "get", lib).out() + ", its run: "
                            + cli("jobs", "get", Json.parse(cli("services", "get", lib, "--json").out()).path("currentJobId").asText("none"), "--json").out() + ")")
                    .isEqualTo(String.join("\n\n", underNode));
        } finally {
            nodeApp.destroyForcibly();
            await(http.close());
            cli("services", "rm", lib);
        }
    }

    /**
     * The ws library's server (D-125), under Node on a port and as a service through the ingress: the same WebSocket
     * conversations with both, compared. The handshake's answer (but its accept key, which follows the random request key),
     * messages both ways with and without compression, pings, closes from either side, and a refused upgrade.
     */
    @Test
    void aWebSocketServerServesAsItDoesUnderNode() throws Exception {
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder("node", "--no-warnings", "--input-type=module", "-e", "await import('./src/wsserver.js')")
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(project.resolve("wsserver.node.log").toFile());
        builder.environment().put("PORT", String.valueOf(port));
        Process nodeApp = builder.start();
        io.vertx.core.http.WebSocketClient client = serverVertx.createWebSocketClient(new io.vertx.core.http.WebSocketClientOptions()
                .setTryUsePerMessageCompression(true).setMaxFrameSize(1024 * 1024).setMaxMessageSize(4 * 1024 * 1024));
        try {
            awaitListening(port, nodeApp, "wsserver");
            Path script = project.resolve("service-wsserver.mjs");
            Files.writeString(script, "import 'realworld-wsserver/wsserver';\n");
            Result deployed = cli("services", "deploy", "wsserver", script.toString());
            assertThat(deployed.code()).as(deployed.err()).isZero();
            String host = "wsserver--realnpm.localhost";
            // The service may still be starting its server.
            long deadline = System.currentTimeMillis() + 30_000;
            while (!probe(client, node.ingressPort(), host) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertThat(probe(client, port, null)).as("wsserver under node").isTrue();
            List<String> underNode = conversation(client, port, null);
            List<String> inService;
            try {
                inService = conversation(client, node.ingressPort(), host);
            } catch (Exception e) {
                throw new AssertionError("wsserver: " + e + " (its last run: " + cli("jobs", "ls", "--json").out() + ")", e);
            }
            assertThat(String.join("\n", inService)).isEqualTo(String.join("\n", underNode));
        } finally {
            nodeApp.destroyForcibly();
            await(client.close());
            cli("services", "rm", "wsserver");
        }
    }

    /** Whether a WebSocket connects (then closes it): the service may still be starting. */
    private boolean probe(io.vertx.core.http.WebSocketClient client, int port, String host) throws Exception {
        io.vertx.core.http.WebSocketConnectOptions options = new io.vertx.core.http.WebSocketConnectOptions().setHost("127.0.0.1")
                .setPort(port).setURI("/probe");
        if (host != null) {
            options.addHeader("Host", host);
        }
        try {
            await(await(client.connect(options)).close());
            return true;
        } catch (java.util.concurrent.ExecutionException notYet) {
            return false;
        }
    }

    /** Connects with its handlers set as it opens, on the event loop, so the server's first message can't come before them. */
    private static io.vertx.core.http.WebSocket open(io.vertx.core.http.WebSocketClient client, io.vertx.core.http.WebSocketConnectOptions options,
                                                     java.util.concurrent.BlockingQueue<String> received) throws Exception {
        return await(client.connect(options).map(ws -> {
            ws.textMessageHandler(text -> received.add("text " + (text.length() > 200 ? text.length() + " chars" : text)));
            ws.binaryMessageHandler(bytes -> received.add("binary " + (bytes.length() > 200 ? bytes.length() + " bytes"
                    : bytes.toString(StandardCharsets.ISO_8859_1))));
            ws.pongHandler(data -> received.add("pong " + data));
            return ws;
        }));
    }

    /** One run of WebSocket conversations with the server, as lines to compare. */
    private List<String> conversation(io.vertx.core.http.WebSocketClient client, int port, String host) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        io.vertx.core.http.WebSocketConnectOptions options = new io.vertx.core.http.WebSocketConnectOptions().setHost("127.0.0.1").setPort(port)
                .setURI("/chat?room=1").setSubProtocols(List.of("chat", "other")).addHeader("X-Custom", "c");
        if (host != null) {
            options.addHeader("Host", host);
        }
        java.util.concurrent.LinkedBlockingQueue<String> received = new java.util.concurrent.LinkedBlockingQueue<>();
        io.vertx.core.http.WebSocket ws;
        try {
            ws = open(client, options, received);
        } catch (java.util.concurrent.ExecutionException e) {
            return List.of(String.valueOf(e.getCause()));
        }
        StringBuilder handshake = new StringBuilder("101 " + ws.subProtocol());
        ws.headers().forEach(h -> {
            String name = h.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!NOT_THE_APPS.contains(name) && !name.equals("sec-websocket-accept")) {
                handshake.append(" | ").append(name).append(": ").append(h.getValue());
            }
        });
        out.add(handshake.toString());
        java.util.concurrent.CompletableFuture<String> closed = new java.util.concurrent.CompletableFuture<>();
        ws.closeHandler(v -> closed.complete("closed " + ws.closeStatusCode() + " " + ws.closeReason()));
        java.util.function.Supplier<String> next = () -> {
            try {
                String item = received.poll(10, TimeUnit.SECONDS);
                return item == null ? "nothing" : item;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        out.add(next.get());
        await(ws.writeTextMessage("hello"));
        out.add(next.get());
        await(ws.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(new byte[] {1, 2, 3, (byte) 255})));
        out.add(next.get());
        await(ws.writeTextMessage("y".repeat(300_000)));
        out.add(next.get());
        await(ws.writePing(io.vertx.core.buffer.Buffer.buffer("pp")));
        out.add(next.get());
        await(ws.writeTextMessage("ping"));
        await(ws.writeTextMessage("log"));
        out.add(next.get());
        await(ws.writeTextMessage("close"));
        out.add(closed.get(10, TimeUnit.SECONDS));

        // A second connection closed from this side, then the server's log of both.
        java.util.concurrent.LinkedBlockingQueue<String> secondReceived = new java.util.concurrent.LinkedBlockingQueue<>();
        io.vertx.core.http.WebSocket second = open(client, new io.vertx.core.http.WebSocketConnectOptions(options).setURI("/second"), secondReceived);
        out.add(secondReceived.poll(10, TimeUnit.SECONDS));
        await(second.close((short) 1000, "client done"));
        java.util.concurrent.LinkedBlockingQueue<String> thirdReceived = new java.util.concurrent.LinkedBlockingQueue<>();
        io.vertx.core.http.WebSocket third = open(client, new io.vertx.core.http.WebSocketConnectOptions(options).setURI("/third"), thirdReceived);
        out.add(thirdReceived.poll(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        await(third.writeTextMessage("log"));
        out.add(thirdReceived.poll(10, TimeUnit.SECONDS));
        await(third.close());

        // Refused by the app's verifyClient: its status and reason come back as they are.
        try {
            await(client.connect(new io.vertx.core.http.WebSocketConnectOptions(options).setURI("/refuse")));
            out.add("refuse: connected");
        } catch (java.util.concurrent.ExecutionException e) {
            out.add("refuse: " + (e.getCause() instanceof io.vertx.core.http.UpgradeRejectedException rejected
                    ? rejected.getStatus() + " " + rejected.getBody() : String.valueOf(e.getCause())));
        }
        return out;
    }

    /** Hop-by-hop headers and Date: the proxy's and the clock's, not the app's. */
    private static final java.util.Set<String> NOT_THE_APPS = java.util.Set.of("date", "connection", "keep-alive", "transfer-encoding", "server");

    /** The same requests, each answered as "status, headers in order, body". */
    private List<String> exchanges(io.vertx.core.http.HttpClient http, RequestOptions base, boolean throughIngress) throws Exception {
        byte[] upload = new byte[2 * 1024 * 1024];
        for (int i = 0; i < upload.length; i++) {
            upload[i] = (byte) (i * 31 % 251);
        }
        List<String> out = new java.util.ArrayList<>();
        // The service may still be starting its server.
        String first = ask(http, base, HttpMethod.GET, "/hello", null, null);
        long deadline = System.currentTimeMillis() + 30_000;
        while (throughIngress && first.contains(" -> 503 ") && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            first = ask(http, base, HttpMethod.GET, "/hello", null, null);
        }
        out.add(first);
        out.add(ask(http, base, HttpMethod.HEAD, "/hello", null, null));
        out.add(ask(http, base, HttpMethod.GET, "/json", null, null));
        out.add(ask(http, base, HttpMethod.GET, "/users/42?expand=yes", null, null));
        out.add(ask(http, base, HttpMethod.POST, "/echo", "application/json", io.vertx.core.buffer.Buffer.buffer("{\"a\":[1,2],\"b\":\"x\"}")));
        out.add(ask(http, base, HttpMethod.POST, "/form", "application/x-www-form-urlencoded", io.vertx.core.buffer.Buffer.buffer("a=1&b=two+words")));
        out.add(ask(http, base, HttpMethod.GET, "/cookies", null, null));
        out.add(ask(http, base, HttpMethod.GET, "/redirect", null, null));
        out.add(ask(http, base, HttpMethod.GET, "/error", null, null));
        out.add(ask(http, base, HttpMethod.GET, "/missing", null, null));
        out.add(ask(http, base, HttpMethod.GET, "/stream", null, null));
        out.add(ask(http, base, HttpMethod.POST, "/upload", "application/octet-stream", io.vertx.core.buffer.Buffer.buffer(upload)));
        // A client that leaves mid-response: the app hears its response close unfinished.
        java.util.concurrent.CompletableFuture<String> firstChunk = new java.util.concurrent.CompletableFuture<>();
        // The handler is set as the response arrives, before its first chunk can.
        io.vertx.core.http.HttpClientResponse slow = await(http.request(new RequestOptions(base).setMethod(HttpMethod.GET).setURI("/slow"))
                .compose(io.vertx.core.http.HttpClientRequest::send)
                .map(response -> response.handler(chunk -> firstChunk.complete(chunk.toString()))));
        out.add("slow began with " + firstChunk.get(10, TimeUnit.SECONDS).trim());
        await(slow.request().connection().close());
        String events = ask(http, base, HttpMethod.GET, "/events", null, null);
        long until = System.currentTimeMillis() + 10_000;
        while (events.endsWith("[]") && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            events = ask(http, base, HttpMethod.GET, "/events", null, null);
        }
        out.add(events.substring(events.lastIndexOf('\n') + 1));
        return out;
    }

    private String ask(io.vertx.core.http.HttpClient http, RequestOptions base, HttpMethod method, String uri, String type,
                       io.vertx.core.buffer.Buffer body) throws Exception {
        RequestOptions options = new RequestOptions(base).setMethod(method).setURI(uri);
        if (type != null) {
            options.putHeader("Content-Type", type);
        }
        return await(http.request(options).compose(request -> (body == null ? request.send() : request.send(body))
                .compose(response -> response.body().map(bytes -> {
                    StringBuilder text = new StringBuilder(method + " " + uri + " -> " + response.statusCode() + " " + response.statusMessage());
                    response.headers().forEach(h -> {
                        if (!NOT_THE_APPS.contains(h.getKey().toLowerCase(java.util.Locale.ROOT))) {
                            text.append('\n').append(h.getKey().toLowerCase(java.util.Locale.ROOT)).append(": ").append(h.getValue());
                        }
                    });
                    return text.append('\n').append(bytes.toString()).toString();
                }))));
    }

    private static void awaitListening(int port, Process app, String lib) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            assertThat(app.isAlive()).as(lib + " under node exited").isTrue();
            try (java.net.Socket probe = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError(lib + " under node never listened on " + port);
    }

    /** The library's fixture gives the same JSON in the isolate as under real Node. */
    private void sameAsNode(String lib) throws Exception {
        JsonNode expected = Json.parse(underNode(lib));
        Path script = project.resolve("job-" + lib + ".mjs");
        String pkg = MONGO.contains(lib) ? "realworld-mongo" : DRIVERS.contains(lib) ? "realworld-db" : NETWORK.contains(lib) ? "realworld-net" : "realworld";
        Files.writeString(script, "import { run } from '" + pkg + "/" + lib + "'; export default async () => run();\n");
        List<String> args = new java.util.ArrayList<>(List.of("run"));
        testEnv.forEach((name, value) -> args.addAll(List.of("--env", name + "=" + value)));
        args.add(script.toString());
        Result ran = cli(args.toArray(String[]::new));
        assertThat(ran.code()).as(lib + ": " + ran.err()).isZero();
        assertThat(Json.parse(ran.out())).as(lib).isEqualTo(expected);
    }

    @Test
    void anNpmLibraryIsntAPackageUntilItsBundledIntoOne() throws Exception {
        Path script = project.resolve("direct.mjs");
        Files.writeString(script, "import { marked } from 'marked'; export default () => 1;\n");
        // The script imports an npm library by name, which isn't a package: refused when it's sent, with the reason.
        Result refused = cli("run", script.toString());
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.err()).contains("No package named \"marked\"");
    }

    @ParameterizedTest(name = "{0} is refused at publish: it needs {1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "bcrypt, bcrypt needs native code",
            "sharp, sharp needs native code"})
    void librariesNeedingWhatTheIsolateLacksAreRefusedWithTheReason(String lib, String reason) throws Exception {
        Path variant = variant(lib);
        Result refused = cli("packages", "publish", variant.toString(), "--dry-run");
        assertThat(refused.code()).as(refused.out()).isEqualTo(1);
        assertThat(refused.err()).contains(reason);
        if (reason.contains("native")) {
            assertThat(refused.err()).as("points at the Node runtime").contains("\"runtime\": \"node\"");
        }
    }

    // --- helpers ---

    /**
     * The drivers' servers (D-116), in containers: Postgres with TLS on (a self-signed certificate for localhost, whose
     * CA is TEST_PG_CA), MySQL, Redis, MongoDB, and Mailpit for SMTP with STARTTLS. Their addresses go to Node and the
     * isolate alike.
     */
    /**
     * The dnsclient fixture's zone (D-122): every record type, names compressed as real servers write them, and names
     * that fail each way (see TestDnsServer). TEST_DNS is its address, as setServers takes it.
     */
    /**
     * The WebSocket clients' server (D-125): TEST_WS. It first sends the handshake's request headers back (but the random
     * key), then echoes; "ping-me" makes it ping, "close-me" makes it close with 4001, and /refuse is refused with a 403.
     */
    private void startWebSocketServer() throws Exception {
        io.vertx.core.http.HttpServer ws = await(serverVertx.createHttpServer(new io.vertx.core.http.HttpServerOptions()
                        .setWebSocketSubProtocols(List.of("chat")).setPerMessageWebSocketCompressionSupported(true)
                        .setMaxWebSocketFrameSize(1024 * 1024).setMaxWebSocketMessageSize(4 * 1024 * 1024))
                .webSocketHandshakeHandler(handshake -> {
                    if (handshake.path().equals("/refuse")) {
                        handshake.reject(403);
                    } else {
                        handshake.accept();
                    }
                })
                .webSocketHandler(socket -> {
                    io.vertx.core.json.JsonArray headers = new io.vertx.core.json.JsonArray();
                    socket.headers().forEach(h -> {
                        if (!h.getKey().equalsIgnoreCase("sec-websocket-key")) {
                            headers.add(new io.vertx.core.json.JsonArray().add(h.getKey()).add(h.getValue()));
                        }
                    });
                    socket.writeTextMessage(new io.vertx.core.json.JsonObject().put("uri", socket.uri()).put("headers", headers).encode());
                    socket.textMessageHandler(text -> {
                        if (text.equals("ping-me")) {
                            socket.writePing(io.vertx.core.buffer.Buffer.buffer("server ping"));
                            socket.writeTextMessage("pinged");
                        } else if (text.equals("close-me")) {
                            socket.close((short) 4001, "bye");
                        } else {
                            socket.writeTextMessage(text);
                        }
                    });
                    socket.binaryMessageHandler(socket::writeBinaryMessage);
                })
                .listen(0, "127.0.0.1"));
        testEnv.put("TEST_WS", "ws://127.0.0.1:" + ws.actualPort());
    }

    private void startDnsServer() throws Exception {
        dnsServer = new automately.core.host.dns.TestDnsServer(serverVertx).start()
                .a("example.test", "93.184.216.34").a("example.test", "93.184.216.35").ttl("example.test", 120)
                .aaaa("example.test", "2606:2800:220:1::1")
                .mx("example.test", 10, "mail.example.test").mx("example.test", 20, "backup.other.test")
                .txt("example.test", "v=spf1 -all", "second").txt("example.test", "h\u00e9llo")
                .ns("example.test", "ns1.example.test").ns("example.test", "ns2.example.test")
                .soa("example.test", "ns1.example.test", "hostmaster.example.test", 2026100901, 7200, 900, 1209600, 300)
                .caa("example.test", 0, "issue", "letsencrypt.org").caa("example.test", 128, "iodef", "mailto:security@example.test")
                .naptr("example.test", 100, 10, "S", "SIP+D2U", "", "_sip._udp.example.test")
                .srv("_sip._tcp.example.test", 10, 60, 5060, "sip1.example.test").srv("_sip._tcp.example.test", 20, 0, 5060, "sip2.example.test")
                .tlsa("_443._tcp.example.test", 3, 1, 1, new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef})
                .cname("www.example.test", "example.test")
                .ptr("34.216.184.93.in-addr.arpa", "example.test")
                .txt("big.example.test", "x".repeat(250), "y".repeat(250), "z".repeat(250));
        testEnv.put("TEST_DNS", dnsServer.address());
    }

    private void startServices() throws Exception {
        io.vertx.core.net.SelfSignedCertificate pgCert = io.vertx.core.net.SelfSignedCertificate.create("localhost");
        testEnv.put("TEST_PG_CA", Files.readString(Path.of(pgCert.certificatePath())));
        var postgres = new org.testcontainers.containers.GenericContainer<>("postgres:18-alpine")
                .withEnv("POSTGRES_USER", "app").withEnv("POSTGRES_PASSWORD", "secret").withEnv("POSTGRES_DB", "app")
                .withCopyToContainer(org.testcontainers.utility.MountableFile.forHostPath(pgCert.certificatePath()), "/tmp/server.crt")
                .withCopyToContainer(org.testcontainers.utility.MountableFile.forHostPath(pgCert.privateKeyPath()), "/tmp/server.key")
                // Postgres reads its key only if it owns it and nobody else can read it.
                .withCommand("sh", "-c", "cp /tmp/server.crt /tmp/server.key /var/lib/postgresql/ && chown postgres /var/lib/postgresql/server.* "
                        + "&& chmod 600 /var/lib/postgresql/server.key && exec docker-entrypoint.sh postgres -c ssl=on "
                        + "-c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key")
                .withExposedPorts(5432)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*ready to accept connections.*", 2));
        var mysql = new org.testcontainers.containers.GenericContainer<>("mysql:8.4")
                .withEnv("MYSQL_ROOT_PASSWORD", "secret").withEnv("MYSQL_DATABASE", "app").withEnv("MYSQL_USER", "app")
                .withEnv("MYSQL_PASSWORD", "secret").withExposedPorts(3306)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                        .withStartupTimeout(java.time.Duration.ofMinutes(3)));
        var redis = new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        var mongo = new org.testcontainers.containers.GenericContainer<>("mongo:8.2").withExposedPorts(27017)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Waiting for connections.*", 1));
        var mailpit = new org.testcontainers.containers.GenericContainer<>("axllent/mailpit:latest")
                .withEnv("MP_SMTP_TLS_CERT", "sans:localhost").withEnv("MP_SMTP_TLS_KEY", "sans:localhost")
                .withExposedPorts(1025, 8025);
        java.util.stream.Stream.of(postgres, mysql, redis, mongo, mailpit).parallel().forEach(c -> {
            c.start();
            containers.add(c);
        });
        testEnv.put("TEST_PG", "postgres://app:secret@127.0.0.1:" + postgres.getMappedPort(5432) + "/app");
        testEnv.put("TEST_MYSQL", "mysql://app:secret@127.0.0.1:" + mysql.getMappedPort(3306) + "/app");
        testEnv.put("TEST_REDIS", "redis://127.0.0.1:" + redis.getMappedPort(6379));
        testEnv.put("TEST_MONGO", "mongodb://127.0.0.1:" + mongo.getMappedPort(27017));
        // mongodb+srv (D-122): the SRV record names a host under *.localhost, which the system resolves to loopback.
        dnsServer.srv("_mongodb._tcp.cluster.localhost", 0, 5, mongo.getMappedPort(27017), "mongo1.cluster.localhost")
                .txt("cluster.localhost", "authSource=admin");
        testEnv.put("TEST_MONGO_SRV", "mongodb+srv://cluster.localhost/?tls=false");
        testEnv.put("TEST_SMTP", String.valueOf(mailpit.getMappedPort(1025)));
    }

    /**
     * The sockets fixture's servers (D-116), the same ones for Node and the isolate: an echo server, a TLS echo server
     * and one that switches to TLS when asked (both with a self-signed certificate for localhost, whose CA is TEST_CA),
     * and a server that answers only once the client has finished sending.
     */
    private void startSocketServers() throws Exception {
        io.vertx.core.net.SelfSignedCertificate certificate = io.vertx.core.net.SelfSignedCertificate.create("localhost");
        testEnv.put("TEST_CA", Files.readString(Path.of(certificate.certificatePath())));
        io.vertx.core.net.NetServer echo = await(serverVertx.createNetServer().connectHandler(socket -> socket.handler(socket::write))
                .listen(0, "127.0.0.1"));
        testEnv.put("TEST_TCP", String.valueOf(echo.actualPort()));
        io.vertx.core.net.NetServer tls = await(serverVertx.createNetServer(new io.vertx.core.net.NetServerOptions().setSsl(true)
                        .setKeyCertOptions(certificate.keyCertOptions()))
                .connectHandler(socket -> socket.handler(data -> socket.write(io.vertx.core.buffer.Buffer.buffer("tls:").appendBuffer(data))))
                .listen(0, "127.0.0.1"));
        testEnv.put("TEST_TLS", String.valueOf(tls.actualPort()));
        io.vertx.core.net.NetServer starttls = await(serverVertx.createNetServer(new io.vertx.core.net.NetServerOptions()
                        .setKeyCertOptions(certificate.keyCertOptions()))
                .connectHandler(socket -> socket.handler(data -> {
                    if (data.toString().equals("STARTTLS")) {
                        // As a real server does: stop reading, answer, switch, then read again (as TLS).
                        socket.pause();
                        socket.write("OK").compose(v -> socket.upgradeToSsl()).onComplete(v -> socket.resume());
                    } else {
                        socket.write(io.vertx.core.buffer.Buffer.buffer(socket.isSsl() ? "secure:" : "plain:").appendBuffer(data));
                    }
                })).listen(0, "127.0.0.1"));
        testEnv.put("TEST_STARTTLS", String.valueOf(starttls.actualPort()));
        // HTTP/2 (D-117): over TLS with ALPN, with the same certificate, and in clear text (h2c).
        io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> h2 = this::serveHttp2;
        io.vertx.core.http.HttpServer h2tls = await(serverVertx.createHttpServer(new io.vertx.core.http.HttpServerOptions().setSsl(true).setUseAlpn(true)
                .setKeyCertOptions(certificate.keyCertOptions())).requestHandler(h2).listen(0, "127.0.0.1"));
        testEnv.put("TEST_H2", "https://localhost:" + h2tls.actualPort());
        io.vertx.core.http.HttpServer h2c = await(serverVertx.createHttpServer(new io.vertx.core.http.HttpServerOptions().setHttp2ClearTextEnabled(true))
                .requestHandler(h2).listen(0, "127.0.0.1"));
        testEnv.put("TEST_H2C", "http://127.0.0.1:" + h2c.actualPort());
        io.vertx.core.net.NetServer paced = await(serverVertx.createNetServer().connectHandler(PacedHttp2::serve).listen(0, "127.0.0.1"));
        testEnv.put("TEST_H2_PACED", "http://127.0.0.1:" + paced.actualPort());
        halfClose = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        testEnv.put("TEST_HALFCLOSE", String.valueOf(halfClose.getLocalPort()));
        Thread acceptor = new Thread(() -> {
            while (!halfClose.isClosed()) {
                try (java.net.Socket peer = halfClose.accept()) {
                    byte[] got = peer.getInputStream().readAllBytes();
                    peer.getOutputStream().write(("got " + new String(got, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // Closed at the end, or a client that left: the next one is served.
                }
            }
        }, "real-npm-half-close");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** The HTTP/2 servers' answers (D-117), fixed so Node and the isolate can be compared. */
    private void serveHttp2(io.vertx.core.http.HttpServerRequest req) {
        io.vertx.core.http.HttpServerResponse res = req.response();
        java.util.Map<Integer, io.vertx.core.json.JsonObject> trailers = RequestTrailers.of(req.connection());
        if (req.path().equals("/now")) {
            // Answers without waiting for the request's body, which stays open.
            res.end("x".repeat(100));
            return;
        }
        req.body().onSuccess(body -> {
            switch (req.path()) {
                case "/request-trailers" -> res.putHeader("content-type", "application/json").end(new io.vertx.core.json.JsonObject()
                        .put("body", body.toString()).put("trailers", trailers.remove((int) req.streamId())).encode());
                case "/json" -> res.putHeader("content-type", "application/json").putHeader("x-version", req.version().name())
                        .putHeader("set-cookie", (Iterable<String>) List.of("a=1", "b=2")).putHeader("x-repeat", (Iterable<String>) List.of("one", "two"))
                        .end("{\"hello\":\"http2\"}");
                case "/echo" -> res.putHeader("content-type", "application/json").end(new io.vertx.core.json.JsonObject()
                        .put("method", req.method().name()).put("path", req.uri()).put("authority", req.authority() == null ? null : req.authority().toString())
                        .put("custom", req.getHeader("x-custom")).put("body", body.toString()).put("length", body.length()).encode());
                case "/trailers" -> {
                    res.setChunked(true).putHeader("content-type", "text/plain").putTrailer("x-checksum", "abc").putTrailer("x-count", "2");
                    res.write("first ");
                    res.end("second");
                }
                case "/reset" -> res.reset(7);
                case "/cancel" -> res.reset(8);
                case "/empty" -> res.setStatusCode(204).end();
                case "/big" -> res.end(io.vertx.core.buffer.Buffer.buffer(new byte[300_000]));
                case "/slow" -> serverVertx.setTimer(1_500, id -> {
                    if (!res.closed()) {
                        res.end("late");
                    }
                });
                default -> res.setStatusCode(404).end("not found");
            }
        });
    }

    /**
     * The trailers each request stream ended with, which Vert.x's server doesn't show: its Netty decoder is wrapped once
     * per connection to keep them, as Http2ClientTest's capture does (D-118).
     */
    static final class RequestTrailers extends io.netty.handler.codec.http2.Http2FrameListenerDecorator {
        private final java.util.Map<Integer, io.vertx.core.json.JsonObject> received = new java.util.concurrent.ConcurrentHashMap<>();

        private RequestTrailers(io.netty.handler.codec.http2.Http2FrameListener listener) {
            super(listener);
        }

        static java.util.Map<Integer, io.vertx.core.json.JsonObject> of(io.vertx.core.http.HttpConnection connection) {
            if (!(connection instanceof io.vertx.core.net.impl.ConnectionBase base)) {
                return new java.util.HashMap<>();
            }
            var handler = base.channelHandlerContext().pipeline().get(io.netty.handler.codec.http2.Http2ConnectionHandler.class);
            if (handler == null) {
                return new java.util.HashMap<>();
            }
            if (handler.decoder().frameListener() instanceof RequestTrailers capture) {
                return capture.received;
            }
            RequestTrailers capture = new RequestTrailers(handler.decoder().frameListener());
            handler.decoder().frameListener(capture);
            return capture.received;
        }

        private void keep(int streamId, io.netty.handler.codec.http2.Http2Headers headers, boolean endOfStream) {
            if (headers.method() == null && endOfStream) {
                io.vertx.core.json.JsonObject names = new io.vertx.core.json.JsonObject();
                headers.forEach(h -> names.put(h.getKey().toString(), names.containsKey(h.getKey().toString())
                        ? names.getString(h.getKey().toString()) + ", " + h.getValue() : h.getValue().toString()));
                received.put(streamId, names);
            }
        }

        @Override
        public void onHeadersRead(io.netty.channel.ChannelHandlerContext ctx, int streamId, io.netty.handler.codec.http2.Http2Headers headers,
                                  int padding, boolean endOfStream) throws io.netty.handler.codec.http2.Http2Exception {
            keep(streamId, headers, endOfStream);
            super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
        }

        @Override
        public void onHeadersRead(io.netty.channel.ChannelHandlerContext ctx, int streamId, io.netty.handler.codec.http2.Http2Headers headers,
                                  int dependency, short weight, boolean exclusive, int padding, boolean endOfStream)
                throws io.netty.handler.codec.http2.Http2Exception {
            keep(streamId, headers, endOfStream);
            super.onHeadersRead(ctx, streamId, headers, dependency, weight, exclusive, padding, endOfStream);
        }
    }

    /**
     * An h2c server at the frame level (D-121), for comparing flow control exactly. It answers every request with the
     * same DATA frames (18 of 16384 bytes, then 5000), one at a time, each followed by a PING whose ACK says the client
     * has read it. So each frame arrives in its own read and the frames are cut the same way for every client, which a
     * real server leaves to timing. It keeps to the client's windows.
     */
    static final class PacedHttp2 {
        static final int[] FRAMES = java.util.stream.IntStream.concat(java.util.stream.IntStream.generate(() -> 16384).limit(18),
                java.util.stream.IntStream.of(5000)).toArray();

        private final io.vertx.core.net.NetSocket socket;
        private io.vertx.core.buffer.Buffer pending = io.vertx.core.buffer.Buffer.buffer();
        private boolean preface;
        private long connectionWindow = 65535;
        private long initialWindow = 65535;
        private final java.util.Map<Integer, Long> streamWindows = new java.util.HashMap<>();
        private final java.util.List<java.util.function.BooleanSupplier> waiting = new java.util.ArrayList<>();
        private final java.util.Map<Integer, io.vertx.core.Promise<Void>> pings = new java.util.HashMap<>();
        private int pingCount;

        private PacedHttp2(io.vertx.core.net.NetSocket socket) {
            this.socket = socket;
        }

        static void serve(io.vertx.core.net.NetSocket socket) {
            PacedHttp2 connection = new PacedHttp2(socket);
            socket.handler(connection::read);
        }

        private void frame(int type, int flags, int stream, io.vertx.core.buffer.Buffer payload) {
            io.vertx.core.buffer.Buffer header = io.vertx.core.buffer.Buffer.buffer(9 + payload.length())
                    .appendUnsignedByte((short) (payload.length() >>> 16)).appendUnsignedShort(payload.length() & 0xffff)
                    .appendUnsignedByte((short) type).appendUnsignedByte((short) flags).appendInt(stream);
            socket.write(header.appendBuffer(payload));
        }

        private io.vertx.core.Future<Void> ping() {
            io.vertx.core.Promise<Void> acked = io.vertx.core.Promise.promise();
            pings.put(++pingCount, acked);
            frame(6, 0, 0, io.vertx.core.buffer.Buffer.buffer().appendInt(0).appendInt(pingCount));
            return acked.future();
        }

        private io.vertx.core.Future<Void> windowFor(int stream, int size) {
            io.vertx.core.Promise<Void> open = io.vertx.core.Promise.promise();
            java.util.function.BooleanSupplier check = () -> {
                if (connectionWindow >= size && streamWindows.get(stream) >= size) {
                    open.complete();
                    return true;
                }
                return false;
            };
            if (!check.getAsBoolean()) {
                waiting.add(check);
            }
            return open.future();
        }

        private void answer(int stream) {
            streamWindows.put(stream, initialWindow);
            frame(1, 4, stream, io.vertx.core.buffer.Buffer.buffer(new byte[] {(byte) 0x88})); // :status 200, from HPACK's static table
            io.vertx.core.Future<Void> sent = ping();
            for (int i = 0; i < FRAMES.length; i++) {
                int size = FRAMES[i];
                boolean last = i == FRAMES.length - 1;
                sent = sent.compose(v -> windowFor(stream, size)).compose(v -> {
                    connectionWindow -= size;
                    streamWindows.merge(stream, (long) -size, Long::sum);
                    byte[] data = new byte[size];
                    java.util.Arrays.fill(data, (byte) 'x');
                    frame(0, last ? 1 : 0, stream, io.vertx.core.buffer.Buffer.buffer(data));
                    return ping();
                });
            }
        }

        private void read(io.vertx.core.buffer.Buffer data) {
            pending = pending.copy().appendBuffer(data);
            if (!preface) {
                if (pending.length() < 24) {
                    return;
                }
                pending = pending.getBuffer(24, pending.length());
                preface = true;
                frame(4, 0, 0, io.vertx.core.buffer.Buffer.buffer());
            }
            while (pending.length() >= 9) {
                int length = pending.getUnsignedMedium(0);
                if (pending.length() < 9 + length) {
                    return;
                }
                int type = pending.getUnsignedByte(3);
                int flags = pending.getUnsignedByte(4);
                int stream = pending.getInt(5) & 0x7fffffff;
                io.vertx.core.buffer.Buffer payload = pending.getBuffer(9, 9 + length);
                pending = pending.getBuffer(9 + length, pending.length());
                switch (type) {
                    case 4 -> {
                        if ((flags & 1) == 0) {
                            for (int i = 0; i + 6 <= payload.length(); i += 6) {
                                if (payload.getUnsignedShort(i) == 4) {
                                    long value = payload.getUnsignedInt(i + 2);
                                    streamWindows.replaceAll((id, window) -> window + value - initialWindow);
                                    initialWindow = value;
                                }
                            }
                            frame(4, 1, 0, io.vertx.core.buffer.Buffer.buffer());
                        }
                    }
                    case 1 -> answer(stream);
                    case 8 -> {
                        long increment = payload.getUnsignedInt(0) & 0x7fffffffL;
                        if (stream == 0) {
                            connectionWindow += increment;
                        } else {
                            streamWindows.merge(stream, increment, Long::sum);
                        }
                        waiting.removeIf(java.util.function.BooleanSupplier::getAsBoolean);
                    }
                    case 6 -> {
                        if ((flags & 1) != 0) {
                            io.vertx.core.Promise<Void> acked = pings.remove(payload.getInt(4));
                            if (acked != null) {
                                acked.complete();
                            }
                        } else {
                            frame(6, 1, 0, payload);
                        }
                    }
                    default -> {
                        // Anything else (RST_STREAM, GOAWAY, PRIORITY) needs nothing from this server.
                    }
                }
            }
        }
    }

    /** The HTTP libraries' server: fixed answers, so Node and the isolate can be compared. */
    private void serve(io.vertx.core.http.HttpServerRequest req) {
        io.vertx.core.http.HttpServerResponse res = req.response();
        String path = req.path();
        if (path.equals("/json")) {
            res.headers().add("content-type", "application/json").add("set-cookie", "a=1; Path=/").add("set-cookie", "b=2")
                    .add("x-repeat", "one").add("x-repeat", "two");
            res.end("{\"hello\":\"world\"}");
        } else if (path.equals("/echo")) {
            req.body().onSuccess(body -> {
                io.vertx.core.json.JsonObject echo = new io.vertx.core.json.JsonObject()
                        .put("method", req.method().name())
                        .put("url", req.uri())
                        .put("contentType", req.getHeader("content-type"))
                        .put("contentLength", req.getHeader("content-length"))
                        .put("transferEncoding", req.getHeader("transfer-encoding"))
                        .put("custom", req.getHeader("x-custom"))
                        .put("authorization", req.getHeader("authorization"))
                        .put("bodyHex", java.util.HexFormat.of().formatHex(body.getBytes(0, Math.min(64, body.length()))))
                        .put("bodyText", body.toString(StandardCharsets.UTF_8))
                        .put("bodyLength", body.length());
                res.putHeader("content-type", "application/json").end(echo.encode());
            });
        } else if (path.startsWith("/status/")) {
            int status = Integer.parseInt(path.substring("/status/".length()));
            res.setStatusCode(status).putHeader("content-type", "text/plain").end("status " + status);
        } else if (path.equals("/paged")) {
            // Three pages linked by Link headers, for got's pagination.
            int page = Integer.parseInt(java.util.Optional.ofNullable(req.getParam("page")).orElse("1"));
            if (page < 3) {
                res.putHeader("link", "<" + "/paged?page=" + (page + 1) + ">; rel=\"next\"");
            }
            res.putHeader("content-type", "application/json").end("[" + (page * 10 + 1) + "," + (page * 10 + 2) + "]");
        } else if (path.equals("/redirect")) {
            res.setStatusCode(302).putHeader("location", "/json").end();
        } else if (path.equals("/big")) {
            byte[] big = new byte[1_000_000];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) ('a' + i % 26);
            }
            res.setChunked(true);
            for (int i = 0; i < big.length; i += 65_536) {
                res.write(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOfRange(big, i, Math.min(big.length, i + 65_536))));
            }
            res.end();
        } else if (path.equals("/slow")) {
            serverVertx.setTimer(1_500, id -> {
                if (!res.closed()) {
                    res.end("late");
                }
            });
        } else if (path.equals("/gzip")) {
            try {
                java.io.ByteArrayOutputStream zipped = new java.io.ByteArrayOutputStream();
                try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(zipped)) {
                    gzip.write("{\"zipped\":true}".getBytes(StandardCharsets.UTF_8));
                }
                res.putHeader("content-type", "application/json").putHeader("content-encoding", "gzip")
                        .end(io.vertx.core.buffer.Buffer.buffer(zipped.toByteArray()));
            } catch (IOException e) {
                res.setStatusCode(500).end();
            }
        } else {
            res.setStatusCode(404).end();
        }
    }

    /** A project of its own for one library, sharing the installed node_modules: publish reads automately.json from its folder. */
    private Path variant(String lib) throws IOException {
        Path dir = project.resolve("variant-" + lib);
        Files.createDirectories(dir.resolve("src"));
        Files.copy(project.resolve("src/" + lib + ".js"), dir.resolve("src/" + lib + ".js"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path modules = dir.resolve("node_modules");
        if (!Files.exists(modules)) {
            Files.createSymbolicLink(modules, project.resolve("node_modules"));
        }
        Files.writeString(dir.resolve("automately.json"), "{\"name\": \"realworld-" + lib + "\", \"version\": \"1.0.0\", \"entry\": \"src/" + lib + ".js\"}");
        return dir;
    }

    /** What the library's module returns under real Node, as JSON. */
    private String underNode(String lib) throws IOException, InterruptedException {
        ProcessBuilder node = new ProcessBuilder("node", "--no-warnings", "--input-type=module", "-e",
                "const m = await import('./src/" + lib + ".js'); console.log(JSON.stringify(await m.run()));")
                .directory(project.toFile()).redirectErrorStream(true);
        node.environment().putAll(testEnv);
        Process started = node.start();
        // A fixture that never lets Node exit fails here instead of hanging the suite.
        java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        timer.schedule(started::destroyForcibly, 2, TimeUnit.MINUTES);
        String out;
        try {
            out = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } finally {
            timer.shutdownNow();
        }
        assertThat(started.waitFor()).as(lib + " under node: " + out).isZero();
        return out;
    }

    private Result cli(String... args) {
        return cli(Map.of(), "", args);
    }

    private Result cli(Map<String, String> env, String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io io = new Io(env, config, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(io, args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }
}
