package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nodes dying while files stream, on a three-node cluster with one backup copy of everything (milestone 3).
 * A download outlives the loss of a node holding its data; a download cut off with its serving node resumes on
 * another node with a Range request; an upload cut off with its node leaves nothing behind.
 */
class FileFailoverTest {

    private static final String TENANT = "acme";
    private static final int MB = 1024 * 1024;

    private final List<RunningNode> nodes = new ArrayList<>();
    private Vertx clientVertx;
    private WebClient client;
    private HttpClient raw;

    @BeforeEach
    void startCluster() throws Exception {
        for (String id : List.of("node-a", "node-b", "node-c")) {
            nodes.add(await(Automately.start(config(id))));
        }
        TestAuth.use(nodes.getFirst());
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
        raw = clientVertx.createHttpClient();
        awaitLiveNodes(nodes.getFirst(), 3);
    }

    @AfterEach
    void stopCluster() throws Exception {
        await(clientVertx.close());
        for (RunningNode node : nodes) {
            await(node.stop());
        }
    }

    @Test
    void aDownloadOutlivesANodeHoldingItsData() throws Exception {
        // Big enough that most of it is still in the store, not in socket buffers, when the node goes.
        byte[] data = bytes(40 * MB, 1);
        String etag = upload(nodes.get(0), "movie.mp4", data);
        RunningNode serving = nodes.get(2);

        Download download = new Download(serving, null, 2 * MB);
        download.paused.get(30, TimeUnit.SECONDS);
        // Every chunk has two copies on three nodes; losing node B leaves at least one of each.
        kill(nodes.get(1));
        download.resume.complete(null);
        Download.Result result = download.result.get(60, TimeUnit.SECONDS);

        assertThat(result.failure()).as("the download completed").isNull();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.headers().get("ETag")).isEqualTo(etag);
        assertThat(result.body().getBytes()).as("every byte, in order").isEqualTo(data);
    }

    @Test
    void aDownloadCutOffWithItsNodeResumesOnAnotherWithARange() throws Exception {
        byte[] data = bytes(24 * MB, 2);
        String etag = upload(nodes.get(0), "movie.mp4", data);
        RunningNode serving = nodes.get(2);

        Download first = new Download(serving, null, 6 * MB);
        first.paused.get(30, TimeUnit.SECONDS);
        kill(serving);
        first.resume.complete(null);
        Download.Result cut = first.result.get(60, TimeUnit.SECONDS);
        assertThat(cut.failure()).as("the connection to the dead node failed").isNotNull();
        int received = cut.body().length();
        assertThat(received).isBetween(6 * MB, data.length - 1);

        // What a player or download manager does: ask another node for the rest, and check it's the same file.
        Download rest = new Download(nodes.get(1), "bytes=" + received + "-", Long.MAX_VALUE);
        rest.resume.complete(null);
        Download.Result tail = rest.result.get(60, TimeUnit.SECONDS);

        assertThat(tail.failure()).isNull();
        assertThat(tail.status()).isEqualTo(206);
        assertThat(tail.headers().get("ETag")).as("the same version").isEqualTo(etag);
        assertThat(tail.headers().get("Content-Range")).isEqualTo("bytes " + received + "-" + (data.length - 1) + "/" + data.length);
        Buffer whole = cut.body().copy().appendBuffer(tail.body());
        assertThat(whole.getBytes()).isEqualTo(data);
    }

    @Test
    void anUploadCutOffWithItsNodeLeavesNothingBehind() throws Exception {
        byte[] previous = bytes(2 * MB, 3);
        String previousEtag = upload(nodes.get(0), "report.bin", previous);
        RunningNode receiving = nodes.get(2);

        // Stream a replacement slowly, then lose the node halfway through.
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.PUT).setHost("localhost").setPort(receiving.httpPort())
                .setURI("/v1/files/report.bin").putHeader("Authorization", TestAuth.bearer(TENANT)).putHeader("Content-Length", String.valueOf(16 * MB));
        HttpClientRequest request = await(raw.request(options));
        CompletableFuture<Throwable> failed = new CompletableFuture<>();
        request.exceptionHandler(failed::complete);
        request.response().onFailure(failed::complete);
        byte[] replacement = bytes(16 * MB, 4);
        for (int sent = 0; sent < 8 * MB; sent += 256 * 1024) {
            await(request.write(Buffer.buffer(Arrays.copyOfRange(replacement, sent, sent + 256 * 1024))));
        }
        Thread.sleep(300);
        kill(receiving);

        assertThat(failed.get(30, TimeUnit.SECONDS)).as("the client saw the upload fail").isNotNull();
        HttpResponse<Buffer> after = get(nodes.get(0), "/v1/files/report.bin");
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.getHeader("ETag")).as("still the previous version").isEqualTo(previousEtag);
        assertThat(after.body().getBytes()).isEqualTo(previous);
        JsonObject listed = get(nodes.get(1), "/v1/files").bodyAsJsonObject();
        assertThat(listed.getJsonArray("files")).hasSize(1);
    }

    // --- helpers ---

    /** A raw download that pauses once {@code pauseAt} bytes have arrived, until {@link #resume} completes. */
    private final class Download {

        record Result(int status, MultiMap headers, Buffer body, Throwable failure) {
        }

        final CompletableFuture<Void> paused = new CompletableFuture<>();
        final CompletableFuture<Void> resume = new CompletableFuture<>();
        final CompletableFuture<Result> result = new CompletableFuture<>();

        Download(RunningNode node, String range, long pauseAt) {
            RequestOptions options = new RequestOptions().setHost("localhost").setPort(node.httpPort()).setURI("/v1/files/movie.mp4")
                    .putHeader("Authorization", TestAuth.bearer(TENANT));
            if (range != null) {
                options.putHeader("Range", range);
            }
            raw.request(options).compose(HttpClientRequest::send).onFailure(result::completeExceptionally).onSuccess(response -> {
                Buffer body = Buffer.buffer();
                boolean[] pausedOnce = {false};
                response.handler(chunk -> {
                    body.appendBuffer(chunk);
                    if (!pausedOnce[0] && body.length() >= pauseAt) {
                        pausedOnce[0] = true;
                        response.pause();
                        paused.complete(null);
                        io.vertx.core.Context context = clientVertx.getOrCreateContext();
                        resume.thenRun(() -> context.runOnContext(v -> response.resume()));
                    }
                });
                response.endHandler(v -> result.complete(new Result(response.statusCode(), response.headers(), body, null)));
                response.exceptionHandler(err -> result.complete(new Result(response.statusCode(), response.headers(), body, err)));
                if (pauseAt == Long.MAX_VALUE) {
                    paused.complete(null);
                }
            });
        }
    }

    private void kill(RunningNode victim) throws Exception {
        nodes.remove(victim);
        await(victim.stop());
        awaitLiveNodes(nodes.getFirst(), nodes.size());
    }

    private String upload(RunningNode node, String path, byte[] data) throws Exception {
        HttpResponse<Buffer> created = await(client.put(node.httpPort(), "localhost", "/v1/files/" + path)
                .putHeader("Authorization", TestAuth.bearer(TENANT)).sendBuffer(Buffer.buffer(data)));
        assertThat(created.statusCode()).as(created.bodyAsString()).isEqualTo(201);
        assertThat(created.getHeader("ETag")).isEqualTo(etag(data));
        return created.getHeader("ETag");
    }

    private HttpResponse<Buffer> get(RunningNode node, String path) throws Exception {
        return await(client.get(node.httpPort(), "localhost", path).putHeader("Authorization", TestAuth.bearer(TENANT)).send());
    }

    private void awaitLiveNodes(RunningNode node, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<Buffer> response = get(node, "/v1/nodes");
            if (response.statusCode() == 200 && response.bodyAsJsonObject().getJsonArray("nodes").size() == count) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The cluster never had " + count + " live nodes");
    }

    private static ServerConfig config(String nodeId) {
        return ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_DATA_BACKUPS", "1"),
                Map.entry("AUTOMATELY_CAPACITY", "2"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "128"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_CLUSTERED", "true"),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "29701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1"))));
    }

    private static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static String etag(byte[] data) throws Exception {
        return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)) + "\"";
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }
}
