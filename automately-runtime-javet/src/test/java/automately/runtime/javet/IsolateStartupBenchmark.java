package automately.runtime.javet;

import automately.core.runtime.JobContext;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.StateSink;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a job's isolate costs to start (D-024, D-106): a fresh isolate, the prelude and a trivial script, end to end, on
 * one thread after a warm-up. Not part of the build; run it with {@code AUTOMATELY_BENCH=1} to decide what the prelude
 * can afford.
 */
@EnabledIfEnvironmentVariable(named = "AUTOMATELY_BENCH", matches = ".+")
class IsolateStartupBenchmark {

    private static final int WARMUP = 100;
    private static final int RUNS = 500;

    @Test
    void startUp() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            JavetScriptRuntime runtime = new JavetScriptRuntime(vertx, 64, 1);
            Map<String, String> scripts = new LinkedHashMap<>();
            scripts.put("trivial", "export default () => 1;");
            scripts.put("uses globals", "export default () => [typeof URL, typeof AbortController, typeof structuredClone].length;");
            scripts.put("imports crypto", "import { createHash } from 'node:crypto'; export default () => createHash('sha256').update('x').digest('hex');");
            scripts.put("imports stream", "import { Readable } from 'node:stream'; export default () => typeof Readable;");
            scripts.put("imports zlib", "import { gzipSync } from 'node:zlib'; export default () => gzipSync('x').length;");
            for (Map.Entry<String, String> script : scripts.entrySet()) {
                for (int i = 0; i < WARMUP; i++) {
                    run(runtime, script.getValue());
                }
                long[] nanos = new long[RUNS];
                for (int i = 0; i < RUNS; i++) {
                    long start = System.nanoTime();
                    run(runtime, script.getValue());
                    nanos[i] = System.nanoTime() - start;
                }
                Arrays.sort(nanos);
                System.out.printf("BENCH %-15s p50 %6.2f ms  p90 %6.2f ms  p99 %6.2f ms%n", script.getKey(),
                        nanos[RUNS / 2] / 1e6, nanos[RUNS * 9 / 10] / 1e6, nanos[RUNS * 99 / 100] / 1e6);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static void run(JavetScriptRuntime runtime, String script) throws Exception {
        Execution execution = new Execution("job-1", "tenant-a", script, new JsonObject(), Duration.ofSeconds(5),
                JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        ExecutionResult result = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD)
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(result.error()).isNull();
    }
}
