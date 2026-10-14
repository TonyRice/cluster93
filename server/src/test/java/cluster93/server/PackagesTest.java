package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Publishing packages over the API (D-083): only new files travel, tenants keep to theirs, operators publish the cluster's. */
class PackagesTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "packages"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_TENANT_STORAGE_MB", "1"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        if (clientVertx != null) { await(clientVertx.close()); }
        if (node != null) { await(node.stop()); }
    }

    @Test
    void aTenantPublishesUploadingOnlyWhatsNew() throws Exception {
        String acme = TestAuth.bearer("pk-acme");
        String index = "export const slug = s => s.toLowerCase().replace(/ /g, '-');";
        String helper = "export const trim = s => s.trim();";

        JsonArray missing = post(acme, "/v1/packages/blobs/missing", new JsonObject().put("sha256", new JsonArray().add(sha(index)).add(sha(helper))))
                .bodyAsJsonObject().getJsonArray("missing");
        assertThat(missing).containsExactly(sha(index), sha(helper));
        assertThat(upload(acme, sha(index), index).statusCode()).isEqualTo(201);
        assertThat(upload(acme, sha(index), index).statusCode()).as("already there").isEqualTo(200);

        JsonObject manifest = manifest("strings", "1.0.0", Map.of("index.js", index, "lib/trim.js", helper));
        HttpResponse<Buffer> early = post(acme, "/v1/packages", manifest);
        assertThat(early.statusCode()).as(early.bodyAsString()).isEqualTo(409);
        assertThat(early.bodyAsString()).contains("missing_files");

        assertThat(upload(acme, sha(helper), helper).statusCode()).isEqualTo(201);
        HttpResponse<Buffer> published = post(acme, "/v1/packages", manifest);
        assertThat(published.statusCode()).as(published.bodyAsString()).isEqualTo(201);
        assertThat(post(acme, "/v1/packages", manifest).statusCode()).as("versions never change").isEqualTo(409);

        JsonObject info = get(acme, "/v1/packages/strings").bodyAsJsonObject();
        assertThat(info.getString("latest")).isEqualTo("1.0.0");
        JsonObject version = get(acme, "/v1/packages/strings/versions/1.0.0").bodyAsJsonObject();
        assertThat(version.getJsonObject("files").getString("lib/trim.js")).isEqualTo("sha256:" + sha(helper));
        assertThat(get(TestAuth.bearer("pk-globex"), "/v1/packages/strings").statusCode()).as("other tenants can't see it").isEqualTo(404);

        JsonArray audited = get(acme, "/v1/audit?limit=50").bodyAsJsonObject().getJsonArray("entries");
        assertThat(audited.stream().map(e -> ((JsonObject) e).getString("action")))
                .contains("publishPackage").doesNotContain("putPackageFile", "findMissingPackageFiles");
    }

    @Test
    void aFileMustMatchItsHashAndFitTheQuota() throws Exception {
        String bearer = TestAuth.bearer("pk-strict");
        HttpResponse<Buffer> lying = upload(bearer, sha("the real thing"), "something else");
        assertThat(lying.statusCode()).isEqualTo(400);
        assertThat(lying.bodyAsString()).contains("SHA-256");

        assertThat(upload(bearer, "not-a-hash", "x").statusCode()).as("the contract checks the hash's form").isEqualTo(400);

        String big = "x".repeat(1_100_000);
        HttpResponse<Buffer> tooBig = upload(bearer, sha(big), big);
        assertThat(tooBig.statusCode()).as(tooBig.bodyAsString()).isEqualTo(413);
        assertThat(post(bearer, "/v1/packages/blobs/missing", new JsonObject().put("sha256", new JsonArray().add(sha(big))))
                .bodyAsJsonObject().getJsonArray("missing")).hasSize(1);
    }

    @Test
    void operatorsPublishTheClustersPackagesWhichEveryTenantSees() throws Exception {
        String code = "export const shout = s => s.toUpperCase();";
        String tenant = TestAuth.bearer("pk-user");

        assertThat(upload(tenant, sha(code), code).statusCode()).isEqualTo(201);
        HttpResponse<Buffer> impostor = post(tenant, "/v1/packages", manifest("@cluster/shout", "1.0.0", Map.of("index.js", code)));
        assertThat(impostor.statusCode()).as("tenants can't publish under @cluster/").isEqualTo(403);

        assertThat(upload(TestAuth.ADMIN, sha(code), code).statusCode()).as("the cluster keeps its own copy").isEqualTo(201);
        assertThat(post(TestAuth.ADMIN, "/v1/packages", manifest("shout", "1.0.0", Map.of("index.js", code))).statusCode())
                .as("operators publish @cluster/ names only").isEqualTo(403);
        HttpResponse<Buffer> published = post(TestAuth.ADMIN, "/v1/packages", manifest("@cluster/shout", "1.0.0", Map.of("index.js", code)));
        assertThat(published.statusCode()).as(published.bodyAsString()).isEqualTo(201);

        assertThat(get(tenant, "/v1/packages").bodyAsJsonObject().getJsonArray("packages").stream()
                .map(p -> ((JsonObject) p).getString("name"))).contains("@cluster/shout");
        HttpResponse<Buffer> seen = get(tenant, "/v1/packages/%40cluster%2Fshout");
        assertThat(seen.statusCode()).as(seen.bodyAsString()).isEqualTo(200);
        assertThat(seen.bodyAsJsonObject().getBoolean("cluster")).isTrue();
        assertThat(get(tenant, "/v1/packages/%40cluster%2Fshout/versions/1.0.0").statusCode()).isEqualTo(200);

        JsonObject app = manifest("app", "1.0.0", Map.of("index.js", code)).put("dependencies", new JsonObject().put("@cluster/shout", "^1.0.0"));
        assertThat(post(tenant, "/v1/packages", app).statusCode()).isEqualTo(201);
    }

    @Test
    void deletingATenantDeletesItsPackages() throws Exception {
        String bearer = TestAuth.bearer("pk-leaving");
        String code = "export default 'bye';";
        upload(bearer, sha(code), code);
        assertThat(post(bearer, "/v1/packages", manifest("bye", "1.0.0", Map.of("index.js", code))).statusCode()).isEqualTo(201);

        HttpResponse<Buffer> deleted = await(client.delete(node.httpPort(), "localhost", "/v1/tenants/pk-leaving")
                .putHeader("Authorization", TestAuth.ADMIN).send());
        assertThat(deleted.statusCode()).as(deleted.bodyAsString()).isEqualTo(204);
        HttpResponse<Buffer> again = await(client.post(node.httpPort(), "localhost", "/v1/tenants")
                .putHeader("Authorization", TestAuth.ADMIN).sendJsonObject(new JsonObject().put("name", "pk-leaving")));
        assertThat(again.statusCode()).isEqualTo(201);
        JsonObject token = await(client.post(node.httpPort(), "localhost", "/v1/tenants/pk-leaving/tokens")
                .putHeader("Authorization", TestAuth.ADMIN)
                .sendJsonObject(new JsonObject().put("name", "t").put("scopes", new JsonArray().add("*")))).bodyAsJsonObject();
        String fresh = "Bearer " + token.getString("token");
        assertThat(get(fresh, "/v1/packages/bye").statusCode()).isEqualTo(404);
        assertThat(post(fresh, "/v1/packages/blobs/missing", new JsonObject().put("sha256", new JsonArray().add(sha(code))))
                .bodyAsJsonObject().getJsonArray("missing")).hasSize(1);
    }

    @Test
    void aJobImportsPackagesAtTheVersionsLockedWhenItWasSubmitted() throws Exception {
        String bearer = TestAuth.bearer("pk-runner");
        publish(bearer, manifest("strings", "1.0.0", Map.of(
                "index.js", "import { trim } from './lib/trim.js'; export const slug = s => trim(s).toLowerCase().replace(/ /g, '-');",
                "lib/trim.js", "export const trim = s => s.trim();")), Map.of(
                "index.js", "import { trim } from './lib/trim.js'; export const slug = s => trim(s).toLowerCase().replace(/ /g, '-');",
                "lib/trim.js", "export const trim = s => s.trim();"));
        Map<String, String> app = Map.of("index.js", "import { slug } from 'strings'; export const title = s => 'title:' + slug(s);");
        publish(bearer, manifest("app", "1.0.0", app).put("dependencies", new JsonObject().put("strings", "^1.0.0")), app);

        String script = "import { title } from 'app'; import { slug } from 'strings';"
                + " export default async () => { const later = await import('strings'); return [title(' A B '), slug('C D'), later.slug('E F')]; };";
        JsonObject first = submit(bearer, new JsonObject().put("script", script));
        assertThat(first.getJsonObject("packages")).isEqualTo(new JsonObject().put("app", "1.0.0").put("strings", "1.0.0"));
        JsonObject done = awaitJob(bearer, first.getString("id"));
        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(done.getJsonArray("output")).containsExactly("title:a-b", "c-d", "e-f");

        Map<String, String> louder = Map.of("index.js", "export const slug = s => s.trim().toUpperCase().replace(/ /g, '_');");
        publish(bearer, manifest("strings", "1.1.0", louder), louder);
        JsonObject second = awaitJob(bearer, submit(bearer, new JsonObject().put("script", script)).getString("id"));
        assertThat(second.getJsonObject("packages").getString("strings")).isEqualTo("1.1.0");
        assertThat(second.getJsonArray("output")).containsExactly("title:A_B", "C_D", "E_F");
        assertThat(get(bearer, "/v1/jobs/" + first.getString("id")).bodyAsJsonObject().getJsonObject("packages").getString("strings"))
                .as("the first job's record still says what it ran").isEqualTo("1.0.0");
    }

    @Test
    void importsAreCheckedWhenAJobIsSubmitted() throws Exception {
        String bearer = TestAuth.bearer("pk-checked");
        Map<String, String> files = Map.of("index.js", "export default 'x';");
        publish(bearer, manifest("present", "1.0.0", files), files);

        HttpResponse<Buffer> unknown = post(bearer, "/v1/jobs", new JsonObject().put("script", "import x from 'absent'; export default () => x;"));
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("unresolved_import");
        assertThat(unknown.bodyAsString()).contains("No package named \\\"absent\\\"");

        HttpResponse<Buffer> computed = post(bearer, "/v1/jobs", new JsonObject().put("script", "export default async (i) => import(i.name);"));
        assertThat(computed.statusCode()).isEqualTo(400);
        assertThat(computed.bodyAsString()).contains("doesn't name a package with a string literal");

        HttpResponse<Buffer> off = post(bearer, "/v1/jobs", new JsonObject().put("script", "import x from 'present'; export default () => x;")
                .put("packages", false));
        assertThat(off.statusCode()).isEqualTo(400);
        assertThat(off.bodyAsString()).contains("\\\"packages\\\": false");

        assertThat(post(bearer, "/v1/jobs", new JsonObject().put("script", "import p from 'vm'; export default () => p;")).bodyAsString())
                .contains("Node's \\\"vm\\\" module isn't available");
        assertThat(post(bearer, "/v1/jobs", new JsonObject().put("script", "export default () => 1;").put("packages", "yes")).statusCode())
                .isEqualTo(400);
        assertThat(post(bearer, "/v1/jobs", new JsonObject().put("script", "export default () => 1;").put("packages", false)).statusCode())
                .as("no imports, nothing to refuse").isEqualTo(202);
    }

    @Test
    void aStartedJobImportsWhatItsParentCould() throws Exception {
        String bearer = TestAuth.bearer("pk-parent");
        Map<String, String> files = Map.of("index.js", "export const shout = s => s.toUpperCase() + '!';");
        publish(bearer, manifest("shout", "1.0.0", files), files);
        String script = "import { shout } from 'shout';"
                + " export default async () => {"
                + "   const child = await jobs.run(\"import { shout } from 'shout'; export default () => shout('child');\");"
                + "   const result = await child.result();"
                + "   let refused; try { await jobs.run(\"import x from 'other'; export default () => x;\"); } catch (e) { refused = e.message; }"
                + "   return [shout('parent'), result.output, refused];"
                + " };";
        JsonObject done = awaitJob(bearer, submit(bearer, new JsonObject().put("script", script)).getString("id"));
        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(done.getJsonArray("output").getString(0)).isEqualTo("PARENT!");
        assertThat(done.getJsonArray("output").getString(1)).isEqualTo("CHILD!");
        assertThat(done.getJsonArray("output").getString(2)).contains("only the packages its parent could");
    }

    @Test
    void aServiceRevisionKeepsItsLockAndARedeployMovesIt() throws Exception {
        String bearer = TestAuth.bearer("pk-service");
        Map<String, String> v1 = Map.of("index.js", "export const v = 1;");
        publish(bearer, manifest("lib", "1.0.0", v1), v1);
        JsonObject service = new JsonObject().put("script", "import { v } from 'lib'; export default async (cfg, ctx) => { await new Promise(() => {}); };")
                .put("enabled", false);
        HttpResponse<Buffer> created = await(client.put(node.httpPort(), "localhost", "/v1/services/uses-lib").putHeader("Authorization", bearer)
                .sendJsonObject(service));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        assertThat(created.bodyAsJsonObject().getJsonObject("packages")).isEqualTo(new JsonObject().put("lib", "1.0.0"));

        Map<String, String> v2 = Map.of("index.js", "export const v = 2;");
        publish(bearer, manifest("lib", "1.1.0", v2), v2);
        JsonObject redeployed = await(client.put(node.httpPort(), "localhost", "/v1/services/uses-lib").putHeader("Authorization", bearer)
                .sendJsonObject(service)).bodyAsJsonObject();
        assertThat(redeployed.getJsonObject("packages").getString("lib")).isEqualTo("1.1.0");
        assertThat(redeployed.getInteger("revision")).as("new versions are a new revision").isEqualTo(2);
    }

    @Test
    void aPublishedVersionRunsAsAJobOrAServiceRevision() throws Exception {
        String bearer = TestAuth.bearer("pk-deploy");
        Map<String, String> v1 = Map.of("index.js", "export default (input) => 'v1:' + input.n;", "extra.js", "export default () => 'extra';");
        publish(bearer, manifest("calc", "1.0.0", v1).put("exports", new JsonObject().put(".", "index.js").put("./extra", "extra.js")), v1);

        JsonObject job = awaitJob(bearer, submit(bearer, new JsonObject().put("package", new JsonObject().put("name", "calc"))
                .put("input", new JsonObject().put("n", 7))).getString("id"));
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(job.getString("output")).isEqualTo("v1:7");
        assertThat(job.getJsonObject("packages")).isEqualTo(new JsonObject().put("calc", "1.0.0"));
        JsonObject extra = awaitJob(bearer, submit(bearer, new JsonObject().put("package", new JsonObject().put("name", "calc")
                .put("version", "1.0.0").put("export", "./extra"))).getString("id"));
        assertThat(extra.getString("output")).isEqualTo("extra");

        HttpResponse<Buffer> both = post(bearer, "/v1/jobs", new JsonObject().put("script", "export default () => 1;")
                .put("package", new JsonObject().put("name", "calc")));
        assertThat(both.statusCode()).isEqualTo(400);
        assertThat(post(bearer, "/v1/jobs", new JsonObject().put("package", new JsonObject().put("name", "calc").put("version", "^1")))
                .statusCode()).as("a revision is an exact version").isEqualTo(400);
        assertThat(post(bearer, "/v1/jobs", new JsonObject().put("package", new JsonObject().put("name", "calc").put("version", "9.0.0")))
                .bodyAsString()).contains("No version of calc meets 9.0.0");

        JsonObject service = new JsonObject().put("package", new JsonObject().put("name", "calc").put("version", "1.0.0")).put("enabled", false);
        JsonObject deployed = await(client.put(node.httpPort(), "localhost", "/v1/services/calc-svc").putHeader("Authorization", bearer)
                .sendJsonObject(service)).bodyAsJsonObject();
        assertThat(deployed.getJsonObject("packages")).isEqualTo(new JsonObject().put("calc", "1.0.0"));
        assertThat(deployed.getString("script")).isEqualTo("export { default } from \"calc\";\n");
        Map<String, String> v2 = Map.of("index.js", "export default (input) => 'v2';");
        publish(bearer, manifest("calc", "1.1.0", v2), v2);
        JsonObject rolled = await(client.put(node.httpPort(), "localhost", "/v1/services/calc-svc").putHeader("Authorization", bearer)
                .sendJsonObject(service.copy().put("package", new JsonObject().put("name", "calc").put("version", "1.1.0")))).bodyAsJsonObject();
        assertThat(rolled.getJsonObject("packages").getString("calc")).isEqualTo("1.1.0");
        assertThat(rolled.getInteger("revision")).as("a new version is a new revision").isEqualTo(2);
    }

    @Test
    void aPackageReadsItsOwnFilesAndImportsTheHostApis() throws Exception {
        String bearer = TestAuth.bearer("pk-assets");
        Map<String, String> files = Map.of(
                "index.js", "import { readFileSync, readdirSync, writeFileSync } from 'fs'; import { URL } from 'url';"
                        + " import { kv } from 'c93:kv'; import bus from 'c93:bus';"
                        + " export default async () => {"
                        + "   const data = JSON.parse(readFileSync(new URL('./data/config.json', import.meta.url), 'utf8'));"
                        + "   let refused; try { writeFileSync('/packages/assets/1.0.0/x.txt', 'no'); } catch (e) { refused = e.code; }"
                        + "   await kv.set('greeting', data.greeting);"
                        + "   return [data.greeting, readdirSync('/packages'), readdirSync('/packages/assets/1.0.0'), refused,"
                        + "     await kv.get('greeting'), typeof bus.publish, readdirSync('/').includes('packages')];"
                        + " };",
                "data/config.json", "{\"greeting\": \"hello from a package\"}");
        publish(bearer, manifest("assets", "1.0.0", files), files);
        JsonObject done = awaitJob(bearer, submit(bearer, new JsonObject().put("package", new JsonObject().put("name", "assets"))).getString("id"));
        assertThat(done.getString("status")).as(done.encodePrettily()).isEqualTo("SUCCEEDED");
        JsonArray out = done.getJsonArray("output");
        assertThat(out.getString(0)).isEqualTo("hello from a package");
        assertThat(out.getJsonArray(1)).containsExactly("assets");
        assertThat(out.getJsonArray(2)).containsExactly("data", "index.js");
        assertThat(out.getString(3)).isEqualTo("EROFS");
        assertThat(out.getString(4)).isEqualTo("hello from a package");
        assertThat(out.getString(5)).isEqualTo("function");
        assertThat(out.getBoolean(6)).isTrue();
    }

    @Test
    void blockingAVersionStopsTheServicesRunningItAndNeedsAdminRights() throws Exception {
        String bearer = TestAuth.bearer("pk-blocker");
        Map<String, String> files = Map.of("index.js", "export default async (config, ctx) => { await ctx.stopRequested; };");
        publish(bearer, manifest("worker", "1.0.0", files), files);
        HttpResponse<Buffer> deployed = await(client.put(node.httpPort(), "localhost", "/v1/services/worker").putHeader("Authorization", bearer)
                .sendJsonObject(new JsonObject().put("package", new JsonObject().put("name", "worker").put("version", "1.0.0"))));
        assertThat(deployed.statusCode()).as(deployed.bodyAsString()).isEqualTo(201);
        JsonObject running = awaitService(bearer, "worker", s -> "RUNNING".equals(s.getString("status")));
        String runId = running.getString("currentJobId");

        JsonObject developer = await(client.post(node.httpPort(), "localhost", "/v1/tenants/pk-blocker/tokens")
                .putHeader("Authorization", TestAuth.ADMIN).sendJsonObject(new JsonObject().put("name", "dev")
                        .put("scopes", new JsonArray().add("packages:read").add("packages:write")))).bodyAsJsonObject();
        String dev = "Bearer " + developer.getString("token");
        String version = "/v1/packages/worker/versions/1.0.0/";
        assertThat(post(dev, version + "block", new JsonObject().put("reason", "nope")).statusCode()).as("packages:admin is needed").isEqualTo(403);
        assertThat(post(dev, version + "yank", new JsonObject()).statusCode()).as("yanking is packages:write").isEqualTo(200);
        assertThat(post(dev, version + "unyank", new JsonObject()).statusCode()).isEqualTo(200);
        assertThat(post(bearer, version + "block", new JsonObject()).statusCode()).as("a reason is required").isEqualTo(400);

        HttpResponse<Buffer> blocked = post(bearer, version + "block", new JsonObject().put("reason", "CVE-2026-0001"));
        assertThat(blocked.statusCode()).as(blocked.bodyAsString()).isEqualTo(200);
        assertThat(blocked.bodyAsJsonObject().getJsonArray("stoppedServices")).containsExactly("pk-blocker/worker");
        JsonObject stopped = awaitJob(bearer, runId);
        assertThat(stopped.getString("error")).contains("worker@1.0.0 is blocked (CVE-2026-0001)");
        assertThat(get(bearer, "/v1/services/worker").bodyAsJsonObject().getBoolean("enabled")).as("it doesn't restart into it").isFalse();

        HttpResponse<Buffer> refused = post(bearer, "/v1/jobs", new JsonObject().put("script", "import 'worker'; export default () => 1;"));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.bodyAsString()).contains("No version of worker");
        assertThat(get(bearer, "/v1/packages/worker/versions/1.0.0").bodyAsJsonObject().getJsonObject("blocked").getString("reason"))
                .isEqualTo("CVE-2026-0001");
        JsonArray audited = get(bearer, "/v1/audit?limit=50").bodyAsJsonObject().getJsonArray("entries");
        assertThat(audited.stream().map(e -> ((JsonObject) e).getString("action"))).contains("blockPackageVersion", "yankPackageVersion");
    }

    // --- helpers ---

    private static JsonObject manifest(String name, String version, Map<String, String> files) throws Exception {
        JsonObject hashes = new JsonObject();
        for (Map.Entry<String, String> file : files.entrySet()) {
            hashes.put(file.getKey(), "sha256:" + sha(file.getValue()));
        }
        return new JsonObject().put("name", name).put("version", version).put("files", hashes);
    }

    /** Uploads the files and publishes the manifest; fails the test if either is refused. */
    private static void publish(String bearer, JsonObject manifest, Map<String, String> files) throws Exception {
        for (String content : files.values()) {
            assertThat(upload(bearer, sha(content), content).statusCode()).isIn(200, 201);
        }
        HttpResponse<Buffer> published = post(bearer, "/v1/packages", manifest);
        assertThat(published.statusCode()).as(published.bodyAsString()).isEqualTo(201);
    }

    private static JsonObject submit(String bearer, JsonObject job) throws Exception {
        HttpResponse<Buffer> submitted = post(bearer, "/v1/jobs", job);
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        return submitted.bodyAsJsonObject();
    }

    private static JsonObject awaitService(String bearer, String name, java.util.function.Predicate<JsonObject> condition) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = get(bearer, "/v1/services/" + name).bodyAsJsonObject();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Service " + name + " never got there; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static JsonObject awaitJob(String bearer, String id) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            last = get(bearer, "/v1/jobs/" + id).bodyAsJsonObject();
            if (java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "ORPHANED").contains(last.getString("status"))) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Job " + id + " never ended; last: " + (last == null ? null : last.encodePrettily()));
    }

    private static HttpResponse<Buffer> upload(String bearer, String hash, String content) throws Exception {
        return await(client.put(node.httpPort(), "localhost", "/v1/packages/blobs/" + hash).putHeader("Authorization", bearer)
                .putHeader("Content-Type", "application/octet-stream").sendBuffer(Buffer.buffer(content)));
    }

    private static HttpResponse<Buffer> post(String bearer, String path, JsonObject body) throws Exception {
        return await(client.post(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).sendJsonObject(body));
    }

    private static HttpResponse<Buffer> get(String bearer, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).send());
    }

    static String sha(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
