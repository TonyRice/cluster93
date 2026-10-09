package automately.cli;

import automately.server.Automately;
import automately.server.Automately.RunningNode;
import automately.server.ServerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
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
            "rxjs", "mustache", "events", "validator", "uuid", "nanoid", "nodecrypto", "papaparse", "csvparse", "jwt", "nodecryptokeys", "nodemodules", "webcrypto", "globals");
    /** The HTTP libraries (D-109), published as a second package: one would be over a version's 10 MB. */
    private static final List<String> NETWORK = List.of("httpclient", "axios", "nodefetch", "got", "sockets");
    /**
     * Database and mail drivers (D-116), run against real servers in containers: two more packages, since together
     * they'd be over a version's 10 MB.
     */
    private static final List<String> DRIVERS = List.of("pg", "mysql2", "ioredis", "nodemailer");
    private static final List<String> MONGO = List.of("mongodb");
    /** Set to run the drivers: they need Docker for their servers. */
    private static final boolean SERVICES = System.getenv("AUTOMATELY_TEST_REAL_SERVICES") != null;
    private final List<org.testcontainers.containers.GenericContainer<?>> containers = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private RunningNode node;
    /** The HTTP libraries' server (D-109), the same one for Node and the isolate: TEST_HTTP. */
    private io.vertx.core.Vertx serverVertx;
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
        if (serverVertx != null) {
            await(serverVertx.close());
        }
        if (halfClose != null) {
            halfClose.close();
        }
        containers.forEach(org.testcontainers.containers.GenericContainer::stop);
    }

    @ParameterizedTest(name = "{0} gives the same answer as under Node")
    @ValueSource(strings = {"lodash", "datefns", "dayjs", "zod", "marked", "jsyaml", "yaml", "ajv", "semver", "immer", "rxjs", "mustache", "events", "validator", "uuid", "nanoid", "nodecrypto", "papaparse", "csvparse", "jwt", "nodecryptokeys", "nodemodules", "webcrypto", "globals", "httpclient", "axios", "nodefetch", "got", "sockets"})
    void aLibraryRunsInTheIsolateAsItDoesInNode(String lib) throws Exception {
        sameAsNode(lib);
    }

    @ParameterizedTest(name = "{0} talks to a real server as it does under Node")
    @ValueSource(strings = {"pg", "mysql2", "ioredis", "mongodb", "nodemailer"})
    void aDriverTalksToItsServerAsItDoesUnderNode(String lib) throws Exception {
        Assumptions.assumeTrue(SERVICES, "Set AUTOMATELY_TEST_REAL_SERVICES (and have Docker) to run the drivers against real servers");
        sameAsNode(lib);
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
        ProcessBuilder node = new ProcessBuilder("node", "--input-type=module", "-e",
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
