package cluster93.cli;

import cluster93.server.Cluster93;
import cluster93.server.Cluster93.RunningNode;
import cluster93.server.ServerConfig;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code c93 apps} against a real in-memory node: deploy, serve, promote, roll back and remove. */
class AppsCliTest {

    private static final String ADMIN = "apps-admin-token-0123456789abcdef-0123456789abcdef";

    private static RunningNode node;
    private static String url;
    private static String ingressUrl;

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
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "apps-cli"),
                Map.entry("C93_ADMIN_TOKEN", ADMIN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_DISK_ROOT", Files.createTempDirectory("cluster93-apps-disk").toString()),
                Map.entry("C93_SECRETS_KEY", "apps-test-secrets-key-0123456789abcdef"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INGRESS_DOMAIN", "localhost"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64")))));
        url = "http://localhost:" + node.httpPort();
        ingressUrl = "http://localhost:" + node.ingressPort();
    }

    @AfterAll
    static void stop() throws Exception {
        await(node.stop());
    }

    @BeforeEach
    void freshConfig() {
        config = home.resolve("config");
    }

    @Test
    void deployUploadsServesListsAndGetsTheApp() throws Exception {
        String tenant = "apps-one";
        loginAsNewTenant(tenant);
        Path site = sampleSite("shop", "1.0.0", "<h1>shop</h1>");

        Result deployed = cli(ingressEnv(), "", "apps", "deploy", site.toString());
        assertThat(deployed.code()).as(deployed.err()).isZero();
        assertThat(deployed.out()).contains("Deployed shop").contains("3 files")
                .contains("preview:").contains("canonical:").contains("Promoted");

        String active = cli("--json", "apps", "get", "shop").json().get("activeDeploymentId").asText();
        assertThat(active).startsWith("dpl_");
        assertThat(deployed.out()).contains(active + "--shop--" + tenant).contains("shop--" + tenant);
        // The preview URL uses the configured ingress host.
        assertThat(deployed.out()).contains("http://" + active + "--shop--" + tenant + ".localhost:" + node.ingressPort());

        assertThat(cli("apps", "ls").out()).contains("shop").contains(active);

        JsonNode record = cli("--json", "apps", "get", "shop").json();
        assertThat(record.get("name").asText()).isEqualTo("shop");
        assertThat(record.get("activeDeploymentId").asText()).isEqualTo(active);

        JsonNode deployments = cli("--json", "apps", "deployments", "shop").json();
        assertThat(deployments.get("deployments")).hasSize(1);
        assertThat(deployments.at("/deployments/0/deploymentId").asText()).isEqualTo(active);
        assertThat(cli("apps", "deployments", "shop").out()).contains(active).contains("1.0.0");

        HttpResponse<String> served = ingressGet("/index.html", tenant, "shop");
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).isEqualTo("<h1>shop</h1>");
        assertThat(served.headers().firstValue("content-type").orElse("")).contains("text/html");
        // The SPA fallback serves index.html for a path that isn't a file.
        assertThat(ingressGet("/deep/route", tenant, "shop").body()).isEqualTo("<h1>shop</h1>");
        assertThat(ingressGet("/css/style.css", tenant, "shop").statusCode()).isEqualTo(200);
    }

    @Test
    void multipleDeploymentsPromoteAndRollback() throws Exception {
        String tenant = "apps-two";
        loginAsNewTenant(tenant);
        Path site = sampleSite("blog", "1.0.0", "<h1>v1</h1>");

        String id1 = cli(ingressEnv(), "", "--json", "apps", "deploy", site.toString()).json().get("deploymentId").asText();
        Files.writeString(site.resolve("out/index.html"), "<h1>v2</h1>");
        Files.writeString(site.resolve("cluster93.json"), "{\"name\": \"blog\", \"version\": \"1.0.1\"}");
        Result second = cli(ingressEnv(), "", "--json", "apps", "deploy", site.toString());
        String id2 = second.json().get("deploymentId").asText();
        assertThat(id2).isNotEqualTo(id1);
        assertThat(second.json().get("promoted").asBoolean()).isTrue();

        JsonNode app = cli("--json", "apps", "get", "blog").json();
        assertThat(app.get("activeDeploymentId").asText()).isEqualTo(id2);
        assertThat(app.get("deploymentHistory")).hasSize(2);
        assertThat(cli("--json", "apps", "deployments", "blog").json().get("deployments")).hasSize(2);
        assertThat(ingressGet("/", tenant, "blog").body()).isEqualTo("<h1>v2</h1>");

        Result promoted = cli("--json", "apps", "promote", "blog", id1);
        assertThat(promoted.code()).as(promoted.err()).isZero();
        assertThat(promoted.json().get("activeDeploymentId").asText()).isEqualTo(id1);
        assertThat(ingressGet("/", tenant, "blog").body()).isEqualTo("<h1>v1</h1>");

        Result rolled = cli("--json", "apps", "rollback", "blog");
        assertThat(rolled.code()).as(rolled.err()).isZero();
        assertThat(rolled.json().get("activeDeploymentId").asText()).isEqualTo(id2);
        assertThat(ingressGet("/", tenant, "blog").body()).isEqualTo("<h1>v2</h1>");
    }

    @Test
    void deployWithoutAManifestUsesTheFolderName() throws Exception {
        loginAsNewTenant("apps-three");
        Path site = Files.createDirectories(home.resolve("plain"));
        Files.writeString(site.resolve("index.html"), "<h1>plain</h1>");

        Result deployed = cli(ingressEnv(), "", "--json", "apps", "deploy", site.toString());
        assertThat(deployed.code()).as(deployed.err()).isZero();
        assertThat(deployed.json().get("app").asText()).isEqualTo("plain");
        assertThat(deployed.json().get("files").asInt()).isEqualTo(1);
        assertThat(deployed.json().get("previewUrl").asText()).contains("plain--apps-three");
    }

    @Test
    void noPromoteRegistersWithoutMakingItLive() throws Exception {
        loginAsNewTenant("apps-four");
        Path site = sampleSite("staged", "1.0.0", "<h1>staged</h1>");

        Result deployed = cli(ingressEnv(), "", "--json", "apps", "deploy", "--no-promote", site.toString());
        assertThat(deployed.code()).as(deployed.err()).isZero();
        assertThat(deployed.json().get("promoted").asBoolean()).isFalse();
        assertThat(cli("--json", "apps", "deployments", "staged").json().get("deployments")).hasSize(1);
        assertThat(cli("apps", "get", "staged").code()).isEqualTo(1);
    }

    @Test
    void rmNeedsConfirmationAndDeletesTheApp() throws Exception {
        loginAsNewTenant("apps-five");
        Path site = sampleSite("gone", "1.0.0", "<h1>gone</h1>");
        assertThat(cli(ingressEnv(), "", "apps", "deploy", site.toString()).code()).isZero();

        Result unconfirmed = cli("apps", "rm", "gone");
        assertThat(unconfirmed.code()).isEqualTo(1);
        assertThat(unconfirmed.err()).contains("--yes");
        assertThat(cli("apps", "ls").out()).contains("gone");

        Result removed = cli("apps", "rm", "gone", "--yes");
        assertThat(removed.code()).as(removed.err()).isZero();
        assertThat(removed.out()).contains("Removed app gone");
        assertThat(cli("apps", "get", "gone").code()).isEqualTo(1);
    }

    @Test
    void deploysConsoleStaticExport() throws Exception {
        Path consoleDir = Path.of("../console");
        if (!Files.exists(consoleDir.resolve("out/index.html"))) {
            return;
        }
        String tenant = "system-console";
        loginAsNewTenant(tenant);
        Result deployed = cli(ingressEnv(), "", "apps", "deploy", consoleDir.toString());
        assertThat(deployed.code()).as(deployed.err()).isZero();
        assertThat(deployed.out()).contains("Deployed console");

        HttpResponse<String> served = ingressGet("/index.html", tenant, "console");
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).contains("Cluster93");
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    /** A project folder with a cluster93.json and an out/ static export holding index.html, app.js and css/style.css. */
    private Path sampleSite(String name, String version, String indexHtml) throws Exception {
        Path site = Files.createDirectories(home.resolve(name + "-site"));
        Files.writeString(site.resolve("cluster93.json"), "{\"name\": \"" + name + "\", \"version\": \"" + version + "\"}");
        Path out = Files.createDirectories(site.resolve("out"));
        Files.writeString(out.resolve("index.html"), indexHtml);
        Files.writeString(out.resolve("app.js"), "console.log('" + name + "');");
        Files.createDirectories(out.resolve("css"));
        Files.writeString(out.resolve("css/style.css"), "body{margin:0}");
        return site;
    }

    private Map<String, String> ingressEnv() {
        return Map.of("C93_INGRESS_URL", ingressUrl);
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
        return token;
    }

    /** A request to the app host through the ingress, named by the test headers (no DNS needed). */
    private static HttpResponse<String> ingressGet(String path, String tenant, String app) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ingressUrl + path))
                .header("X-Cluster93-App", app).header("X-Cluster93-Tenant", tenant).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
