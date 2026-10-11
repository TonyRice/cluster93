package automately.runner.protocol.runtime;

import automately.core.job.JobStatus;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.LogSink;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.ScriptRuntime;
import automately.core.runtime.StateSink;
import automately.core.runtime.api.HostApis;
import automately.runner.protocol.pool.TenantWorkerPool;
import automately.runner.protocol.pool.TenantWorkerPoolManager;
import automately.runner.protocol.pool.WorkerProcessHandle;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes scripts in an out-of-process in-cluster tenant worker via binary framing IPC (D-127, worker-node-protocol.md).
 * Spawns the worker as a native binary (e.g. worker-javet / worker-graaljs) or falls back to a child JVM process.
 */
public class WorkerProcessRuntime implements ScriptRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerProcessRuntime.class);

    private final Vertx vertx;
    private final String binaryPath;
    private final int heapMb;
    private final TenantWorkerPool pool;
    private final TenantWorkerPoolManager poolManager;
    private final AtomicInteger channelSequence = new AtomicInteger(1);

    public WorkerProcessRuntime(Vertx vertx) {
        this(vertx, System.getProperty("automately.worker.binary"), 128);
    }

    public WorkerProcessRuntime(Vertx vertx, TenantWorkerPool pool) {
        this(vertx, null, 128, pool, null);
    }

    public WorkerProcessRuntime(Vertx vertx, TenantWorkerPoolManager poolManager) {
        this(vertx, null, 128, null, poolManager);
    }

    public WorkerProcessRuntime(Vertx vertx, String binaryPath, int heapMb) {
        this(vertx, binaryPath, heapMb, null, null);
    }

    public WorkerProcessRuntime(Vertx vertx, String binaryPath, int heapMb, TenantWorkerPool pool) {
        this(vertx, binaryPath, heapMb, pool, null);
    }

    public WorkerProcessRuntime(Vertx vertx, String binaryPath, int heapMb, TenantWorkerPool pool, TenantWorkerPoolManager poolManager) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.binaryPath = binaryPath;
        this.heapMb = Math.max(16, heapMb);
        this.pool = pool;
        this.poolManager = poolManager;
    }

    @Override
    public RunningExecution execute(Execution execution, LogSink logs, StateSink state, HostApis host) {
        int channelId = channelSequence.getAndIncrement();
        Promise<ExecutionResult> promise = Promise.promise();

        if (poolManager != null) {
            AtomicReference<WorkerProcessHandle> handleRef = new AtomicReference<>();
            poolManager.acquire(execution.tenant(), null).onComplete(ar -> {
                if (ar.failed()) {
                    promise.complete(ExecutionResult.failed("Worker pool failure: " + ar.cause().getMessage()));
                    return;
                }
                WorkerProcessHandle handle = ar.result();
                handleRef.set(handle);
                pumpStderr(handle.process().getErrorStream(), logs);

                handle.run(channelId, execution, host, logs, state)
                        .onComplete(runRes -> {
                            boolean isError = runRes.failed() || (runRes.succeeded() && runRes.result().status() != JobStatus.SUCCEEDED);
                            poolManager.release(execution.tenant(), null, handle, isError);
                            if (runRes.succeeded()) {
                                promise.complete(runRes.result());
                            } else {
                                promise.complete(ExecutionResult.failed("Worker error: " + runRes.cause().getMessage()));
                            }
                        });
            });

            return new RunningExecution() {
                @Override
                public Future<ExecutionResult> result() {
                    return promise.future();
                }

                @Override
                public void stop(String reason, Duration grace) {
                    WorkerProcessHandle handle = handleRef.get();
                    if (handle != null) {
                        handle.stop(channelId, reason, grace.toMillis());
                    }
                }

                @Override
                public void cancel(String reason) {
                    WorkerProcessHandle handle = handleRef.get();
                    if (handle != null) {
                        handle.cancel(channelId, reason);
                        poolManager.release(execution.tenant(), null, handle, true);
                    }
                    promise.tryComplete(ExecutionResult.cancelled("Cancelled: " + reason));
                }
            };
        }

        if (pool != null) {
            AtomicReference<WorkerProcessHandle> handleRef = new AtomicReference<>();
            pool.acquire(execution.tenant()).onComplete(ar -> {
                if (ar.failed()) {
                    promise.complete(ExecutionResult.failed("Worker pool failure: " + ar.cause().getMessage()));
                    return;
                }
                WorkerProcessHandle handle = ar.result();
                handleRef.set(handle);
                pumpStderr(handle.process().getErrorStream(), logs);

                handle.run(channelId, execution, host, logs, state)
                        .onComplete(runRes -> {
                            boolean isError = runRes.failed() || (runRes.succeeded() && runRes.result().status() != JobStatus.SUCCEEDED);
                            pool.release(handle, isError);
                            if (runRes.succeeded()) {
                                promise.complete(runRes.result());
                            } else {
                                promise.complete(ExecutionResult.failed("Worker error: " + runRes.cause().getMessage()));
                            }
                        });
            });

            return new RunningExecution() {
                @Override
                public Future<ExecutionResult> result() {
                    return promise.future();
                }

                @Override
                public void stop(String reason, Duration grace) {
                    WorkerProcessHandle handle = handleRef.get();
                    if (handle != null) {
                        handle.stop(channelId, reason, grace.toMillis());
                    }
                }

                @Override
                public void cancel(String reason) {
                    WorkerProcessHandle handle = handleRef.get();
                    if (handle != null) {
                        handle.cancel(channelId, reason);
                        pool.release(handle, true);
                    }
                    promise.tryComplete(ExecutionResult.cancelled("Cancelled: " + reason));
                }
            };
        }

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
