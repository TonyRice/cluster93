package automately.runner.protocol.runtime;

import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.LogSink;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.ScriptRuntime;
import automately.core.runtime.StateSink;
import automately.core.runtime.api.HostApis;
import automately.runner.protocol.server.NodeWorkerChannel;
import automately.runner.protocol.transport.StreamBridge;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes scripts in an out-of-process in-cluster tenant worker via binary framing IPC (D-127, worker-node-protocol.md).
 * Spawns the worker as a native binary (e.g. worker-javet / worker-graaljs) or falls back to a child JVM process.
 */
public class WorkerProcessRuntime implements ScriptRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerProcessRuntime.class);

    private final Vertx vertx;
    private final String binaryPath;
    private final int heapMb;
    private final AtomicInteger channelSequence = new AtomicInteger(1);

    public WorkerProcessRuntime(Vertx vertx) {
        this(vertx, System.getProperty("automately.worker.binary"), 128);
    }

    public WorkerProcessRuntime(Vertx vertx, String binaryPath, int heapMb) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.binaryPath = binaryPath;
        this.heapMb = Math.max(16, heapMb);
    }

    @Override
    public RunningExecution execute(Execution execution, LogSink logs, StateSink state, HostApis host) {
        int channelId = channelSequence.getAndIncrement();
        Promise<ExecutionResult> promise = Promise.promise();

        Process process;
        try {
            process = spawnWorkerProcess();
        } catch (Exception e) {
            return new FailedExecution(Future.failedFuture(e));
        }

        // Capture worker stderr for diagnostic logging
        pumpStderr(process.getErrorStream(), logs);

        WriteStream<io.vertx.core.buffer.Buffer> output = StreamBridge.writeStream(vertx, process.getOutputStream());
        ReadStream<io.vertx.core.buffer.Buffer> input = StreamBridge.readStream(vertx, process.getInputStream());

        String workerId = "worker-" + process.pid();
        NodeWorkerChannel channel = new NodeWorkerChannel(workerId, output, input);

        channel.awaitHello()
                .compose(hello -> channel.bind(execution.tenant(), new JsonObject().put("heapMb", heapMb), new JsonObject()))
                .compose(v -> channel.run(channelId, execution, host, logs, state))
                .onComplete(ar -> {
                    cleanupProcess(process);
                    if (ar.succeeded()) {
                        promise.complete(ar.result());
                    } else {
                        promise.complete(ExecutionResult.failed("Worker process error: " + ar.cause().getMessage()));
                    }
                });

        return new RunningExecution() {
            @Override
            public Future<ExecutionResult> result() {
                return promise.future();
            }

            @Override
            public void stop(String reason, Duration grace) {
                channel.stop(channelId, reason, grace.toMillis());
                vertx.setTimer(grace.toMillis() + 1000, id -> cleanupProcess(process));
            }

            @Override
            public void cancel(String reason) {
                channel.cancel(channelId, reason);
                cleanupProcess(process);
                promise.tryComplete(ExecutionResult.cancelled("Cancelled: " + reason));
            }
        };
    }

    private Process spawnWorkerProcess() throws Exception {
        List<String> command = new ArrayList<>();
        if (binaryPath != null && Files.isExecutable(Path.of(binaryPath))) {
            command.add(binaryPath);
            command.add("ipc");
            command.add("--heap-mb");
            command.add(String.valueOf(heapMb));
        } else {
            // Fallback to spawning child JVM
            String javaBin = ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
            String classPath = System.getProperty("java.class.path");
            command.add(javaBin);
            command.add("-cp");
            command.add(classPath);
            command.add("automately.worker.WorkerMain");
            command.add("ipc");
            command.add("--heap-mb");
            command.add(String.valueOf(heapMb));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        return pb.start();
    }

    private void pumpStderr(InputStream stderr, LogSink logs) {
        Thread.ofVirtual().name("worker-stderr-pump").start(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stderr))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (logs != null) {
                        logs.log("WARN", "[worker-stderr] " + line);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void cleanupProcess(Process process) {
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
            process.destroyForcibly();
        }
    }

    private record FailedExecution(Future<ExecutionResult> result) implements RunningExecution {
        @Override public void stop(String reason, Duration grace) {}
        @Override public void cancel(String reason) {}
    }
}
