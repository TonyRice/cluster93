package automately.cli;

import automately.server.Automately;
import automately.server.Automately.RunningNode;
import automately.server.ServerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Every command against a real in-memory node, run in-process (phase 4, milestone 3). */
class CliTest {

    private static final String ADMIN = "cli-admin-token-0123456789abcdef-0123456789abcdef";

    private static RunningNode node;
    private static String url;

    @TempDir
    Path home;

    private Path config;

    /** What one command printed, and how it exited. */
    record Result(int code, String out, String err) {
        JsonNode json() {
            return Json.parse(out);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "cli"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", ADMIN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_DISK_ROOT", Files.createTempDirectory("automately-cli-disk").toString()),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64")))));
        url = "http://localhost:" + node.httpPort();
    }

    @AfterAll
    static void stop() throws Exception {
        await(node.stop());
    }

    @BeforeEach
    void freshConfig() {
        config = home.resolve("config");
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

    /** As the admin: a tenant and a token with every scope; then logs in with it. */
    private String loginAsNewTenant(String tenant) {
        assertThat(cli("--url", url, "--token", ADMIN, "tenants", "create", tenant).code()).isZero();
        Result minted = cli("--url", url, "--token", ADMIN, "tenants", "token", tenant, "laptop", "--scope", "*");
        assertThat(minted.code()).as(minted.err()).isZero();
        String token = minted.out().strip();
        Result login = cli(Map.of(), token + "\n", "login", url);
        assertThat(login.code()).as(login.err()).isZero();
        assertThat(login.out()).contains("tenant " + tenant);
        return token;
    }

    @Test
    void loginSavesTheUrlAndTokenAndLogoutForgetsThem() throws Exception {
        String token = loginAsNewTenant("login-test");

        JsonNode saved = Json.parse(Files.readString(config.resolve("config.json")));
        assertThat(saved.get("url").asText()).isEqualTo(url);
        assertThat(saved.get("token").asText()).isEqualTo(token);
        if (config.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(config.resolve("config.json"))))
                    .isEqualTo("rw-------");
        }
        assertThat(cli("whoami").out()).contains("tenant login-test");
        assertThat(cli("--json", "whoami").json().get("tenant").asText()).isEqualTo("login-test");

        assertThat(cli("logout").out()).contains("Logged out");
        Result after = cli("whoami");
        assertThat(after.code()).isEqualTo(1);
        assertThat(after.err()).contains("Not logged in");
    }

    @Test
    void aBadTokenIsRefusedAtLogin() {
        Result refused = cli(Map.of(), "atly_nonsense\n", "login", url);
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.err()).contains("error:");
        assertThat(Files.exists(config.resolve("config.json"))).isFalse();
    }

    @Test
    void runPrintsTheOutputAndExitsWithTheOutcome() throws Exception {
        loginAsNewTenant("runner");
        Path ok = home.resolve("ok.js");
        Files.writeString(ok, "export default async (input) => { console.log('working on', input.name); return { hello: input.name }; }");
        Path broken = home.resolve("broken.js");
        Files.writeString(broken, "export default () => { throw new Error('nope'); }");

        Result success = cli("run", ok.toString(), "--input", "{\"name\":\"tony\"}");
        assertThat(success.code()).as(success.err()).isZero();
        assertThat(Json.parse(success.out()).get("hello").asText()).isEqualTo("tony");
        assertThat(success.err()).contains("working on tony");

        Result failure = cli("run", broken.toString());
        assertThat(failure.code()).isEqualTo(1);
        assertThat(failure.err()).contains("FAILED").contains("nope");

        Result detached = cli("run", "-d", ok.toString(), "--input", "{\"name\":\"bg\"}");
        String id = detached.out().strip();
        assertThat(id).isNotBlank();
        Result logs = cli("jobs", "logs", id);
        assertThat(logs.code()).isZero();
        assertThat(logs.out()).contains("working on bg");
        assertThat(cli("jobs", "ls").out()).contains(id).contains("SUCCEEDED");
        assertThat(cli("--json", "jobs", "get", id).json().get("output").get("hello").asText()).isEqualTo("bg");
    }

    @Test
    void callbackSecretsCanBeShownAndRotated() throws Exception {
        loginAsNewTenant("hooks");
        String secret = cli("callbacks", "secret").out().strip();
        assertThat(secret).startsWith("whsec_");
        String rotated = cli("callbacks", "secret", "--rotate").out().strip();
        assertThat(rotated).startsWith("whsec_").isNotEqualTo(secret);
        assertThat(cli("callbacks", "secret").out().strip()).isEqualTo(rotated);

        Path script = home.resolve("cb.js");
        Files.writeString(script, "export default () => 1");
        Result submitted = cli("--json", "run", "-d", script.toString(), "--callback", "https://example.com/hook", "--callback-data", "{\"n\":1}");
        assertThat(submitted.code()).as(submitted.err()).isZero();
        assertThat(submitted.json().get("callback").get("data").get("n").asInt()).isEqualTo(1);
    }

    @Test
    void queuesPushShowStatsAndManageDeadLetters() throws Exception {
        loginAsNewTenant("queues");

        Result pushed = cli("queues", "push", "emails", "{\"to\":\"a@example.com\"}", "--key", "welcome-1");
        assertThat(pushed.code()).as(pushed.err()).isZero();
        assertThat(pushed.out().strip()).isEqualTo("k-welcome-1");
        assertThat(cli("queues", "push", "emails", "{\"to\":\"a@example.com\"}", "--key", "welcome-1").out().strip())
                .as("the same key pushes once").isEqualTo("k-welcome-1");

        assertThat(cli("queues", "stats", "emails").out()).contains("emails").containsPattern("emails\\s+1\\s+0\\s+0");
        assertThat(cli("--json", "queues", "dead", "emails").json().get("messages")).isEmpty();
        Result missing = cli("queues", "retry", "emails", "nope");
        assertThat(missing.code()).isNotZero();
        assertThat(missing.err()).contains("No dead-lettered message nope");
        assertThat(cli("queues", "push", "emails", "not json").err()).contains("must be JSON");
    }

    @Test
    void servicesDeployListStopAndRemove() throws Exception {
        loginAsNewTenant("svc");
        Path script = home.resolve("web.js");
        Files.writeString(script, "export default async (input, ctx) => { await ctx.serve(() => 'hi'); return ctx.stopRequested; }");

        Result deployed = cli("services", "deploy", "web", script.toString());
        assertThat(deployed.code()).as(deployed.err()).isZero();
        assertThat(deployed.out()).startsWith("web: ").contains("web--svc.");
        assertThat(cli("services", "ls").out()).contains("web");
        assertThat(cli("services", "stop", "web").out()).contains("web:");
        assertThat(cli("services", "rm", "web").out()).contains("Removed web");
        Result gone = cli("services", "get", "web");
        assertThat(gone.code()).isEqualTo(1);
        assertThat(gone.err()).contains("error:");
    }

    @Test
    void filesUploadDownloadAndSyncAFolder() throws Exception {
        loginAsNewTenant("files");
        Path site = Files.createDirectories(home.resolve("site"));
        Files.writeString(site.resolve("index.html"), "<h1>v1</h1>");
        Files.createDirectories(site.resolve("css"));
        Files.writeString(site.resolve("css/app.css"), "body{}");

        Result first = cli("files", "sync", site.toString(), "www");
        assertThat(first.code()).as(first.err()).isZero();
        assertThat(first.out()).contains("Uploaded 2, unchanged 0");

        Files.writeString(site.resolve("index.html"), "<h1>v2</h1>");
        assertThat(cli("files", "sync", site.toString(), "www").out()).contains("upload www/index.html").contains("Uploaded 1, unchanged 1");

        Files.delete(site.resolve("css/app.css"));
        assertThat(cli("files", "sync", site.toString(), "www", "--delete", "--dry-run").out()).contains("would delete www/css/app.css");
        assertThat(cli("files", "sync", site.toString(), "www", "--delete").out()).contains("delete www/css/app.css");

        assertThat(cli("files", "ls", "www/").out()).contains("www/index.html").doesNotContain("app.css");
        assertThat(cli("files", "get", "www/index.html", "-").out()).isEqualTo("<h1>v2</h1>");

        Path big = home.resolve("big.bin");
        byte[] data = new byte[3 * 1024 * 1024 + 7];
        new java.util.Random(1).nextBytes(data);
        Files.write(big, data);
        assertThat(cli("files", "put", big.toString(), "blobs/big.bin").code()).isZero();
        Path back = home.resolve("back.bin");
        assertThat(cli("files", "get", "blobs/big.bin", back.toString()).code()).isZero();
        assertThat(Files.readAllBytes(back)).isEqualTo(data);
        assertThat(cli("files", "rm", "blobs/big.bin").out()).contains("Deleted");
    }

    @Test
    void tokensAreCreatedOnceListedAndRevoked() {
        loginAsNewTenant("tok");
        Result created = cli("tokens", "create", "ci", "--scope", "jobs:read");
        assertThat(created.code()).as(created.err()).isZero();
        String secret = created.out().strip();
        assertThat(secret).startsWith("atly_");
        assertThat(created.err()).contains("shown only once");

        Result ci = cli(Map.of("AUTOMATELY_TOKEN", secret), "", "whoami");
        assertThat(ci.out()).contains("jobs:read");

        JsonNode listed = cli("--json", "tokens", "ls").json();
        String id = null;
        for (JsonNode token : listed.get("tokens")) {
            if (token.get("name").asText().equals("ci")) {
                id = token.get("id").asText();
            }
        }
        assertThat(id).isNotNull();
        assertThat(cli("tokens", "revoke", id).out()).contains("Revoked");
        assertThat(cli(Map.of("AUTOMATELY_TOKEN", secret), "", "whoami").code()).isEqualTo(1);
    }

    @Test
    void theMcpBridgeCarriesStdioToTheCluster() {
        loginAsNewTenant("bridge");
        String stdin = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"run_job\",\"arguments\":{\"script\":\"export default () => 40 + 2\"}}}",
                "") + "\n";

        Result bridged = cli(Map.of(), stdin, "mcp");

        assertThat(bridged.code()).as(bridged.err()).isZero();
        java.util.List<String> lines = bridged.out().lines().toList();
        assertThat(lines).as("one line per answer; the notification gets none").hasSize(2);
        assertThat(Json.parse(lines.get(0)).at("/result/serverInfo/name").asText()).isEqualTo("automately");
        JsonNode call = Json.parse(lines.get(1));
        assertThat(call.get("id").asInt()).isEqualTo(2);
        assertThat(call.at("/result/structuredContent/output").asInt()).isEqualTo(42);
        assertThat(bridged.err()).contains("bridging stdio");
    }

    @Test
    void storageProvidersAndRoutesFromTheCommandLine() throws Exception {
        loginAsNewTenant("stor");
        assertThat(cli("--url", url, "--token", ADMIN, "tenants", "storage-policy", "stor", "--allow", "disk", "--disk-mb", "5").code()).isZero();
        assertThat(cli("--url", url, "--token", ADMIN, "--json", "tenants", "storage-policy", "stor").json().get("diskAllotmentMb").asInt()).isEqualTo(5);

        Result saved = cli("storage", "providers", "set-disk", "local");
        assertThat(saved.code()).as(saved.err()).isZero();
        assertThat(cli("storage", "providers", "ls").out()).contains("local").contains("disk");
        assertThat(cli("storage", "providers", "check", "local").out()).contains("reachable");
        assertThat(cli("storage", "routes", "set", "--prefix", "backups/=local").code()).isZero();
        assertThat(cli("storage", "routes").out()).contains("backups/ -> local");

        Path file = home.resolve("db.gz");
        Files.write(file, new byte[1234]);
        assertThat(cli("files", "put", file.toString(), "backups/db.gz").code()).isZero();
        assertThat(cli("storage").out()).contains("disk 0.0 MB of 5.0 MB").contains("Allowed providers");

        Result inUse = cli("storage", "providers", "rm", "local");
        assertThat(inUse.code()).isEqualTo(1);
        assertThat(inUse.err()).contains("delete them first");
    }

    @Test
    void helpAndVersionWork() {
        Result help = cli("--help");
        assertThat(help.code()).isZero();
        assertThat(help.out()).contains("run").contains("services").contains("files").contains("mcp");
        assertThat(cli("--version").out()).startsWith("automately ");
        Result usage = cli("run");
        assertThat(usage.code()).isEqualTo(2);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
