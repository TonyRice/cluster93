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
import cluster93.runner.protocol.pool.WorkerExit;
import cluster93.runner.protocol.pool.WorkerProcessHandle;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import cluster93.runner.protocol.transport.StreamBridge;
import io.vertx.core.Context;
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
                            // A run that produced a result is a run-level event, whatever its status: a failed,
                            // timed-out or cancelled job frees only its own slot and leaves a shared worker alive.
                            // Only a run that never produced a result — the usual sign of a dead worker or a broken
                            // channel — retires the worker itself (D-162). Either way the failure is re-labelled
                            // with the worker's exit cause when the process is gone, so the tenant is told why
                            // (D-159) rather than that the pipe ended.
                            if (runRes.succeeded()) {
                                poolManager.release(execution.tenant(), null, handle);
                                completeRun(handle, runRes.result(), promise);
                            } else {
                                String death = WorkerExit.runFailure(handle.process(), runRes.cause());
                                poolManager.poison(execution.tenant(), null, handle, "run failed: " + death);
                                completeRun(handle, ExecutionResult.failed(death), promise);
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
                        // Ask the worker to stop the run; its slot is freed when the run actually completes, so a
                        // sibling job cannot reuse the worker before this run's context is gone. Releasing here as
                        // well would count the slot down twice and, before D-155, reaped the shared worker.
                        handle.cancel(channelId, reason);
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
                            // See the poolManager branch: a job's outcome is a run-level event; only a run that never
                            // produced a result retires the worker (D-162), and a failure names the worker's exit
                            // cause when the process is gone (D-159).
                            if (runRes.succeeded()) {
                                pool.release(handle);
                                completeRun(handle, runRes.result(), promise);
                            } else {
                                String death = WorkerExit.runFailure(handle.process(), runRes.cause());
                                pool.poison(handle, "run failed: " + death);
                                completeRun(handle, ExecutionResult.failed(death), promise);
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
                        // The slot is freed when the run completes; see the poolManager branch.
                        handle.cancel(channelId, reason);
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
                    // Decide the outcome while the process is still observable: cleanupProcess destroys it just below,
                    // and a process we destroyed ourselves must not be read as a worker that crashed (D-159).
                    ExecutionResult result = ar.succeeded()
                            ? attributeWorkerDeath(process, ar.result())
                            : ExecutionResult.failed(WorkerExit.runFailure(process, ar.cause()));
                    cleanupProcess(process);
                    promise.complete(result);
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

    /**
     * Attributes a failed run to its worker's exit cause when the process is gone (D-159). A script that merely threw
     * keeps its own error: only a run whose status is {@code FAILED} <em>and</em> whose worker has died is re-labelled,
     * and then the worker's exit code is what names it ("killed: …", "crashed: …"). Package-private so the rule can be
     * tested without spawning a process.
     */
    static ExecutionResult attributeWorkerDeath(Process process, ExecutionResult result) {
        if (result.status() != JobStatus.FAILED) {
            return result;
        }
        String exit = WorkerExit.describe(process);
        return exit == null ? result : ExecutionResult.failed("Worker " + exit);
    }

    /** How long to wait for a dead worker's exit status before falling back to the run's own cause (D-159). */
    static final long WORKER_EXIT_WAIT_MS = 2_000L;

    /**
     * Completes a run. A failure on a worker whose channel has died is attributed to the worker's exit cause (D-159);
     * anything else — a success, or a script that failed on a live worker — completes at once with its own outcome.
     */
    private void completeRun(WorkerProcessHandle handle, ExecutionResult result, Promise<ExecutionResult> promise) {
        if (result.status() == JobStatus.FAILED && handle.channel().isBroken()) {
            awaitWorkerExitAndComplete(handle.process(), result, promise);
        } else {
            promise.complete(result);
        }
    }

    /**
     * Completes the run once the worker has really exited, naming its exit cause. The status is read from the process
     * reaper, not the instant the pipe ends: the EOF and the process exit are observed on different threads, so
     * reading {@code exitValue()} the moment the channel reports the death is exactly how the cause gets lost. A
     * worker whose pipe has closed is already exiting, so this is prompt; the timer is only a guard, so a broken
     * channel on a process that somehow lives cannot hang the job.
     */
    private void awaitWorkerExitAndComplete(Process process, ExecutionResult failure, Promise<ExecutionResult> promise) {
        Context context = Vertx.currentContext();
        AtomicBoolean settled = new AtomicBoolean(false);
        long timerId = vertx.setTimer(WORKER_EXIT_WAIT_MS, id -> {
            if (settled.compareAndSet(false, true)) {
                completeOn(context, promise, failure);
            }
        });
        process.onExit().whenComplete((exited, error) -> {
            if (settled.compareAndSet(false, true)) {
                vertx.cancelTimer(timerId);
                completeOn(context, promise, attributeWorkerDeath(exited, failure));
            }
        });
    }

    /** Completes on the run's own Vert.x context when there is one, so the caller is not resumed on the reaper thread. */
    private static void completeOn(Context context, Promise<ExecutionResult> promise, ExecutionResult result) {
        if (context != null && Vertx.currentContext() != context) {
            context.runOnContext(v -> promise.tryComplete(result));
        } else {
            promise.tryComplete(result);
        }
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
