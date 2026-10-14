package cluster93.runner.protocol.pool;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervises a pool of isolated, warm tenant worker processes (D-127, §7).
 * Pre-spawns workers in UNBOUND state and binds them to tenants on demand, enforcing cgroup v2 memory limits.
 */
public class TenantWorkerPool {

    private static final Logger LOG = LoggerFactory.getLogger(TenantWorkerPool.class);

    /** How long to wait for a worker's hello by default; override with {@code cluster93.worker.helloTimeoutMs}. */
    public static final long DEFAULT_HELLO_TIMEOUT_MS = 30_000L;

    public enum ReusePolicy {
        FRESH_PER_JOB,
        REUSE_SAME_TENANT
    }

    private final Vertx vertx;
    private final String binaryPath;
    private final String mode;
    private final int minWarm;
    private final int maxTotal;
    private final int heapMb;
    private final ReusePolicy reusePolicy;
    private final CgroupMemoryLimiter cgroupLimiter;
    private final cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer;
    /** How long a freshly spawned worker may take to say hello before it is given up on (D-135). */
    private final long helloTimeoutMs;
    /** The node-wide allowance every tenant's pool draws from (D-156); null means this pool is the only one. */
    private final WorkerBudget nodeBudget;
    /** Who arbitrates between tenants when capacity is contended (D-161); null when this pool is the only one. */
    private NodeArbiter arbiter;

    private final Queue<WorkerProcessHandle> warmUnboundQueue = new ArrayDeque<>();
    private final Map<String, List<WorkerProcessHandle>> tenantWarmQueue = new ConcurrentHashMap<>();
    private final Map<Long, WorkerProcessHandle> activeWorkers = new ConcurrentHashMap<>();
    /**
     * Waiters, per tenant. Insertion-ordered, so the earliest-arrived tenant wins a tie: a single global FIFO let a
     * tenant that queued ten requests be served all ten before a tenant that queued one, which starves it.
     */
    private final Map<String, Deque<PendingAcquire>> pendingByTenant = new LinkedHashMap<>();
    /** How many workers each tenant is currently running, for reporting how the pool is shared out. */
    private final Map<String, Integer> activeByTenant = new HashMap<>();
    private final AtomicInteger totalWorkers = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record PendingAcquire(String tenantId, Promise<WorkerProcessHandle> promise) {}

    public TenantWorkerPool(Vertx vertx) {
        this(vertx, System.getProperty("cluster93.worker.binary"), null, 2, 16, 128, ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this(vertx, binaryPath, mode, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer, null);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, cluster93.runner.protocol.server.NodeRpcAuthorizer authorizer, WorkerBudget nodeBudget) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.binaryPath = binaryPath;
        this.mode = (mode == null || mode.isBlank()) ? "javet" : mode.toLowerCase(Locale.ROOT);
        this.minWarm = Math.max(0, minWarm);
        this.maxTotal = Math.max(1, maxTotal);
        this.heapMb = Math.max(16, heapMb);
        this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy");
        this.cgroupLimiter = Objects.requireNonNull(cgroupLimiter, "cgroupLimiter");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.nodeBudget = nodeBudget;
        this.helloTimeoutMs = Math.max(1L, Long.getLong("cluster93.worker.helloTimeoutMs", DEFAULT_HELLO_TIMEOUT_MS));

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

    /** The configured (or auto-discovered) binary path, which may be null when JVM fallback is in play. */
    public String binaryPath() {
        return binaryPath;
    }

    /** The engine mode this pool's workers are built for (e.g. "javet" or "graaljs"). */
    public String mode() {
        return mode;
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
                    countActive(tenantId, 1);
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
                countActive(tenantId, 1);
                ensureWarmPool();
                return warm.bind(tenantId, new JsonObject().put("heapMb", heapMb), new JsonObject())
                        .map(v -> warm)
                        .onFailure(err -> release(warm, true));
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
                        countActive(tenantId, 1);
                    }
                    spawned.bind(tenantId, new JsonObject().put("heapMb", heapMb), new JsonObject())
                            .onSuccess(v -> promise.complete(spawned))
                            .onFailure(err -> {
                                release(spawned, true);
                                promise.fail(err);
                            });
                } else {
                    promise.fail(ar.cause());
                }
            });
            return promise.future();
        }

        // 4. At capacity: queue the request
        Promise<WorkerProcessHandle> promise = Promise.promise();
        pendingByTenant.computeIfAbsent(tenantId, k -> new ArrayDeque<>()).add(new PendingAcquire(tenantId, promise));
        if (arbiter != null) {
            arbiter.tenantWaiting(tenantId);
        }
        return promise.future();
    }

    /**
     * Releases a worker back to the supervisor upon job completion.
     */
    public synchronized void release(WorkerProcessHandle worker, boolean errorOrOom) {
        activeWorkers.remove(worker.pid());
        countActive(worker.boundTenantId(), -1);

        boolean oomKilled = cgroupLimiter.checkOomKilled(worker.pid());
        if (errorOrOom || oomKilled || !worker.isAlive() || reusePolicy == ReusePolicy.FRESH_PER_JOB) {
            recycleWorker(worker);
        } else {
            // Keep warm for same tenant
            tenantWarmQueue.computeIfAbsent(worker.boundTenantId(), k -> new ArrayList<>()).add(worker);
        }

        if (arbiter != null) {
            // The arbiter orders tenants between pools; this pool still serves its own queue, so a tenant that is
            // not in the rotation cannot strand a request here. Replacing this with the arbiter alone needs the
            // per-tenant worker (work order step 2), or a waiting request can go unserved.
            arbiter.tenantWaiting(worker.boundTenantId());
            processNextPending();
        } else {
            processNextPending();
        }
        ensureWarmPool();
    }

    private synchronized void processNextPending() {
        if (pendingByTenant.isEmpty()) {
            return;
        }
        // One tenant per pool, so its own backlog is plain FIFO; who wins *between* tenants is the arbiter's job.
        Map.Entry<String, Deque<PendingAcquire>> head = pendingByTenant.entrySet().iterator().next();
        String tenantId = head.getKey();
        Deque<PendingAcquire> queue = head.getValue();
        PendingAcquire next = queue.poll();
        if (queue.isEmpty()) {
            pendingByTenant.remove(tenantId);
        }
        acquire(next.tenantId()).onComplete(ar -> {
            if (ar.succeeded()) {
                next.promise().complete(ar.result());
            } else {
                next.promise().fail(ar.cause());
            }
        });
    }


    /** Tracks how many workers a tenant is running, for reporting how the pool is shared out. */
    private void countActive(String tenantId, int delta) {
        if (tenantId == null) {
            return;
        }
        activeByTenant.merge(tenantId, delta, Integer::sum);
        if (activeByTenant.get(tenantId) <= 0) {
            activeByTenant.remove(tenantId);
        }
    }

    private synchronized void ensureWarmPool() {
        if (closed.get()) {
            return;
        }
        while (warmUnboundQueue.size() < minWarm && totalWorkers.get() < maxTotal) {
            // spawnWorker counts the attempt against maxTotal and logs its own failure (with the worker's stderr).
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
                }
            });
        }
    }

    private Future<WorkerProcessHandle> spawnWorker() {
        Promise<WorkerProcessHandle> promise = Promise.promise();
        // Counted from the moment the spawn starts, so maxTotal bounds in-flight workers too. Released by
        // recycleWorker on failure, or when the handle is recycled after use (D-135).
        totalWorkers.incrementAndGet();
        if (nodeBudget != null && !nodeBudget.tryAcquire()) {
            // Another tenant took the last of the node's allowance between the check and here.
            totalWorkers.decrementAndGet();
            promise.fail(new IllegalStateException("The node is at its worker capacity; try again shortly"));
            return promise.future();
        }
        try {
            List<String> command = new ArrayList<>();
            String resolvedBinary = (binaryPath != null && !binaryPath.isBlank()) ? resolveBinary(binaryPath, mode) : null;
            if (resolvedBinary == null) {
                resolvedBinary = resolveBinary(null, mode);
            }
            if (resolvedBinary != null) {
                command.add(resolvedBinary);
                command.add("ipc");
                command.add("--heap-mb");
                command.add(String.valueOf(heapMb));
            } else {
                String javaBin = ProcessHandle.current().info().command()
                        .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
                command.add(javaBin);
                command.add("-cp");
                command.add(resolveWorkerClasspath());
                command.add("cluster93.worker.WorkerMain");
                command.add("ipc");
                command.add("--heap-mb");
                command.add(String.valueOf(heapMb));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            long pid = process.pid();

            // Drain stderr immediately: the OS pipe buffer is 64 KiB, and a worker that logs more than that before
            // its first run would block forever writing to a full pipe (D-128). The tail is kept so a worker that
            // dies before its hello can say why (D-135).
            StderrTail stderrTail = new StderrTail(20);
            drainStderr(pid, process.getErrorStream(), stderrTail);

            // Apply Linux cgroup memory bounds (heapMb + 64 MB native overhead)
            long maxBytes = (heapMb + 64L) * 1024L * 1024L;
            long highBytes = (heapMb + 32L) * 1024L * 1024L;
            cgroupLimiter.applyMemoryLimit(pid, maxBytes, highBytes);

            WriteStream<io.vertx.core.buffer.Buffer> output = StreamBridge.writeStream(vertx, process.getOutputStream());
            ReadStream<io.vertx.core.buffer.Buffer> input = StreamBridge.readStream(vertx, process.getInputStream());

            String workerId = "worker-" + pid;
            NodeWorkerChannel channel = new NodeWorkerChannel(workerId, output, input, new cluster93.runner.protocol.flow.CreditManager(), authorizer);
            WorkerProcessHandle handle = new WorkerProcessHandle(process, channel);

            // A worker must say hello; if it dies first, or never answers, fail the acquire instead of waiting forever.
            AtomicBoolean settled = new AtomicBoolean(false);
            long timerId = vertx.setTimer(helloTimeoutMs, id -> {
                if (settled.compareAndSet(false, true)) {
                    LOG.warn("Worker {} (pid {}) didn't say hello within {} ms{}", workerId, pid, helloTimeoutMs, stderrTail.render());
                    recycleWorker(handle);
                    promise.fail(new IllegalStateException("Worker " + workerId + " didn't say hello within " + helloTimeoutMs + " ms"));
                }
            });
            channel.awaitHello().onComplete(ar -> {
                if (settled.compareAndSet(false, true)) {
                    vertx.cancelTimer(timerId);
                    if (ar.succeeded()) {
                        promise.complete(handle);
                    } else {
                        LOG.warn("Worker {} (pid {}) failed to start: {}{}", workerId, pid,
                                ar.cause() == null ? "unknown error" : ar.cause().getMessage(), stderrTail.render());
                        recycleWorker(handle);
                        promise.fail(ar.cause() == null ? new IllegalStateException("Worker " + workerId + " failed to start") : ar.cause());
                    }
                }
            });

        } catch (Exception e) {
            totalWorkers.decrementAndGet();
            if (nodeBudget != null) {
                nodeBudget.release();
            }
            LOG.warn("Worker didn't start: {}", e.getMessage());
            promise.fail(e);
        }
        return promise.future();
    }

    private void recycleWorker(WorkerProcessHandle worker) {
        // Each handle is counted once (in spawnWorker) and released once; terminate() is idempotent and marks the
        // handle, so a repeat recycle is a no-op (D-135).
        if (worker == null || worker.state() == WorkerProcessHandle.State.TERMINATED) {
            return;
        }
        totalWorkers.decrementAndGet();
        if (nodeBudget != null) {
            nodeBudget.release();
        }
        cgroupLimiter.cleanup(worker.pid());
        inspectExit(worker);
        worker.terminate();
    }

    /** A worker that died on its own leaves an exit code that says how: 137 is SIGKILL/OOM, 139 is SIGSEGV. */
    private static void inspectExit(WorkerProcessHandle worker) {
        try {
            if (!worker.process().isAlive()) {
                int code = worker.process().exitValue();
                if (code == 137) {
                    LOG.error("Worker {} (pid {}) was killed (exit 137): likely OOM or cgroup memory limit", worker.workerId(), worker.pid());
                } else if (code == 139) {
                    LOG.error("Worker {} (pid {}) crashed with SIGSEGV (exit 139)", worker.workerId(), worker.pid());
                } else if (code != 0) {
                    LOG.warn("Worker {} (pid {}) exited with code {}", worker.workerId(), worker.pid(), code);
                }
            }
        } catch (IllegalThreadStateException ignored) {
            // still alive when we looked
        }
    }

    private static void drainStderr(long pid, java.io.InputStream stderr, StderrTail tail) {
        Thread.ofVirtual().name("worker-stderr-" + pid).start(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stderr))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.add(line);
                    LOG.debug("[worker {}] {}", pid, line);
                }
            } catch (IOException ignored) {
                // the pipe closed
            }
        });
    }

    /**
     * The last few lines a worker wrote to stderr. A worker that dies before its hello leaves nothing in the log
     * (its stderr is only at debug level), so the reason travels with the failure instead (D-135).
     */
    static final class StderrTail {
        private final int limit;
        private final java.util.Deque<String> lines = new java.util.ArrayDeque<>();

        StderrTail(int limit) {
            this.limit = limit;
        }

        synchronized void add(String line) {
            lines.addLast(line);
            while (lines.size() > limit) {
                lines.removeFirst();
            }
        }

        /** The held lines as a newline-prefixed block, or "" when there are none. */
        synchronized String render() {
            if (lines.isEmpty()) {
                return "";
            }
            return "\n" + String.join("\n", lines);
        }
    }


    /** The arbiter that decides between tenants (D-161); set once, by the manager that owns this pool. */
    TenantWorkerPool arbiter(NodeArbiter arbiter) {
        this.arbiter = arbiter;
        return this;
    }

    /** Whether this tenant has requests waiting for node capacity. */
    synchronized boolean hasPending() {
        return !pendingByTenant.isEmpty();
    }

    /** Serves this tenant's next waiting request, because the arbiter chose it (D-161). */
    synchronized void pump() {
        processNextPending();
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

            for (Deque<PendingAcquire> queue : pendingByTenant.values()) {
                for (PendingAcquire pending : queue) {
                    pending.promise().fail(new IllegalStateException("Worker pool closed"));
                }
            }
            pendingByTenant.clear();
        }
    }

    // --- Binary auto-discovery and child-JVM classpath resolution (D-129) ---

    /**
     * Resolves the executable worker binary for a mode. An explicit binary is honoured first; otherwise the native
     * worker binaries laid out by the build script ({@code bin/worker-<engine>},
     * {@code benchmarks/engines/bin/worker-<engine>}, {@code worker/target/worker-<engine>}) are searched,
     * walking up from the working directory so a server launched from any subdirectory still finds the repository root.
     *
     * @param explicitBinary an operator-configured binary path, or null/blank to auto-discover
     * @param mode           the worker engine mode (e.g. "javet" or "graaljs"); null/blank defaults to "javet"
     * @return the resolved executable path, or null when none was found
     */
    public static String resolveBinary(String explicitBinary, String mode) {
        return resolveBinary(explicitBinary, mode, Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    static String resolveBinary(String explicitBinary, String mode, Path baseDir) {
        String normalized = (mode == null || mode.isBlank()) ? "javet" : mode.toLowerCase(Locale.ROOT);
        if (explicitBinary != null && !explicitBinary.isBlank()) {
            Path resolved = firstExecutable(baseDir, explicitBinary.trim());
            return resolved == null ? null : resolved.toString();
        }
        for (String candidate : List.of(
                "bin/worker-" + normalized,
                "bin/worker-javet",
                "benchmarks/engines/bin/worker-" + normalized,
                "worker/target/worker-" + normalized)) {
            Path resolved = firstExecutable(baseDir, candidate);
            if (resolved != null) {
                return resolved.toString();
            }
        }
        return null;
    }

    /**
     * Resolves a single candidate path (absolute, or relative to the working directory) to an executable regular file,
     * walking up parent directories from the working directory. Returns null when no executable file is found.
     */
    public static String resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path resolved = firstExecutable(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(), path);
        return resolved == null ? null : resolved.toString();
    }

    /**
     * Builds the {@code -cp} value a child JVM needs to run {@code cluster93.worker.WorkerMain}: the current JVM's
     * classpath, plus the worker module's jar (or its compiled classes) when not already present.
     *
     * @return the classpath for the child JVM
     */
    public static String resolveWorkerClasspath() {
        return resolveWorkerClasspath(System.getProperty("java.class.path", ""),
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    static String resolveWorkerClasspath(String baseClassPath, Path baseDir) {
        List<String> entries = new ArrayList<>();
        // The worker's own classes and resources go first, so its logback.xml (which routes logging to stderr and keeps
        // stdout free for frames) shadows the server's when the worker runs as a child JVM on the server's classpath.
        Path workerTarget = findWorkerTargetDir(baseDir);
        if (workerTarget != null) {
            List<Path> jars = workerJars(workerTarget);
            if (!jars.isEmpty()) {
                for (Path jar : jars) {
                    addIfAbsent(entries, jar.toAbsolutePath().normalize().toString());
                }
            } else {
                Path classes = workerTarget.resolve("classes");
                if (Files.isDirectory(classes)) {
                    addIfAbsent(entries, classes.toAbsolutePath().normalize().toString());
                }
            }
        }
        if (baseClassPath != null && !baseClassPath.isBlank()) {
            for (String entry : baseClassPath.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    addIfAbsent(entries, entry);
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Path firstExecutable(Path baseDir, String candidate) {
        Path candidatePath;
        try {
            candidatePath = Path.of(candidate);
        } catch (RuntimeException e) {
            return null;
        }
        if (candidatePath.isAbsolute()) {
            return isExecutable(candidatePath) ? candidatePath.normalize() : null;
        }
        Path dir = baseDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path path = dir.resolve(candidate).normalize();
            if (isExecutable(path)) {
                return path;
            }
            Path parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                return null;
            }
            dir = parent;
        }
        return null;
    }

    private static boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static Path findWorkerTargetDir(Path baseDir) {
        Path dir = baseDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path target = dir.resolve("worker").resolve("target");
            if (Files.isDirectory(target)) {
                return target;
            }
            Path parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                return null;
            }
            dir = parent;
        }
        return null;
    }

    private static List<Path> workerJars(Path workerTarget) {
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(workerTarget)) {
            stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("cluster93-worker-") && name.endsWith(".jar")
                        && !name.contains("-sources") && !name.contains("-javadoc");
            }).sorted().forEach(jars::add);
        } catch (IOException ignored) {
            // The worker module hasn't been built yet; the JVM fallback will simply be missing its main class.
        }
        return jars;
    }

    private static void addIfAbsent(List<String> entries, String path) {
        String normalized = normalize(path);
        if (normalized == null) {
            return;
        }
        for (String entry : entries) {
            if (normalized.equals(normalize(entry))) {
                return;
            }
        }
        entries.add(normalized);
    }

    private static String normalize(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return path;
        }
    }
}
