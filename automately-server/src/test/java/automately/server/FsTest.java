package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The fs module in scripts (D-079): the tenant's files as one tree, the job's /tmp, sync calls, Node's errors. */
class FsTest {

    private static final String TENANT = "fsuser";
    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Automately.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", "fs"),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "4"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64")))));
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
    void scriptsReadAndWriteTheTreeWithPromisesAndSyncCalls() throws Exception {
        JsonObject output = run("""
                import { readFileSync, writeFileSync, existsSync } from 'fs';
                import { mkdir, readdir, readFile, rename, stat } from 'node:fs/promises';
                export default async () => {
                  await mkdir('/reports/2026', { recursive: true });
                  writeFileSync('/reports/2026/q1.csv', 'a,b\\n1,2');
                  fs.appendFileSync('reports/2026/q1.csv', '\\n3,4');
                  await rename('/reports/2026/q1.csv', '/reports/2026/first.csv');
                  const text = readFileSync('/reports/2026/first.csv', 'utf8');
                  const listing = await readdir('/reports', { withFileTypes: true });
                  const s = await stat('/reports/2026/first.csv');
                  return {
                    text, size: s.size, isFile: s.isFile(),
                    listing: listing.map((d) => d.name + (d.isDirectory() ? '/' : '')),
                    gone: existsSync('/reports/2026/q1.csv'),
                    bytes: (await readFile('/reports/2026/first.csv')).length,
                  };
                };
                """);
        assertThat(output.getString("text")).isEqualTo("a,b\n1,2\n3,4");
        assertThat(output.getInteger("size")).isEqualTo(11);
        assertThat(output.getBoolean("isFile")).isTrue();
        assertThat(output.getJsonArray("listing").getList()).containsExactly("2026/");
        assertThat(output.getBoolean("gone")).isFalse();
        assertThat(output.getInteger("bytes")).isEqualTo(11);
        // The same file, through the REST API.
        HttpResponse<Buffer> fromApi = await(client.get(node.httpPort(), "localhost", "/v1/files/reports/2026/first.csv")
                .putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        assertThat(fromApi.bodyAsString()).isEqualTo("a,b\n1,2\n3,4");
    }

    @Test
    void errorsAreNodes() throws Exception {
        JsonObject output = run("""
                export default async () => {
                  const codes = [];
                  const trySync = (f) => { try { f(); codes.push('ok'); } catch (e) { codes.push(e.code); } };
                  trySync(() => fs.readFileSync('/missing.txt'));
                  fs.mkdirSync('/dir');
                  trySync(() => fs.mkdirSync('/dir'));
                  fs.writeFileSync('/dir/f', 'x');
                  trySync(() => fs.rmdirSync('/dir'));
                  trySync(() => fs.writeFileSync('/dir/f/inner', 'x'));
                  trySync(() => fs.writeFileSync('/packages/x.js', 'x'));
                  try { await fs.promises.unlink('/nope'); } catch (e) { codes.push(e.code + ':' + e.syscall); }
                  fs.rmSync('/dir', { recursive: true });
                  fs.rmSync('/dir', { force: true });
                  codes.push(String(fs.existsSync('/dir')));
                  return codes;
                };
                """);
        assertThat(output.getJsonArray("codes", output.getJsonArray("value")).getList())
                .containsExactly("ENOENT", "EEXIST", "ENOTEMPTY", "ENOTDIR", "EACCES", "ENOENT:unlink", "false");
    }

    @Test
    void tmpIsTheJobsOwnAndGoneAfterwards() throws Exception {
        JsonObject first = run("""
                export default async () => {
                  fs.writeFileSync('/tmp/scratch.txt', 'mine');
                  fs.mkdirSync('/tmp/work/deep', { recursive: true });
                  await fs.promises.copyFile('/tmp/scratch.txt', '/kept.txt');
                  return { here: fs.readFileSync('/tmp/scratch.txt', 'utf8'), root: fs.readdirSync('/').includes('tmp') };
                };
                """);
        assertThat(first.getString("here")).isEqualTo("mine");
        assertThat(first.getBoolean("root")).isTrue();

        JsonObject second = run("""
                export default async () => ({ tmp: fs.existsSync('/tmp/scratch.txt'), kept: fs.readFileSync('/kept.txt', 'utf8') });
                """);
        assertThat(second.getBoolean("tmp")).as("another job's /tmp").isFalse();
        assertThat(second.getString("kept")).isEqualTo("mine");
        HttpResponse<Buffer> listed = await(client.get(node.httpPort(), "localhost", "/v1/dirs").putHeader("Authorization", TestAuth.bearer(TENANT)).send());
        assertThat(listed.bodyAsString()).as("/tmp never reaches the store").doesNotContain("\"tmp\"");
    }

    /** Runs a script for the tenant; its output, wrapped as {@code {value}} when it isn't an object. */
    private static JsonObject run(String script) throws Exception {
        HttpResponse<Buffer> submitted = await(client.post(node.httpPort(), "localhost", "/v1/jobs").putHeader("Authorization", TestAuth.bearer(TENANT))
                .sendJsonObject(new JsonObject().put("script", script).put("timeoutMs", 20_000)));
        assertThat(submitted.statusCode()).as(submitted.bodyAsString()).isEqualTo(202);
        String id = submitted.bodyAsJsonObject().getString("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonObject job;
        do {
            Thread.sleep(50);
            job = await(client.get(node.httpPort(), "localhost", "/v1/jobs/" + id).putHeader("Authorization", TestAuth.bearer(TENANT)).send())
                    .bodyAsJsonObject();
        } while (List.of("QUEUED", "RUNNING").contains(job.getString("status")) && System.nanoTime() < deadline);
        assertThat(job.getString("status")).as(job.encodePrettily()).isEqualTo("SUCCEEDED");
        Object output = job.getValue("output");
        return output instanceof JsonObject object ? object : new JsonObject().put("value", output);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
