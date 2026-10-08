package automately.server;

import automately.server.Automately.RunningNode;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tenants' secrets (D-068): write-only through the API, readable by scripts only with {@code secrets:use}. */
class SecretsTest {

    private static final String TENANT = "vault";
    private static final String VALUE = "postgres://app:s3cr3t-pa55@db.internal/app";
    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    private static Map<String, String> env(String id, boolean withKey) {
        Map<String, String> env = new HashMap<>(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", id),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "6"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200")));
        if (withKey) {
            env.put("AUTOMATELY_SECRETS_KEY", "secrets-test-key-0123456789abcdef-0123");
        }
        return env;
    }

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(env("vault", true))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        await(clientVertx.close());
        await(node.stop());
    }

    @Test
    void aSecretGoesInButItsValueNeverComesBackOut() throws Exception {
        String all = TestAuth.bearer(TENANT);
        HttpResponse<Buffer> created = put(all, "/v1/secrets/api-key", new JsonObject().put("value", "first"));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        assertThat(created.bodyAsJsonObject().getInteger("version")).isEqualTo(1);

        HttpResponse<Buffer> replaced = put(all, "/v1/secrets/api-key", new JsonObject().put("value", "second-value"));
        assertThat(replaced.statusCode()).isEqualTo(200);
        JsonObject info = replaced.bodyAsJsonObject();
        assertThat(info.getInteger("version")).isEqualTo(2);
        assertThat(info.getString("updatedBy")).isNotBlank();
        assertThat(info.containsKey("value")).isFalse();

        String shown = get(all, "/v1/secrets/api-key").bodyAsString() + get(all, "/v1/secrets").bodyAsString();
        assertThat(shown).contains("api-key").doesNotContain("second-value").doesNotContain("first");

        assertThat(delete(all, "/v1/secrets/api-key").statusCode()).isEqualTo(204);
        assertThat(get(all, "/v1/secrets/api-key").statusCode()).isEqualTo(404);
        assertThat(delete(all, "/v1/secrets/api-key").statusCode()).isEqualTo(404);
    }

    @Test
    void badNamesAndValuesAreRefused() throws Exception {
        String all = TestAuth.bearer(TENANT);
        assertThat(put(all, "/v1/secrets/.hidden", new JsonObject().put("value", "x")).statusCode()).isEqualTo(400);
        assertThat(put(all, "/v1/secrets/ok", new JsonObject().put("value", 42)).statusCode()).isEqualTo(400);
        assertThat(put(all, "/v1/secrets/big", new JsonObject().put("value", "x".repeat(64 * 1024 + 1))).statusCode()).isEqualTo(400);
    }

    @Test
    void onlyScriptsSubmittedWithSecretsUseReadThem() throws Exception {
        String all = TestAuth.bearer(TENANT);
        assertThat(put(all, "/v1/secrets/db-url", new JsonObject().put("value", VALUE)).statusCode()).isIn(200, 201);
        String reader = """
                export default async () => {
                  try { return { value: await secrets.get('db-url'), missing: await secrets.get('nope') }; }
                  catch (e) { return { code: e.code }; }
                }
                """;

        JsonObject allowed = run(all, reader);
        assertThat(allowed.getBoolean("secrets")).isTrue();
        assertThat(allowed.getJsonObject("output")).isEqualTo(new JsonObject().put("value", VALUE).putNull("missing"));

        // A token that can run jobs, but not use secrets: what an agent might be given.
        String jobsOnly = token(all, "jobs:read", "jobs:write");
        JsonObject refused = run(jobsOnly, reader);
        assertThat(refused.getBoolean("secrets")).isFalse();
        assertThat(refused.getJsonObject("output")).isEqualTo(new JsonObject().put("code", "forbidden"));

        // A token that can set and list secrets doesn't read them either.
        String manager = token(all, "secrets:read", "secrets:write");
        assertThat(get(manager, "/v1/secrets").bodyAsString()).contains("db-url").doesNotContain(VALUE);
    }

    @Test
    void startedJobsMayReadSecretsOnlyIfTheirParentMay() throws Exception {
        String all = TestAuth.bearer(TENANT);
        assertThat(put(all, "/v1/secrets/child-secret", new JsonObject().put("value", "from-parent")).statusCode()).isIn(200, 201);
        String parent = """
                export default async () => {
                  const child = await jobs.run(async () => {
                    try { return await secrets.get('child-secret'); } catch (e) { return e.code; }
                  });
                  return (await child.result()).output;
                }
                """;

        assertThat(run(all, parent).getString("output")).isEqualTo("from-parent");
        assertThat(run(token(all, "jobs:read", "jobs:write"), parent).getString("output")).isEqualTo("forbidden");
    }

    @Test
    void aServiceReadsSecretsIfWhoeverDefinedItMay() throws Exception {
        String all = TestAuth.bearer(TENANT);
        assertThat(put(all, "/v1/secrets/svc-secret", new JsonObject().put("value", "for-the-service")).statusCode()).isIn(200, 201);
        JsonObject service = new JsonObject().put("restart", "on-failure").put("enabled", true).put("script", """
                export default async () => {
                  try { return await secrets.get('svc-secret'); } catch (e) { return e.code; }
                }
                """);

        HttpResponse<Buffer> defined = put(all, "/v1/services/reader", service);
        assertThat(defined.statusCode()).as(defined.bodyAsString()).isIn(200, 201);
        assertThat(defined.bodyAsJsonObject().getBoolean("secrets")).isTrue();
        assertThat(serviceOutput(all, "reader", 1)).isEqualTo("for-the-service");

        // Redefined by a token without secrets:use, its next run can't read them.
        String servicesOnly = token(all, "services:read", "services:write", "jobs:read");
        HttpResponse<Buffer> redefined = put(servicesOnly, "/v1/services/reader", service);
        assertThat(redefined.bodyAsJsonObject().getBoolean("secrets")).isFalse();
        assertThat(redefined.bodyAsJsonObject().getInteger("revision")).isEqualTo(2);
        assertThat(serviceOutput(all, "reader", 2)).isEqualTo("forbidden");
        delete(all, "/v1/services/reader");
    }

    @Test
    void tenantsOnlySeeTheirOwnSecrets() throws Exception {
        assertThat(put(TestAuth.bearer(TENANT), "/v1/secrets/mine", new JsonObject().put("value", "vault-only")).statusCode()).isIn(200, 201);
        String other = TestAuth.bearer("neighbour");

        assertThat(get(other, "/v1/secrets/mine").statusCode()).isEqualTo(404);
        assertThat(run(other, "export default () => secrets.get('mine')").getValue("output")).isNull();
    }

    @Test
    void deletingATenantForgetsItsSecrets() throws Exception {
        String bearer = TestAuth.bearer("short-lived");
        assertThat(put(bearer, "/v1/secrets/leftover", new JsonObject().put("value", "gone soon")).statusCode()).isEqualTo(201);

        HttpResponse<Buffer> deleted = await(client.delete(node.httpPort(), "localhost", "/v1/tenants/short-lived")
                .putHeader("Authorization", TestAuth.ADMIN).send());
        assertThat(deleted.statusCode()).as(deleted.bodyAsString()).isEqualTo(204);
        TestAuth.use(node);

        assertThat(get(TestAuth.bearer("short-lived"), "/v1/secrets").bodyAsJsonObject().getJsonArray("secrets")).isEmpty();
    }

    @Test
    void withoutASecretsKeyThereAreNoSecrets() throws Exception {
        RunningNode keyless = await(Automately.start(ServerConfig.fromEnv(env("keyless", false))));
        try {
            TestAuth.use(keyless);
            String bearer = TestAuth.bearer("keyless-tenant");
            HttpResponse<Buffer> refused = await(client.put(keyless.httpPort(), "localhost", "/v1/secrets/x").putHeader("Authorization", bearer)
                    .sendJsonObject(new JsonObject().put("value", "x")));
            assertThat(refused.statusCode()).isEqualTo(409);
            assertThat(refused.bodyAsJsonObject().getJsonObject("error").getString("code")).isEqualTo("secrets_unavailable");
        } finally {
            await(keyless.stop());
            TestAuth.use(node);
        }
    }

    @Test
    void aJobsEnvHoldsWhatItDeclaresWithSecretsReadAtRunTimeAndMaskedInLogs() throws Exception {
        String all = TestAuth.bearer("env-acme");
        assertThat(put(all, "/v1/secrets/stripe-live", new JsonObject().put("value", "sk_live_0123456789")).statusCode()).isIn(200, 201);
        JsonObject declared = new JsonObject().put("API_URL", "https://api.example.com").put("STRIPE", new JsonObject().put("secret", "stripe-live"));

        JsonObject job = submitAndWait(all, new JsonObject().put("env", declared).put("script",
                "export default () => { console.log('using key', process.env.STRIPE); return [process.env.API_URL, process.env.STRIPE.length,"
                        + " Object.keys(process.env).sort().join(',')]; }"));
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(job.getJsonArray("output")).containsExactly("https://api.example.com", 18, "API_URL,NODE_ENV,STRIPE");
        assertThat(job.getJsonArray("logs").encode()).contains("using key [secret:stripe-live]");
        assertThat(job.getJsonObject("env")).as("the record keeps the name, not the value").isEqualTo(declared);
        assertThat(job.encode()).doesNotContain("sk_live_0123456789");

        String narrow = token(all, "jobs:read", "jobs:write");
        HttpResponse<Buffer> refused = post(narrow, "/v1/jobs", new JsonObject().put("env", declared).put("script", "export default () => 1;"));
        assertThat(refused.statusCode()).as("naming a secret needs secrets:use").isEqualTo(403);
        assertThat(post(narrow, "/v1/jobs", new JsonObject().put("env", new JsonObject().put("A", "1")).put("script", "export default () => 1;"))
                .statusCode()).as("plain values don't").isEqualTo(202);
        assertThat(post(all, "/v1/jobs", new JsonObject().put("env", new JsonObject().put("bad-name", "1")).put("script", "export default () => 1;"))
                .statusCode()).isEqualTo(400);

        JsonObject missing = submitAndWait(all, new JsonObject().put("env", new JsonObject().put("K", new JsonObject().put("secret", "nope")))
                .put("script", "export default () => 1;"));
        assertThat(missing.getString("status")).isEqualTo("FAILED");
        assertThat(missing.getString("error")).contains("env.K names the secret \"nope\", which doesn't exist");
    }

    @Test
    void aStartedJobGetsItsParentsEnvOnlyIfItAsks() throws Exception {
        String all = TestAuth.bearer("env-parent");
        JsonObject job = submitAndWait(all, new JsonObject().put("env", new JsonObject().put("REGION", "eu")).put("script",
                "export default async () => {"
                        + " const read = 'export default () => [process.env.REGION ?? null, process.env.OWN ?? null];';"
                        + " const plain = await (await jobs.run(read)).result();"
                        + " const inherited = await (await jobs.run(read, {}, { inheritEnv: true, env: { OWN: 'x' } })).result();"
                        + " return [plain.output, inherited.output]; };"));
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        assertThat(job.getJsonArray("output").getJsonArray(0)).containsExactly(null, null);
        assertThat(job.getJsonArray("output").getJsonArray(1)).containsExactly("eu", "x");
    }

    @Test
    void aServicesEnvIsPartOfItsRevision() throws Exception {
        String all = TestAuth.bearer("env-service");
        JsonObject body = new JsonObject().put("script", "export default async (config, ctx) => process.env.MODE;")
                .put("restart", "on-failure").put("env", new JsonObject().put("MODE", "first"));
        JsonObject created = put(all, "/v1/services/moded", body).bodyAsJsonObject();
        assertThat(created.getJsonObject("env").getString("MODE")).isEqualTo("first");
        assertThat(serviceOutput(all, "moded", 1)).isEqualTo("first");
        JsonObject changed = put(all, "/v1/services/moded", body.copy().put("env", new JsonObject().put("MODE", "second"))).bodyAsJsonObject();
        assertThat(changed.getInteger("revision")).as("a new env is a new revision").isEqualTo(2);
        assertThat(serviceOutput(all, "moded", 2)).isEqualTo("second");
    }

    private static JsonObject submitAndWait(String bearer, JsonObject body) throws Exception {
        HttpResponse<Buffer> submitted = post(bearer, "/v1/jobs", body.copy().put("timeoutMs", 20_000));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        String id = submitted.bodyAsJsonObject().getString("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject job;
        do {
            Thread.sleep(50);
            job = get(bearer, "/v1/jobs/" + id).bodyAsJsonObject();
        } while (List.of("QUEUED", "RUNNING").contains(job.getString("status")) && System.nanoTime() < deadline);
        return job;
    }

    private static HttpResponse<Buffer> post(String bearer, String path, JsonObject body) throws Exception {
        return await(client.post(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).sendJsonObject(body));
    }

    /** A token for the tenant with only {@code scopes}, minted with {@code bearer}. */
    private static String token(String bearer, String... scopes) throws Exception {
        HttpResponse<Buffer> minted = await(client.post(node.httpPort(), "localhost", "/v1/tokens").putHeader("Authorization", bearer)
                .sendJsonObject(new JsonObject().put("name", "narrow").put("scopes", new JsonArray(List.of(scopes)))));
        assertThat(minted.statusCode()).as(minted.bodyAsString()).isEqualTo(201);
        return "Bearer " + minted.bodyAsJsonObject().getString("token");
    }

    /** The output of the service's run of {@code revision}, once it has ended. */
    private static Object serviceOutput(String bearer, String name, int revision) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonObject service = get(bearer, "/v1/services/" + name).bodyAsJsonObject();
            JsonObject exit = service.getJsonObject("lastExit");
            if (service.getBoolean("completed", false) && service.getInteger("revision") == revision && exit != null) {
                return get(bearer, "/v1/jobs/" + exit.getString("jobId")).bodyAsJsonObject().getValue("output");
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Service " + name + " revision " + revision + " never completed");
    }

    private static JsonObject run(String bearer, String script) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", bearer)
                .sendJsonObject(new JsonObject().put("script", script).put("timeoutMs", 20_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        String id = submitted.bodyAsJsonObject().getString("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject job;
        do {
            Thread.sleep(50);
            job = get(bearer, "/v1/jobs/" + id).bodyAsJsonObject();
        } while (List.of("QUEUED", "RUNNING").contains(job.getString("status")) && System.nanoTime() < deadline);
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        return job;
    }

    private static HttpResponse<Buffer> get(String bearer, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).send());
    }

    private static HttpResponse<Buffer> put(String bearer, String path, JsonObject body) throws Exception {
        return await(client.put(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).sendJsonObject(body));
    }

    private static HttpResponse<Buffer> delete(String bearer, String path) throws Exception {
        return await(client.delete(node.httpPort(), "localhost", path).putHeader("Authorization", bearer).send());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
