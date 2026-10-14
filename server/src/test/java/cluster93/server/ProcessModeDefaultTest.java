package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that Cluster93 defaults to out-of-process worker execution ({@code C93_WORKER_MODE=process}) and
 * boots in process mode when a native worker binary is present (D-129).
 */
class ProcessModeDefaultTest {

    @Test
    @DisplayName("ServerConfig defaults worker mode to process")
    void defaultsToProcessMode() {
        assertEquals("process", ServerConfig.fromEnv(Map.of()).workerMode());
    }

    @Test
    @DisplayName("ServerConfig defaults the worker engine to graaljs")
    void defaultsWorkerEngineToGraaljs() {
        assertEquals("graaljs", ServerConfig.fromEnv(Map.of()).workerDefaultMode());
    }

    @Test
    @DisplayName("The default worker binary points to graaljs when it is the default mode")
    void defaultBinaryPointsToGraaljs() {
        String graaljs = cluster93.runner.protocol.pool.TenantWorkerPool.resolvePath("bin/worker-graaljs");
        Assumptions.assumeTrue(graaljs != null, "no bin/worker-graaljs present; skipping default-binary assertion");

        ServerConfig config = ServerConfig.fromEnv(Map.of());
        assertThat(config.workerDefaultMode()).isEqualTo("graaljs");
        assertThat(config.workerBinaries().get("graaljs")).isEqualTo(graaljs);
        assertThat(config.workerBinaries().get("default")).isEqualTo(graaljs);
    }

    @Test
    @DisplayName("An explicit javet default mode switches the default worker engine")
    void explicitJavetDefaultModeIsHonoured() {
        ServerConfig config = ServerConfig.fromEnv(Map.of("C93_WORKER_DEFAULT_MODE", "javet"));
        assertThat(config.workerDefaultMode()).isEqualTo("javet");
    }

    @Test
    @DisplayName("An explicit embedded mode is honoured")
    void embeddedModeIsHonoured() {
        assertEquals("embedded", ServerConfig.fromEnv(Map.of("C93_WORKER_MODE", "embedded")).workerMode());
    }

    @Test
    @DisplayName("The server boots in process mode when a native worker binary is present")
    void serverStartsInProcessModeWhenNativeBinaryPresent() throws Exception {
        String binary = cluster93.runner.protocol.pool.TenantWorkerPool.resolveBinary(null, "javet");
        Assumptions.assumeTrue(binary != null, "no native worker binary present; skipping process-mode boot test");

        Map<String, String> env = new HashMap<>();
        env.put("C93_NODE_ID", "process-default");
        env.put("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN);
        env.put("C93_RATE_LIMIT_PER_MINUTE", "0");
        env.put("C93_METRICS_PORT", "0");
        env.put("C93_HTTP_PORT", "0");
        env.put("C93_INGRESS_PORT", "0");
        env.put("C93_INTERNAL_PORT", "0");
        env.put("C93_ADVERTISE_HOST", "127.0.0.1");
        env.put("C93_MAX_HEAP_MB", "64");

        ServerConfig config = ServerConfig.fromEnv(env);
        assertThat(config.workerMode()).isEqualTo("process");
        assertThat(config.workerBinaries().get("javet")).isEqualTo(binary);

        RunningNode node = await(Cluster93.start(config));
        try {
            assertThat(node.httpPort()).isPositive();
        } finally {
            await(node.stop());
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
