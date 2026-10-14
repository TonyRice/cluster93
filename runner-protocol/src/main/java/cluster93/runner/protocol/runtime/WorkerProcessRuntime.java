package cluster93.runner.protocol.runtime;

import cluster93.core.job.JobStatus;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.LogSink;
import cluster93.core.runtime.RunningExecution;
import cluster93.core.runtime.ScriptRuntime;
import cluster93.core.runtime.StateSink;
import cluster93.core.runtime.api.HostApis;
import cluster93.runner.protocol.pool.TenantWorkerPool;
import cluster93.runner.protocol.pool.TenantWorkerPoolManager;
import cluster93.runner.protocol.pool.WorkerProcessHandle;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import cluster93.runner.protocol.transport.StreamBridge;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
        this(vertx, System.getProperty("cluster93.worker.binary"), 128);
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
        long start = System.nanoTime();

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
                cluster93.core.metrics.Metrics.of(vertx).isolateStarted(Duration.ofNanos(System.nanoTime() - start));

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
                cluster93.core.metrics.Metrics.of(vertx).isolateStarted(Duration.ofNanos(System.nanoTime() - start));

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

        // A worker that never says hello (it died, or stalled) must not leave the run pending forever (D-135).
        AtomicBoolean settled = new AtomicBoolean(false);
        long helloTimeout = Math.max(1L, Long.getLong("cluster93.worker.helloTimeoutMs", TenantWorkerPool.DEFAULT_HELLO_TIMEOUT_MS));
        long timerId = vertx.setTimer(helloTimeout, id -> {
            if (settled.compareAndSet(false, true)) {
                cleanupProcess(process);
                promise.tryComplete(ExecutionResult.failed("Worker didn't say hello within " + helloTimeout + " ms"));
            }
        });
        channel.awaitHello()
                .onSuccess(hello -> cluster93.core.metrics.Metrics.of(vertx).isolateStarted(Duration.ofNanos(System.nanoTime() - start)))
                .compose(hello -> channel.bind(execution.tenant(), new JsonObject().put("heapMb", heapMb), new JsonObject()))
                .compose(v -> channel.run(channelId, execution, host, logs, state))
                .onComplete(ar -> {
                    if (settled.compareAndSet(false, true)) {
                        vertx.cancelTimer(timerId);
                    }
                    cleanupProcess(process);
                    if (ar.succeeded()) {
                        promise.complete(ar.result());
                    } else {
                        promise.complete(ExecutionResult.failed("Worker process error: "
                                + (ar.cause() == null ? "worker failed" : ar.cause().getMessage())));
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
        String resolvedBinary = (binaryPath != null && !binaryPath.isBlank())
                ? TenantWorkerPool.resolveBinary(binaryPath, "javet")
                : null;
        if (resolvedBinary == null) {
            resolvedBinary = TenantWorkerPool.resolveBinary(null, "javet");
        }
        if (resolvedBinary != null) {
            command.add(resolvedBinary);
            command.add("ipc");
            command.add("--heap-mb");
            command.add(String.valueOf(heapMb));
        } else {
            // Fallback to spawning child JVM
            String javaBin = ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
            command.add(javaBin);
            command.add("-cp");
            command.add(TenantWorkerPool.resolveWorkerClasspath());
            command.add("cluster93.worker.WorkerMain");
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
