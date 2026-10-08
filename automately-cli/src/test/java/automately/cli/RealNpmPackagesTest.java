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
 * module prints under real Node. Libraries that need what the isolate doesn't have (Node's crypto, stream or process,
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
            "rxjs", "mustache", "events", "validator", "uuid", "nanoid", "nodecrypto");

    private RunningNode node;
    private Path project;
    private Path config;

    record Result(int code, String out, String err) {
    }

    @BeforeAll
    void setUp() throws Exception {
        String work = System.getenv("AUTOMATELY_TEST_REAL_NPM");
        Assumptions.assumeTrue(work != null && !work.isBlank(), "Set AUTOMATELY_TEST_REAL_NPM to a work folder to run real npm packages");
        project = Path.of(work).toAbsolutePath().resolve("realworld");
        install();
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
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "128")))));
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
        Path stamp = project.resolve("node_modules/.package-lock.json");
        if (Files.exists(stamp) && Files.exists(project.resolve("node_modules/esbuild"))) {
            return;
        }
        // --ignore-scripts: native packages are only inspected, never built.
        Process npm = new ProcessBuilder("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund").directory(project.toFile())
                .redirectErrorStream(true).start();
        String log = new String(npm.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(npm.waitFor()).as("npm ci: " + log).isZero();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (node != null) {
            await(node.stop());
        }
    }

    @ParameterizedTest(name = "{0} gives the same answer as under Node")
    @ValueSource(strings = {"lodash", "datefns", "dayjs", "zod", "marked", "jsyaml", "yaml", "ajv", "semver", "immer", "rxjs", "mustache", "events", "validator", "uuid", "nanoid", "nodecrypto"})
    void aLibraryRunsInTheIsolateAsItDoesInNode(String lib) throws Exception {
        JsonNode expected = Json.parse(underNode(lib));
        Path script = project.resolve("job-" + lib + ".mjs");
        Files.writeString(script, "import { run } from 'realworld/" + lib + "'; export default async () => run();\n");
        Result ran = cli("run", script.toString());
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
            "jwt, stream (imported by",
            "papaparse, stream (imported by",
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
        Process node = new ProcessBuilder("node", "--input-type=module", "-e",
                "const m = await import('./src/" + lib + ".js'); console.log(JSON.stringify(await m.run()));")
                .directory(project.toFile()).redirectErrorStream(true).start();
        String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(node.waitFor()).as(lib + " under node: " + out).isZero();
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
