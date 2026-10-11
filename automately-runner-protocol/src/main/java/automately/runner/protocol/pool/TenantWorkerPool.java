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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private final automately.runner.protocol.server.NodeRpcAuthorizer authorizer;

    private final Queue<WorkerProcessHandle> warmUnboundQueue = new ArrayDeque<>();
    private final Map<String, List<WorkerProcessHandle>> tenantWarmQueue = new ConcurrentHashMap<>();
    private final Map<Long, WorkerProcessHandle> activeWorkers = new ConcurrentHashMap<>();
    private final Queue<PendingAcquire> pendingAcquires = new ArrayDeque<>();
    private final AtomicInteger totalWorkers = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record PendingAcquire(String tenantId, Promise<WorkerProcessHandle> promise) {}

    public TenantWorkerPool(Vertx vertx) {
        this(vertx, System.getProperty("automately.worker.binary"), null, 2, 16, 128, ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(), automately.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, automately.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, automately.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this(vertx, binaryPath, null, minWarm, maxTotal, heapMb, reusePolicy, cgroupLimiter, authorizer);
    }

    public TenantWorkerPool(Vertx vertx, String binaryPath, String mode, int minWarm, int maxTotal, int heapMb, ReusePolicy reusePolicy, CgroupMemoryLimiter cgroupLimiter, automately.runner.protocol.server.NodeRpcAuthorizer authorizer) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.binaryPath = binaryPath;
        this.mode = (mode == null || mode.isBlank()) ? "javet" : mode.toLowerCase(Locale.ROOT);
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

    // --- Binary auto-discovery and child-JVM classpath resolution (D-129) ---

    /**
     * Resolves the executable worker binary for a mode. An explicit binary is honoured first; otherwise the native
     * worker binaries laid out by the build script ({@code bin/worker-<engine>},
     * {@code benchmarks/engines/bin/worker-<engine>}, {@code automately-worker/target/worker-<engine>}) are searched,
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
                "automately-worker/target/worker-" + normalized)) {
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
     * Builds the {@code -cp} value a child JVM needs to run {@code automately.worker.WorkerMain}: the current JVM's
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
        if (baseClassPath != null && !baseClassPath.isBlank()) {
            for (String entry : baseClassPath.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    addIfAbsent(entries, entry);
                }
            }
        }
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
            Path target = dir.resolve("automately-worker").resolve("target");
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
                return name.startsWith("automately-worker-") && name.endsWith(".jar")
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
