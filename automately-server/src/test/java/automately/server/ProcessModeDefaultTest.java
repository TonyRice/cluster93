package automately.server;

import automately.server.Automately.RunningNode;
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
 * Verifies that Automately defaults to out-of-process worker execution ({@code AUTOMATELY_WORKER_MODE=process}) and
 * boots in process mode when a native worker binary is present (D-129).
 */
class ProcessModeDefaultTest {

    @Test
    @DisplayName("ServerConfig defaults worker mode to process")
    void defaultsToProcessMode() {
        assertEquals("process", ServerConfig.fromEnv(Map.of()).workerMode());
    }

    @Test
    @DisplayName("An explicit embedded mode is honoured")
    void embeddedModeIsHonoured() {
        assertEquals("embedded", ServerConfig.fromEnv(Map.of("AUTOMATELY_WORKER_MODE", "embedded")).workerMode());
    }

    @Test
    @DisplayName("The server boots in process mode when a native worker binary is present")
    void serverStartsInProcessModeWhenNativeBinaryPresent() throws Exception {
        String binary = automately.runner.protocol.pool.TenantWorkerPool.resolveBinary(null, "javet");
        Assumptions.assumeTrue(binary != null, "no native worker binary present; skipping process-mode boot test");

        Map<String, String> env = new HashMap<>();
        env.put("AUTOMATELY_NODE_ID", "process-default");
        env.put("AUTOMATELY_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN);
        env.put("AUTOMATELY_RATE_LIMIT_PER_MINUTE", "0");
        env.put("AUTOMATELY_METRICS_PORT", "0");
        env.put("AUTOMATELY_HTTP_PORT", "0");
        env.put("AUTOMATELY_INGRESS_PORT", "0");
        env.put("AUTOMATELY_INTERNAL_PORT", "0");
        env.put("AUTOMATELY_ADVERTISE_HOST", "127.0.0.1");
        env.put("AUTOMATELY_MAX_HEAP_MB", "64");

        ServerConfig config = ServerConfig.fromEnv(env);
        assertThat(config.workerMode()).isEqualTo("process");
        assertThat(config.workerBinaries().get("javet")).isEqualTo(binary);

        RunningNode node = await(Automately.start(config));
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
