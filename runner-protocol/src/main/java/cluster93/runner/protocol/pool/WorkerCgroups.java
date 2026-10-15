package cluster93.runner.protocol.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The node's Linux cgroups v2 manager (D-127, D-158): it applies a **per-tenant CPU** limit and a **per-worker
 * memory** limit, and reports what the host supports via {@link CgroupSupport}.
 *
 * <p>Layout, under the node's own detected cgroup:
 * <pre>
 *   &lt;own&gt;/cluster93/tenant-&lt;id&gt;/cpu.max              the tenant's CPU ceiling (D-158)
 *   &lt;own&gt;/cluster93/tenant-&lt;id&gt;/worker-&lt;pid&gt;/memory.{max,high}   one worker's memory (D-163)
 * </pre>
 * A worker is a child of its tenant's cgroup, so the tenant's {@code cpu.max} bounds the tenant's <em>aggregate</em>
 * CPU — which is what "one tenant's busy loop cannot starve another" needs. A per-worker cap would not: a tenant
 * running more workers would still take proportionally more.
 *
 * <p><b>Texts, not numbers.</b> As with the memory limit, everything degrades: where the controller or the base is
 * unavailable nothing is applied and the engine's own limit is the only bound. {@link #prepare(boolean)} is the one
 * side-effecting step, called once at startup.
 */
public class WorkerCgroups {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerCgroups.class);
    /** cgroup v2 default period: 100 ms. {@code cpu.max} is "&lt;microseconds of CPU&gt; &lt;period&gt;". */
    private static final long CPU_PERIOD_MICROS = 100_000L;

    private final CgroupSupport support;
    private Path base;
    private boolean available;
    /** The per-tenant CPU ceiling as a percentage of one core (0 = unlimited); set once from config. */
    private volatile int tenantCpuPercent;

    /** Detects this host's cgroup v2 shape; {@link #support()} says what it found. Creates nothing. */
    public WorkerCgroups() {
        this(CgroupSupport.detect());
    }

    public WorkerCgroups(CgroupSupport support) {
        this.support = support;
        this.base = support.base();
        this.available = support.available();
        LOG.info("Worker cgroups: {}", support.detail());
        if (!available) {
            LOG.warn("cgroup v2 limits are NOT applied on this host — the engine's own per-context limit is the only "
                    + "bound. Set -Dcluster93.cgroup.dir, or run under a unit with Delegate=yes. ({})", support.detail());
        }
    }

    /** An explicit base, for tests and for an operator who pins one ({@code -Dcluster93.cgroup.dir}). Assumes the
     *  base is a working cgroup v2 directory, so limits are applied. */
    public WorkerCgroups(Path cgroupBaseDir) {
        this(new CgroupSupport(null, cgroupBaseDir, checkWritable(cgroupBaseDir), Set.of("cpu", "memory"),
                "explicit base " + cgroupBaseDir));
    }

    /** What this host supports — the base, the delegated controllers and whether a limit can be applied. */
    public CgroupSupport support() {
        return support;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Prepares the node's own cgroup so worker cgroups can be created under it, **once at startup**. cgroup v2
     * forbids enabling a controller for children while a process sits in the same cgroup ("no internal processes"),
     * and the node runs in its own — so it moves itself into a leaf child first, then enables the delegated
     * controllers. This is exactly what the shipped probe does, and what makes the limits real on a delegated unit.
     *
     * @param required when true, a failure to prepare throws instead of warning ({@code C93_REQUIRE_CGROUP_LIMITS})
     * @return whether worker limits can now be applied
     */
    public boolean prepare(boolean required) {
        if (usable()) {
            return true;
        }
        boolean ok = prepareOwn();
        if (!ok) {
            // Do not leave `available` true: a per-worker write would then only fail and warn on every spawn. Falling
            // back cleanly is what the host deserves when its cgroup cannot host worker limits.
            available = false;
            base = null;
        }
        if (!ok && required) {
            throw new IllegalStateException("C93_REQUIRE_CGROUP_LIMITS is set but cgroup limits cannot be enforced: "
                    + support.detail());
        }
        return ok;
    }

    /** Whether the base exists and the controllers we need are already enabled for children of the node's cgroup. */
    private boolean usable() {
        if (!available || base == null) {
            return false;
        }
        Path own = support.own();
        if (own == null) {
            // An explicit base: the operator has seen to the plumbing, so a directory is enough.
            return Files.isDirectory(base);
        }
        // A directory left behind by an earlier failed attempt is not usable: the controllers must actually be
        // enabled in the node's own cgroup, or a child would have no memory.max/cpu.max to write.
        return Files.isDirectory(base) && subtreeControl(own).containsAll(wantedControllers());
    }

    /** The controllers we would use and this host delegates. */
    private Set<String> wantedControllers() {
        Set<String> wanted = new LinkedHashSet<>();
        if (support.cpu()) {
            wanted.add("cpu");
        }
        if (support.memory()) {
            wanted.add("memory");
        }
        return wanted;
    }

    /** The controllers a cgroup has enabled for its children, from {@code cgroup.subtree_control}. */
    private static Set<String> subtreeControl(Path cgroup) {
        try {
            Path file = cgroup.resolve("cgroup.subtree_control");
            if (!Files.isReadable(file)) {
                return Set.of();
            }
            return new LinkedHashSet<>(java.util.Arrays.asList(Files.readString(file).trim().split("\\s+")));
        } catch (IOException | RuntimeException ignored) {
            return Set.of();
        }
    }

    private boolean prepareOwn() {
        Path own = support.own();
        if (own == null) {
            LOG.warn("No cgroup of our own to prepare ({}); worker limits stay unapplied", support.detail());
            return false;
        }
        String enable = enableTokens();
        if (enable.isEmpty()) {
            LOG.warn("The node's cgroup {} delegates no cpu/memory controller; worker limits stay unapplied", own);
            return false;
        }
        try {
            long ourPid = ProcessHandle.current().pid();
            List<Long> members = readPids(own.resolve("cgroup.procs"));
            if (members.stream().anyMatch(pid -> pid != ourPid)) {
                LOG.warn("The node's cgroup {} holds {} other process(es), so its controllers cannot be enabled for "
                        + "worker cgroups; limits stay unapplied", own, members.size() - (members.contains(ourPid) ? 1 : 0));
                return false;
            }
            // 1. Move ourselves into a leaf, so `own` holds no process and its controllers can be enabled for children.
            Path node = own.resolve("node");
            Files.createDirectories(node);
            Files.writeString(node.resolve("cgroup.procs"), String.valueOf(ourPid));
            // 2. Enable the delegated controllers for the node's children.
            Files.writeString(own.resolve("cgroup.subtree_control"), enable);
            // 3. Create the base the worker cgroups live under.
            if (base == null) {
                base = own.resolve("cluster93");
            }
            Files.createDirectories(base);
            available = true;
            LOG.info("Prepared worker cgroups: moved into {} and enabled [{}] under {}", node, enable, own);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not prepare {} for worker cgroups ({}); limits stay unapplied", own, e.getMessage());
            return false;
        }
    }

    /** The {@code subtree_control} enable string for the controllers we want and this host delegates, or "". */
    private String enableTokens() {
        List<String> tokens = new ArrayList<>();
        if (support.cpu()) {
            tokens.add("+cpu");
        }
        if (support.memory()) {
            tokens.add("+memory");
        }
        return String.join(" ", tokens);
    }

    /**
     * Sets a tenant's aggregate CPU ceiling, as a percentage of one core (100 = one core, 200 = two, 0 = unlimited).
     * Idempotent; called whenever a tenant's worker is created.
     */
    public boolean applyTenantCpu(String tenant) {
        if (!available || tenant == null || tenantCpuPercent <= 0 || !support.cpu()) {
            return false;
        }
        Path dir = tenantDir(tenant);
        try {
            Files.createDirectories(dir);
            long quota = (long) tenantCpuPercent * CPU_PERIOD_MICROS / 100;
            Files.writeString(dir.resolve("cpu.max"), quota + " " + CPU_PERIOD_MICROS);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to set cpu.max for tenant {}: {}", tenant, e.getMessage());
            return false;
        }
    }

    /** The configured per-tenant CPU ceiling; 0 means unlimited. */
    public int tenantCpuPercent() {
        return tenantCpuPercent;
    }

    public void tenantCpuPercent(int percentOfCore) {
        this.tenantCpuPercent = Math.max(0, percentOfCore);
    }

    /**
     * Places a worker process in its tenant's cgroup and bounds its memory (D-163).
     *
     * @return true if applied, false in fallback mode
     */
    public boolean applyWorker(String tenant, long pid, long maxBytes, long highBytes) {
        if (!available || tenant == null) {
            return false;
        }
        Path dir = workerDir(tenant, pid);
        try {
            Files.createDirectories(dir);
            if (maxBytes > 0 && support.memory()) {
                Files.writeString(dir.resolve("memory.max"), String.valueOf(maxBytes));
            }
            if (highBytes > 0 && support.memory()) {
                Files.writeString(dir.resolve("memory.high"), String.valueOf(highBytes));
            }
            Files.writeString(dir.resolve("cgroup.procs"), String.valueOf(pid));
            LOG.debug("Placed worker PID {} in tenant {} (memory max={} bytes)", pid, tenant, maxBytes);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to apply cgroup memory limit to worker PID {} (tenant {}): {}", pid, tenant, e.getMessage());
            return false;
        }
    }

    /** Whether a worker (tenant and pid) was OOM-killed, from its cgroup's {@code memory.events}. */
    public boolean checkOomKilled(String tenant, long pid) {
        if (!available || tenant == null) {
            return false;
        }
        Path eventsFile = workerDir(tenant, pid).resolve("memory.events");
        if (!Files.exists(eventsFile)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(eventsFile)) {
                if (line.startsWith("oom_kill ")) {
                    return Integer.parseInt(line.substring("oom_kill ".length()).trim()) > 0;
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not inspect memory.events for worker PID {}: {}", pid, e.getMessage());
        }
        return false;
    }

    /** Removes a worker's cgroup once its process has exited. */
    public void cleanup(String tenant, long pid) {
        if (!available || tenant == null) {
            return;
        }
        try {
            Files.deleteIfExists(workerDir(tenant, pid));
        } catch (Exception e) {
            LOG.debug("Could not delete cgroup for worker PID {}: {}", pid, e.getMessage());
        }
    }

    private Path tenantDir(String tenant) {
        return base.resolve("tenant-" + sanitize(tenant));
    }

    private Path workerDir(String tenant, long pid) {
        return tenantDir(tenant).resolve("worker-" + pid);
    }

    /** A tenant name as a cgroup directory name: anything outside {@code [A-Za-z0-9_.-]} becomes {@code _}. */
    private static String sanitize(String tenant) {
        return tenant.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** The pids a cgroup holds, or an empty list when unreadable. */
    private static List<Long> readPids(Path cgroupProcs) {
        List<Long> pids = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(cgroupProcs)) {
                try {
                    pids.add(Long.parseLong(line.trim()));
                } catch (NumberFormatException ignored) {
                    // a blank or malformed line; skip it
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable: the caller treats "unknown" as "not safe to prepare"
        }
        return pids;
    }

    private static boolean checkWritable(Path path) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return false;
        }
        try {
            Path existing = path;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing != null && Files.isWritable(existing);
        } catch (Exception e) {
            return false;
        }
    }
}
