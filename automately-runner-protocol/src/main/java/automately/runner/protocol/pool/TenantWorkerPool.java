package automately.runner.protocol.pool;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervises a pool of isolated, warm tenant worker processes (D-127, §7).
 * Pre-spawns workers in UNBOUND state and binds them to tenants on demand, enforcing cgroup v2 memory limits.
 */
public class TenantWorkerPool {

    private static final Logger LOG = LoggerFactory.getLogger(TenantWorkerPool.class);

    public enum ReusePolicy {
        FRESH_PER_JOB,
        REUSE_SAME_TENANT
    }

    private final Vertx vertx;
    private final String binaryPath;
    private final int minWarm;
    private final int maxTotal;
    private final int heapMb;
    private final ReusePolicy reusePolicy;
    private final CgroupMemoryLimiter cgroupLimiter;
    private final automately.runner.protocol.server.NodeRpcAuthorizer authorizer;

    private final Queue<WorkerProcessHandle> warmUnboundQueue = new ArrayDeque<>();
    private final Map<String, List<WorkerProcessHandle>> tenantWarmQueue = new ConcurrentHashMap<>();
    private final Map<Long, WorkerProcessHandle> activeWorkers = new ConcurrentHashMap<>();
    private final Queue<PendingAcquire> pendingAcquires = new ArrayDeque<>();
    private final AtomicInteger totalWorkers = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record PendingAcquire(String tenantId, Promise<WorkerProcessHandle> promise) {}

    public TenantWorkerPool(Vertx vertx) {
        this(vertx, System.getProperty("automately.worker.binary"), 2, 16, 128, ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), automately.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter) {
        this(vertx, binaryPath, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, automately.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, automately.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.binaryPath = binaryPath;
        this.minWarm = Math.max(0, minWarm);
        this.maxTotal = Math.max(1, maxTotal);
        this.heapMb = Math.max(16, heapMb);
        this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy");
        this.cgroupLimiter = Objects.requireNonNull(cgroupLimiter, "cgroupLimiter");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");

        // Maintain warm unbound pool
        ensureWarmPool();
    }

    public int minWarm() {
        return minWarm;
    }

    public int maxTotal() {
        return maxTotal;
    }

    public int totalWorkers() {
        return totalWorkers.get();
    }

    public synchronized int warmUnboundCount() {
        return warmUnboundQueue.size();
    }

    public CgroupMemoryLimiter cgroupLimiter() {
        return cgroupLimiter;
    }

    /**
     * Acquires a worker for the specified tenant, binding it if newly taken from the unbound pool.
     */
    public synchronized Future<WorkerProcessHandle> acquire(String tenantId) {
        if (closed.get()) {
            return Future.failedFuture(new IllegalStateException("Worker pool is closed"));
        }

        // 1. If reuse is enabled, check if we already have a bound idle worker for this tenant
        if (reusePolicy == ReusePolicy.REUSE_SAME_TENANT) {
            List<WorkerProcessHandle> list = tenantWarmQueue.get(tenantId);
            if (list != null && !list.isEmpty()) {
                WorkerProcessHandle worker = list.remove(list.size() - 1);
                if (worker.isAlive()) {
                    activeWorkers.put(worker.pid(), worker);
                    return Future.succeededFuture(worker);
                } else {
                    recycleWorker(worker);
                }
            }
        }

        // 2. Try taking from warm unbound pool
        while (!warmUnboundQueue.isEmpty()) {
            WorkerProcessHandle warm = warmUnboundQueue.poll();
            if (warm.isAlive()) {
                activeWorkers.put(warm.pid(), warm);
                ensureWarmPool();
                return warm.bind(tenantId, new JsonObject().put("heapMb", heapMb), new JsonObject())
                        .map(v -> warm);
            } else {
                recycleWorker(warm);
            }
        }

        // 3. Can we spawn a new worker right now?
        if (totalWorkers.get() < maxTotal) {
            Promise<WorkerProcessHandle> promise = Promise.promise();
            spawnWorker().onComplete(ar -> {
                if (ar.succeeded()) {
                    WorkerProcessHandle spawned = ar.result();
                    synchronized (TenantWorkerPool.this) {
                        activeWorkers.put(spawned.pid(), spawned);
                    }
                    spawned.bind(tenantId, new JsonObject().put("heapMb", heapMb), new JsonObject())
                            .onSuccess(v -> promise.complete(spawned))
                            .onFailure(promise::fail);
                } else {
                    promise.fail(ar.cause());
                }
            });
            return promise.future();
        }

        // 4. At capacity: queue the request
        Promise<WorkerProcessHandle> promise = Promise.promise();
        pendingAcquires.add(new PendingAcquire(tenantId, promise));
        return promise.future();
    }

    /**
     * Releases a worker back to the supervisor upon job completion.
     */
    public synchronized void release(WorkerProcessHandle worker, boolean errorOrOom) {
        activeWorkers.remove(worker.pid());

        boolean oomKilled = cgroupLimiter.checkOomKilled(worker.pid());
        if (errorOrOom || oomKilled || !worker.isAlive() || reusePolicy == ReusePolicy.FRESH_PER_JOB) {
            recycleWorker(worker);
        } else {
            // Keep warm for same tenant
            tenantWarmQueue.computeIfAbsent(worker.boundTenantId(), k -> new ArrayList<>()).add(worker);
        }

        processNextPending();
        ensureWarmPool();
    }

    private synchronized void processNextPending() {
        while (!pendingAcquires.isEmpty()) {
            PendingAcquire next = pendingAcquires.poll();
            acquire(next.tenantId()).onComplete(ar -> {
                if (ar.succeeded()) {
                    next.promise().complete(ar.result());
                } else {
                    next.promise().fail(ar.cause());
                }
            });
            break;
        }
    }

    private synchronized void ensureWarmPool() {
        if (closed.get()) {
            return;
        }
        while (warmUnboundQueue.size() < minWarm && totalWorkers.get() < maxTotal) {
            totalWorkers.incrementAndGet();
            spawnWorker().onComplete(ar -> {
                if (ar.succeeded()) {
                    synchronized (TenantWorkerPool.this) {
                        if (closed.get()) {
                            recycleWorker(ar.result());
                        } else {
                            warmUnboundQueue.add(ar.result());
                            processNextPending();
                        }
                    }
                } else {
                    totalWorkers.decrementAndGet();
                    LOG.error("Failed to spawn warm worker", ar.cause());
                }
            });
        }
    }

    private Future<WorkerProcessHandle> spawnWorker() {
        Promise<WorkerProcessHandle> promise = Promise.promise();
        try {
            List<String> command = new ArrayList<>();
            if (binaryPath != null && Files.isExecutable(Path.of(binaryPath))) {
                command.add(binaryPath);
                command.add("ipc");
                command.add("--heap-mb");
                command.add(String.valueOf(heapMb));
            } else {
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
            Process process = pb.start();
            long pid = process.pid();

            // Apply Linux cgroup memory bounds (heapMb + 64 MB native overhead)
            long maxBytes = (heapMb + 64L) * 1024L * 1024L;
            long highBytes = (heapMb + 32L) * 1024L * 1024L;
            cgroupLimiter.applyMemoryLimit(pid, maxBytes, highBytes);

            WriteStream<io.vertx.core.buffer.Buffer> output = StreamBridge.writeStream(vertx, process.getOutputStream());
            ReadStream<io.vertx.core.buffer.Buffer> input = StreamBridge.readStream(vertx, process.getInputStream());

            String workerId = "worker-" + pid;
            NodeWorkerChannel channel = new NodeWorkerChannel(workerId, output, input, new automately.runner.protocol.flow.CreditManager(), authorizer);
            WorkerProcessHandle handle = new WorkerProcessHandle(process, channel);

            channel.awaitHello().onComplete(ar -> {
                if (ar.succeeded()) {
                    promise.complete(handle);
                } else {
                    recycleWorker(handle);
                    promise.fail(ar.cause());
                }
            });

        } catch (Exception e) {
            promise.fail(e);
        }
        return promise.future();
    }

    private void recycleWorker(WorkerProcessHandle worker) {
        if (worker != null) {
            totalWorkers.decrementAndGet();
            cgroupLimiter.cleanup(worker.pid());
            worker.terminate();
        }
    }

    /**
     * Closes the pool and terminates all warm and active worker processes.
     */
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            for (WorkerProcessHandle warm : warmUnboundQueue) {
                recycleWorker(warm);
            }
            warmUnboundQueue.clear();

            for (List<WorkerProcessHandle> list : tenantWarmQueue.values()) {
                for (WorkerProcessHandle w : list) {
                    recycleWorker(w);
                }
            }
            tenantWarmQueue.clear();

            for (WorkerProcessHandle active : activeWorkers.values()) {
                recycleWorker(active);
            }
            activeWorkers.clear();

            for (PendingAcquire pending : pendingAcquires) {
                pending.promise().fail(new IllegalStateException("Worker pool closed"));
            }
            pendingAcquires.clear();
        }
    }
}
