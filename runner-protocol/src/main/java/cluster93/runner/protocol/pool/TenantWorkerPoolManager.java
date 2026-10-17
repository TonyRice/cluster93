package cluster93.runner.protocol.pool;

import cluster93.core.metrics.Metrics;
import cluster93.runner.protocol.message.HelloMessage;
import cluster93.runner.protocol.message.PolicyMessage;
import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import cluster93.runner.protocol.server.NodeWorkerChannel;
import cluster93.runner.protocol.transport.uds.UdsServerTransport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Supervises per-tenant {@link TenantWorkerPool} instances, isolating warm worker pools,
 * memory bounds, and recycling lifecycles across tenants (D-127).
 *
 * <p>It is also the node's <b>listening</b> end for worker containers (D-166, D-167): the node binds one Unix socket
 * and every connection to it is an <b>open worker</b> — tenant-agnostic, exactly what the unbound queue always meant.
 * The manager holds them because the pool is per tenant and an open worker belongs to no tenant yet; a pool takes one
 * when it has work for it, and the node binds it there. The node never creates a container, so this is a worker
 * <em>source</em> beside the spawn path, not a second orchestrator.
 */
public class TenantWorkerPoolManager implements NodeArbiter {

    private static final Logger LOG = LoggerFactory.getLogger(TenantWorkerPoolManager.class);

    private final Vertx vertx;
    private final Map<String, String> modeBinaries;
    private final String defaultMode;
    private final int minWarmPerTenant;
    private final int maxTotalPerTenant;
    /** How many jobs one worker runs at once (D-155), passed to every pool this manager creates. */
    private final int maxJobsPerWorker;
    /** The node-wide allowance shared by every tenant's pool, so n tenants cannot spawn n × capacity (D-156). */
    private final WorkerBudget nodeBudget;
    /** The node-wide memory allowance shared by every tenant's pool (D-163), next to the worker count. */
    private final WorkerMemoryBudget memoryBudget;
    /**
     * Who gets a freed worker when tenants contend (D-161). This is the only place more than one tenant is visible:
     * a per-tenant pool sees just itself, so fairness installed there could never fire.
     */
    private final TenantRotation rotation = new TenantRotation();
    private final int heapMb;
    private final TenantWorkerPool.ReusePolicy reusePolicy;
    private final WorkerCgroups cgroupLimiter;
    private final NodeRpcAuthorizer authorizer;

    private final Map<String, TenantWorkerPool> tenantPools = new ConcurrentHashMap<>();

    // --- the listening worker source (D-166, D-167) ---

    /**
     * Open workers: connections that have said hello, presented the secret, and claimed no tenant yet. The node binds
     * each to whichever tenant has work. They live here, not in a tenant's pool, because they belong to no tenant —
     * which is exactly what "tenant-agnostic pool" means (D-166).
     */
    private final Deque<SocketWorkerHandle> openWorkers = new ArrayDeque<>();
    /** Every live worker connection, open or bound. The accept cap is its size, so a count cannot drift from it. */
    private final Set<SocketWorkerHandle> connections = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectionSequence = new AtomicLong(1);
    /** The node's listening socket, or null when it accepts no worker container ({@code C93_WORKER_SOCKET} unset). */
    private UdsServerTransport workerListener;
    private String workerSocketPath;
    /** How many worker connections the node accepts at once (D-166); 0 means unbounded. */
    private int maxWorkerConnections;
    /** The pre-shared secret a connecting worker must present (D-147, D-168); null means none is required. */
    private String workerSecret;

    public TenantWorkerPoolManager(Vertx vertx, Map<String, String> modeBinaries, String defaultMode) {
        this(vertx, modeBinaries, defaultMode, 1, 8, 8, 4, 128, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new WorkerCgroups(), NodeRpcAuthorizer.ALLOW_ALL);
    }

    public TenantWorkerPoolManager(Vertx vertx,
                                   Map<String, String> modeBinaries,
                                   String defaultMode,
                                   int minWarmPerTenant,
                                   int maxTotalPerTenant,
                                   int maxWorkers,
                                   int maxJobsPerWorker,
                                   int heapMb,
                                   TenantWorkerPool.ReusePolicy reusePolicy,
                                   WorkerCgroups cgroupLimiter,
                                   NodeRpcAuthorizer authorizer) {
        this(vertx, modeBinaries, defaultMode, minWarmPerTenant, maxTotalPerTenant, maxWorkers, maxJobsPerWorker,
                heapMb, reusePolicy, cgroupLimiter, authorizer, 0);
    }

    public TenantWorkerPoolManager(Vertx vertx,
                                   Map<String, String> modeBinaries,
                                   String defaultMode,
                                   int minWarmPerTenant,
                                   int maxTotalPerTenant,
                                   int maxWorkers,
                                   int maxJobsPerWorker,
                                   int heapMb,
                                   TenantWorkerPool.ReusePolicy reusePolicy,
                                   WorkerCgroups cgroupLimiter,
                                   NodeRpcAuthorizer authorizer,
                                   long maxWorkerMemoryBytes) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.modeBinaries = modeBinaries != null ? Map.copyOf(modeBinaries) : Map.of();
        this.defaultMode = defaultMode != null ? defaultMode : "javet";
        this.minWarmPerTenant = Math.max(0, minWarmPerTenant);
        this.maxTotalPerTenant = Math.max(1, maxTotalPerTenant);
        this.maxJobsPerWorker = Math.max(1, maxJobsPerWorker);
        // Bounded at last (D-156). Gating spawns on the shared allowance used to deadlock: every pool held minWarm
        // against the same budget, so several tenants filled it with idle workers and a tenant that then could not
        // spawn queued forever (PackagesTest hung). Two things changed: pools no longer pre-warm (minWarm 0), and a
        // spawn that finds the budget full first asks the manager to reap an IDLE worker node-wide and retries
        // (freeIdleWorker). Work preempts warmth, so an idle worker can never block a job.
        this.nodeBudget = new WorkerBudget(Math.max(1, maxWorkers));
        this.memoryBudget = new WorkerMemoryBudget(maxWorkerMemoryBytes);
        this.heapMb = Math.max(16, heapMb);
        this.reusePolicy = Objects.requireNonNull(reusePolicy, "reusePolicy");
        this.cgroupLimiter = Objects.requireNonNull(cgroupLimiter, "cgroupLimiter");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    }

    /**
     * Resolves the dedicated worker pool for a given tenant, creating it if not present.
     */
    public TenantWorkerPool poolFor(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return tenantPools.computeIfAbsent(tenantId, t -> {
            String binary = resolveModeBinary(defaultMode);
            TenantWorkerPool pool = new TenantWorkerPool(vertx, binary, defaultMode, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget, maxJobsPerWorker, memoryBudget)
                    .arbiter(this);
            return registerTenantGauges(t, pool);
        });
    }

    /**
     * Acquires a worker for a specific tenant and execution mode.
     *
     * <p>This is <b>not</b> where the node-wide concurrent-<em>job</em> bound lives. That bound is the scheduler's:
     * it places at most {@code C93_CAPACITY} running attempts on a node and refuses more, and the node re-checks it
     * in {@code acceptsAttempt}, so a node never runs more jobs at once than it advertises (D-154, D-156). This
     * method only supplies a worker for a job the scheduler has already admitted.
     */
    public Future<WorkerHandle> acquire(String tenantId, String mode) {
        String effectiveMode = (mode != null && !mode.isBlank()) ? mode.toLowerCase(Locale.ROOT) : defaultMode;
        // If mode matches default, use the tenant pool
        if (effectiveMode.equalsIgnoreCase(defaultMode)) {
            return poolFor(tenantId).acquire(tenantId);
        }

        // Dedicated pool for this mode and tenant
        String poolKey = tenantId + ":" + effectiveMode;
        TenantWorkerPool pool = tenantPools.computeIfAbsent(poolKey, k -> {
            String binary = resolveModeBinary(effectiveMode);
            TenantWorkerPool created = new TenantWorkerPool(vertx, binary, effectiveMode, minWarmPerTenant, maxTotalPerTenant, heapMb, reusePolicy, cgroupLimiter, authorizer, nodeBudget, maxJobsPerWorker, memoryBudget)
                    .arbiter(this);
            return registerTenantGauges(tenantId, created);
        });
        return pool.acquire(tenantId);
    }

    /**
     * Resolves the binary for a mode: the mode's explicit binary, then the "default" binary, then auto-discovery.
     * Returns null when no executable binary can be found (the pool then falls back to a child JVM).
     */
    String resolveModeBinary(String mode) {
        String normalized = (mode == null || mode.isBlank()) ? defaultMode : mode.toLowerCase(Locale.ROOT);
        String explicit = modeBinaries.get(normalized);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        explicit = modeBinaries.get("default");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return TenantWorkerPool.resolveBinary(null, normalized);
    }

    /**
     * Releases an acquired worker after one of its jobs finishes. This is a run-level event: the pool decides from
     * the worker's own health whether the process is retired.
     */
    public void release(String tenantId, String mode, WorkerHandle worker) {
        TenantWorkerPool pool = poolFor(tenantId, mode);
        if (pool != null) {
            pool.release(worker);
        } else {
            worker.terminate();
        }
    }

    /** Retires a worker that is itself unhealthy, in the pool that owns it. */
    public void poison(String tenantId, String mode, WorkerHandle worker, String reason) {
        TenantWorkerPool pool = poolFor(tenantId, mode);
        if (pool != null) {
            pool.poison(worker, reason);
        } else {
            worker.terminate();
        }
    }

    /** The pool that owns a tenant's workers for a mode, or null when it was never created. */
    private TenantWorkerPool poolFor(String tenantId, String mode) {
        String effectiveMode = (mode != null && !mode.isBlank()) ? mode.toLowerCase(Locale.ROOT) : defaultMode;
        String poolKey = effectiveMode.equalsIgnoreCase(defaultMode) ? tenantId : tenantId + ":" + effectiveMode;
        return tenantPools.get(poolKey);
    }

    @Override
    public synchronized void tenantWaiting(String tenantId) {
        rotation.enqueue(tenantId);
    }

    /** Which tenant the manager would hand the next freed worker to; for status, and for testing the rotation. */
    public synchronized String nextWaitingTenant() {
        return rotation.next();
    }

    /**
     * A worker finished: hand its capacity to the fairest tenant waiting, skipping the one just served. One request
     * per freed worker, so a tenant cannot take several at once by having a long queue.
     */
    @Override
    public synchronized void capacityFreed() {
        String tenantId = rotation.next();
        if (tenantId == null) {
            return;
        }
        TenantWorkerPool pool = tenantPools.get(tenantId);
        if (pool == null) {
            // Defensive only: a tenant that queued has a pool, so this cannot happen in practice. Nothing can be
            // served, so the claim is dropped rather than kept forever (the rotation is not a waiting list).
            rotation.served(tenantId, false);
            return;
        }
        pool.pump();
        // Still waiting for more, so it goes to the back of the rotation and its next turn comes after everyone else.
        rotation.served(tenantId, pool.hasPending());
    }

    /** How many workers the node is running across every tenant, and what it may run. */
    public WorkerBudget nodeBudget() {
        return nodeBudget;
    }

    /** Registers a tenant's worker gauges once, backed by the pool, which the manager holds for its lifetime. */
    private TenantWorkerPool registerTenantGauges(String tenantId, TenantWorkerPool pool) {
        Metrics.of(vertx).tenantWorkers(tenantId, pool,
                TenantWorkerPool::activeWorkerCount, TenantWorkerPool::jobsInFlightCount, TenantWorkerPool::pendingCount);
        return pool;
    }

    /** A point-in-time reading of every tenant's worker use on this node (D-156), for the CLI and tests. */
    public WorkerPoolSnapshot snapshot() {
        List<WorkerPoolSnapshot.TenantWorkers> tenants = new ArrayList<>();
        Set<String> active = new HashSet<>();
        int warm = 0;
        for (TenantWorkerPool pool : tenantPools.values()) {
            warm += pool.warmCount();
            WorkerPoolSnapshot.TenantWorkers usage = pool.tenantUsage();
            if (usage != null && (usage.workers() > 0 || usage.pending() > 0)) {
                tenants.add(usage);
                if (usage.workers() > 0) {
                    active.add(usage.tenant());
                }
            }
        }
        tenants.sort(Comparator.comparing(WorkerPoolSnapshot.TenantWorkers::tenant));
        return new WorkerPoolSnapshot(nodeBudget.used(), nodeBudget.limit(), warm, active.size(),
                memoryBudget.usedBytes(), memoryBudget.limitBytes(), List.copyOf(tenants));
    }

    /** Worker processes this node is running now; a cheap reading for the node-wide gauge. */
    public int workersRunning() {
        return nodeBudget.used();
    }

    /** Worker processes this node may run; 0 means unbounded. */
    public int workersLimit() {
        return nodeBudget.limit();
    }

    /** Memory the node's workers reserve now; a cheap reading for the node-wide gauge (D-163). */
    public long workersMemoryUsed() {
        return memoryBudget.usedBytes();
    }

    /** Memory the node's workers may reserve; 0 means unbounded. */
    public long workersMemoryLimit() {
        return memoryBudget.limitBytes();
    }

    /** Idle workers kept warm, node-wide. */
    public int workersWarm() {
        int warm = 0;
        for (TenantWorkerPool pool : tenantPools.values()) {
            warm += pool.warmCount();
        }
        return warm;
    }

    /** Distinct tenants running at least one worker right now. */
    public int tenantsActive() {
        Set<String> active = new HashSet<>();
        for (TenantWorkerPool pool : tenantPools.values()) {
            WorkerPoolSnapshot.TenantWorkers usage = pool.tenantUsage();
            if (usage != null && usage.workers() > 0) {
                active.add(usage.tenant());
            }
        }
        return active.size();
    }

    /**
     * Makes room for one more worker by reaping an idle (warm) worker from any tenant's pool, returning whether one
     * was found. A pool whose spawn the node-wide budget refused calls this and retries: work must preempt warmth,
     * or an idle worker sitting in one tenant's pool would block another tenant's job for good. A worker with jobs
     * still on it is never reaped here.
     */
    @Override
    public boolean freeIdleWorker() {
        for (TenantWorkerPool pool : tenantPools.values()) {
            if (pool.reapIdleWorker()) {
                return true;
            }
        }
        return false;
    }

    /** Where a bound worker's egress policy comes from (D-173); null when this node decides egress itself. */
    private volatile java.util.function.Function<String, PolicyMessage> workerPolicy;

    /**
     * Sets where a bound worker's egress policy comes from (D-173): one payload per tenant, handed to each worker as it
     * is bound and refreshed with {@link #refreshWorkerPolicies(String)}. Left unset, this node decides egress itself
     * and sends nothing — and a worker sent nothing refuses to connect anywhere on its own.
     */
    public TenantWorkerPoolManager workerPolicy(java.util.function.Function<String, PolicyMessage> supplier) {
        this.workerPolicy = supplier;
        return this;
    }

    @Override
    public PolicyMessage policyFor(String tenantId) {
        java.util.function.Function<String, PolicyMessage> supplier = workerPolicy;
        return supplier == null ? null : supplier.apply(tenantId);
    }

    /** Re-sends the policy to every worker bound to a tenant, or to every bound worker when {@code tenantId} is null. */
    public void refreshWorkerPolicies(String tenantId) {
        for (TenantWorkerPool pool : tenantPools.values()) {
            pool.refreshPolicy(tenantId);
        }
    }

    /**
     * Takes an idle worker another tenant is holding, for {@code tenantId} to re-bind, or null when none is free
     * (D-172). The caller adopts it ({@link TenantWorkerPool#adopt}) and sends the new binding. The worker has no run
     * left — a pool only ever offers workers whose last run finished — so moving the binding cannot pull the ground
     * from under a call: the run-level authorisation and the worker's cgroup move with it.
     */
    @Override
    public WorkerHandle takeIdleWorkerFor(String tenantId) {
        for (TenantWorkerPool pool : tenantPools.values()) {
            WorkerHandle idle = pool.offerIdleWorker();
            if (idle != null) {
                LOG.info("Handing idle worker {} (tenant {}) to tenant {} instead of starting another process (D-172)",
                        idle.workerId(), idle.boundTenantId(), tenantId);
                return idle;
            }
        }
        return null;
    }

    /**
     * Closes all tenant worker pools and destroys processes.
     */
    public void close() {
        closeWorkerListener();
        // Open workers belong to no pool, so no pool closes them: close them here.
        List<SocketWorkerHandle> open;
        synchronized (this) {
            open = new ArrayList<>(openWorkers);
            openWorkers.clear();
            connections.clear();
        }
        open.forEach(SocketWorkerHandle::terminate);
        for (TenantWorkerPool pool : tenantPools.values()) {
            pool.close();
        }
        tenantPools.clear();
    }

    // ---------------------------------------------------------------------------------------
    // The listening worker source (D-166, D-167)
    // ---------------------------------------------------------------------------------------

    /**
     * Starts listening for worker containers: binds {@code socketPath} and turns every connection into an open worker.
     * The pool keeps spawning processes as before — this is a second source, not a replacement, so a node may run both.
     *
     * @param socketPath     where the node listens; the path carries no tenant, because a connection claims none
     * @param secret         the pre-shared secret every connecting worker must present (D-168), or null/blank for none
     *                       — which is warned about, since a shared socket is then guarded by its permissions alone
     * @param maxConnections how many worker connections the node accepts at once; 0 or less is unbounded
     */
    public Future<Void> listenForWorkers(String socketPath, String secret, int maxConnections) {
        this.workerSocketPath = socketPath;
        this.workerSecret = (secret == null || secret.isBlank()) ? null : secret;
        this.maxWorkerConnections = Math.max(0, maxConnections);
        if (this.workerSecret == null) {
            LOG.warn("Worker socket {} is open without a secret: any process that can reach it is accepted. Set "
                    + "C93_WORKER_SECRET_FILE (mode 0400, mounted read-only into the worker containers).", socketPath);
        }
        workerListener = new UdsServerTransport(vertx, socketPath);
        return workerListener.listen(this::onWorkerSocket).onSuccess(v -> LOG.info(
                "Listening for worker containers on {} ({} connection(s) at most{}): the deployment creates them, the "
                        + "node binds each connection to whichever tenant has work (D-166, D-167)",
                socketPath, maxWorkerConnections == 0 ? "unbounded" : String.valueOf(maxWorkerConnections),
                this.workerSecret == null ? ", no secret" : ", secret required"));
    }

    /** Stops listening for worker connections. */
    public void closeWorkerListener() {
        if (workerListener != null) {
            workerListener.close();
            workerListener = null;
        }
    }

    /** Whether this node accepts worker containers at all. */
    public boolean acceptsWorkerConnections() {
        return workerListener != null;
    }

    /** How many worker connections are live right now, open and bound together. */
    public synchronized int connectedWorkerCount() {
        return connections.size();
    }

    /** How many connections have claimed no tenant yet. */
    public synchronized int openWorkerCount() {
        return openWorkers.size();
    }

    /**
     * An open worker this node accepted, to bind to {@code tenantId} (D-166). A pool takes one before spawning, so a
     * worker a deployment supplied is preferred over a process the node starts; null when none is waiting.
     */
    @Override
    public synchronized WorkerHandle takeOpenWorker(String tenantId) {
        return openWorkers.poll();
    }

    /**
     * A connection arrived. It is not trusted yet: it must say hello, present the secret the node provisioned (D-147,
     * D-168) and fit the node's memory ceiling (D-169) before it becomes a worker at all. A pipe binds a channel to a
     * child the node made; a socket binds it to whoever connected, so these checks are the binding.
     */
    private void onWorkerSocket(NetSocket socket) {
        long id = connectionSequence.getAndIncrement();
        String workerId = "worker-c" + id;
        if (maxWorkerConnections > 0 && connectedWorkerCount() >= maxWorkerConnections) {
            LOG.warn("Refusing worker connection {} on {}: the node already holds {} (C93_WORKER_CONNECTIONS_MAX)",
                    workerId, workerSocketPath, connectedWorkerCount());
            socket.close();
            return;
        }
        NodeWorkerChannel channel = new NodeWorkerChannel(workerId, socket, socket,
                new cluster93.runner.protocol.flow.CreditManager(), authorizer);
        long helloTimeoutMs = Math.max(1L, Long.getLong("cluster93.worker.helloTimeoutMs", TenantWorkerPool.DEFAULT_HELLO_TIMEOUT_MS));
        AtomicBoolean settled = new AtomicBoolean(false);
        long timerId = vertx.setTimer(helloTimeoutMs, timer -> {
            if (settled.compareAndSet(false, true)) {
                LOG.warn("Worker connection {} did not say hello within {} ms; closing it", workerId, helloTimeoutMs);
                socket.close();
            }
        });
        channel.awaitHello().onComplete(ar -> {
            if (!settled.compareAndSet(false, true)) {
                return; // the timer already gave up on this connection
            }
            vertx.cancelTimer(timerId);
            if (ar.failed()) {
                LOG.warn("Worker connection {} ended before its hello: {}", workerId, String.valueOf(ar.cause().getMessage()));
                socket.close();
                return;
            }
            HelloMessage hello = ar.result();
            if (!secretAccepted(hello.secret())) {
                LOG.warn("Refusing worker connection {} (engine {}, pid {}): it presented the wrong secret (D-168)",
                        workerId, hello.engine(), hello.pid());
                socket.close();
                return;
            }
            long reservedBytes = declaredWorkerBytes(hello);
            if (memoryBudget != null && !memoryBudget.tryAcquire(reservedBytes)) {
                LOG.warn("Refusing worker connection {} (engine {}): its {} MB would exceed the node's worker memory "
                                + "ceiling ({} MB of {} MB already reserved). Raise C93_MAX_WORKER_MEMORY_MB, or run "
                                + "fewer worker containers (D-169).",
                        workerId, hello.engine(), reservedBytes / (1024 * 1024),
                        memoryBudget.usedBytes() / (1024 * 1024), memoryBudget.limitBytes() / (1024 * 1024));
                socket.close();
                return;
            }
            SocketWorkerHandle handle = new SocketWorkerHandle(id, socket::close, channel,
                    hello.maxJobs() > 0 ? hello.maxJobs() : maxJobsPerWorker, reservedBytes);
            synchronized (this) {
                connections.add(handle);
                openWorkers.add(handle);
            }
            channel.onClosed(err -> onWorkerConnectionClosed(handle));
            LOG.info("Worker container connected: {} (engine {}, pid {}, {} job(s) at once, {} MB reserved); it is open, "
                            + "and the node will bind it to whichever tenant needs it (D-166)",
                    workerId, hello.engine(), hello.pid(), handle.maxJobs(), reservedBytes / (1024 * 1024));
            // A tenant may already be waiting for capacity: offer it this worker through the same fairness path a
            // freed worker takes, so a connection is served to the fairest waiting tenant (D-161).
            capacityFreed();
        });
    }

    /**
     * A worker connection ended, however it ended. A connection that claimed no tenant is still the manager's, so its
     * memory is given back here; one a pool took is the pool's to reap, exactly as it reaps a dead process (D-159) —
     * its in-flight jobs must fail, the worker leaves the pool's books, and it gives the memory back.
     */
    private synchronized void onWorkerConnectionClosed(SocketWorkerHandle handle) {
        handle.connectionEnded();
        connections.remove(handle);
        if (openWorkers.remove(handle)) {
            if (memoryBudget != null) {
                memoryBudget.release(handle.reservedBytes());
            }
            LOG.info("Open worker {} disconnected before taking work; {} MB given back",
                    handle.workerId(), handle.reservedBytes() / (1024 * 1024));
            return;
        }
        String tenant = handle.boundTenantId();
        TenantWorkerPool pool = tenant == null ? null : poolFor(tenant, null);
        if (pool != null) {
            pool.poison(handle, "worker connection ended");
        }
    }

    /** Whether the worker presented the secret the node provisioned (D-168). Constant-time: a secret is not a guess. */
    private boolean secretAccepted(String presented) {
        if (workerSecret == null) {
            return true;
        }
        return presented != null && MessageDigest.isEqual(workerSecret.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The node-wide memory to reserve for a connected worker (D-169): what it <b>declares</b> — its {@code --heap-mb}
     * times the jobs it runs at once, plus native overhead — falling back to this node's own defaults when it declares
     * nothing. A connected worker's cgroup belongs to its container, which the node never sets, so this is a declared
     * size rather than the measured one a spawned worker's cgroup gives (D-163).
     */
    private long declaredWorkerBytes(HelloMessage hello) {
        int jobs = hello.maxJobs() > 0 ? hello.maxJobs() : maxJobsPerWorker;
        int heap = hello.heapMb() > 0 ? hello.heapMb() : heapMb;
        return ((long) heap * jobs + 128L) * 1024L * 1024L;
    }
}
