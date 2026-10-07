package automately.server;

import automately.server.Automately.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant disk providers on two nodes (D-057): only when both see the same AUTOMATELY_DISK_ROOT, since either node
 * may serve any file. Different folders turn disk providers off; one shared folder works across nodes.
 */
class SharedDiskRootTest {

    @TempDir
    Path work;

    private RunningNode nodeA;
    private RunningNode nodeB;
    private Vertx clientVertx;
    private WebClient client;

    @BeforeEach
    void client() {
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterEach
    void stop() throws Exception {
        await(clientVertx.close());
        if (nodeB != null) {
            await(nodeB.stop());
        }
        if (nodeA != null) {
            await(nodeA.stop());
        }
    }

    private static ServerConfig config(String nodeId, Path diskRoot) {
        return ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("AUTOMATELY_NODE_ID", nodeId),
                Map.entry("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("AUTOMATELY_METRICS_PORT", "0"),
                Map.entry("AUTOMATELY_DISK_ROOT", diskRoot.toString()),
                Map.entry("AUTOMATELY_HTTP_PORT", "0"),
                Map.entry("AUTOMATELY_INGRESS_PORT", "0"),
                Map.entry("AUTOMATELY_INTERNAL_PORT", "0"),
                Map.entry("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("AUTOMATELY_CAPACITY", "1"),
                Map.entry("AUTOMATELY_MAX_HEAP_MB", "64"),
                Map.entry("AUTOMATELY_HEARTBEAT_MS", "200"),
                Map.entry("AUTOMATELY_CLUSTERED", "true"),
                Map.entry("AUTOMATELY_CLUSTER_PORT", "31701"),
                Map.entry("AUTOMATELY_CLUSTER_MEMBERS", "127.0.0.1"))));
    }

    private HttpResponse<Buffer> putDiskProvider(RunningNode node, String bearer) throws Exception {
        return await(client.put(node.httpPort(), "localhost", "/v1/storage/providers/local").putHeader("Authorization", bearer)
                .sendJsonObject(new JsonObject().put("type", "disk")));
    }

    private String tenantWithDiskAllowed() throws Exception {
        TestAuth.use(nodeA);
        String bearer = TestAuth.bearer("acme");
        HttpResponse<Buffer> policy = await(client.put(nodeA.httpPort(), "localhost", "/v1/tenants/acme/storage-policy")
                .putHeader("Authorization", TestAuth.ADMIN)
                .sendJsonObject(new JsonObject().put("allow", new JsonArray().add("disk")).put("diskAllotmentMb", 10)));
        assertThat(policy.statusCode()).isEqualTo(200);
        return bearer;
    }

    @Test
    void differentFoldersOnTheNodesTurnDiskProvidersOff() throws Exception {
        nodeA = await(Automately.start(config("node-a", work.resolve("a"))));
        nodeB = await(Automately.start(config("node-b", work.resolve("b"))));
        String bearer = tenantWithDiskAllowed();

        // Each node notices within a few checks that the other's marker isn't in its folder.
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        HttpResponse<Buffer> response;
        do {
            Thread.sleep(500);
            response = putDiskProvider(nodeA, bearer);
        } while (response.statusCode() != 409 && System.nanoTime() < deadline);

        assertThat(response.statusCode()).as(response.bodyAsString()).isEqualTo(409);
        assertThat(response.bodyAsString()).contains("isn't the same folder on every node");
    }

    @Test
    void oneSharedFolderServesFilesFromEitherNode() throws Exception {
        Path shared = work.resolve("shared");
        nodeA = await(Automately.start(config("node-a", shared)));
        nodeB = await(Automately.start(config("node-b", shared)));
        String bearer = tenantWithDiskAllowed();
        // Let both nodes check after they've both joined.
        Thread.sleep(11_000);

        assertThat(putDiskProvider(nodeA, bearer).statusCode()).isEqualTo(201);
        assertThat(await(client.put(nodeA.httpPort(), "localhost", "/v1/storage/routes").putHeader("Authorization", bearer)
                .sendJsonObject(new JsonObject().put("default", "local"))).statusCode()).isEqualTo(200);

        HttpResponse<Buffer> stored = await(client.put(nodeA.httpPort(), "localhost", "/v1/files/report.txt").putHeader("Authorization", bearer)
                .sendBuffer(Buffer.buffer("on the shared disk")));
        assertThat(stored.statusCode()).as(stored.bodyAsString()).isEqualTo(201);
        assertThat(await(client.get(nodeB.httpPort(), "localhost", "/v1/files/report.txt").putHeader("Authorization", bearer).send())
                .bodyAsString()).isEqualTo("on the shared disk");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
