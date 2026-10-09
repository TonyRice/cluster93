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
    private static final List<String> NETWORK = List.of("httpclient", "axios", "nodefetch", "got");

    private RunningNode node;
    /** The HTTP libraries' server (D-109), the same one for Node and the isolate: TEST_HTTP. */
    private io.vertx.core.Vertx serverVertx;
    private String testHttp;
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
    }

    @ParameterizedTest(name = "{0} gives the same answer as under Node")
    @ValueSource(strings = {"lodash", "datefns", "dayjs", "zod", "marked", "jsyaml", "yaml", "ajv", "semver", "immer", "rxjs", "mustache", "events", "validator", "uuid", "nanoid", "nodecrypto", "papaparse", "csvparse", "jwt", "nodecryptokeys", "nodemodules", "webcrypto", "globals", "httpclient", "axios", "nodefetch", "got"})
    void aLibraryRunsInTheIsolateAsItDoesInNode(String lib) throws Exception {
        JsonNode expected = Json.parse(underNode(lib));
        Path script = project.resolve("job-" + lib + ".mjs");
        String pkg = NETWORK.contains(lib) ? "realworld-net" : "realworld";
        Files.writeString(script, "import { run } from '" + pkg + "/" + lib + "'; export default async () => run();\n");
        Result ran = cli("run", "--env", "TEST_HTTP=" + testHttp, script.toString());
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
        node.environment().put("TEST_HTTP", testHttp);
        Process started = node.start();
        String out = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
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
