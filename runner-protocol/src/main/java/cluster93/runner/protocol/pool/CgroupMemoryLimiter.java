package cluster93.runner.protocol.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Applies Linux cgroups v2 memory limits (MemoryMax, MemoryHigh) to tenant worker subprocesses (D-127, §7), and
 * reports what this host's cgroup setup supports via {@link CgroupSupport} (D-158).
 *
 * <p>The base it writes under is **detected**, not assumed: the old absolute {@code /sys/fs/cgroup/cluster93} needs
 * root, so a node that did not run as root silently applied no limit at all. See {@link CgroupSupport} for how the
 * node's own delegated cgroup is found. Where the cgroup is unavailable (unprivileged, non-Linux, containers without
 * the controller) the limit is not applied and the engine's per-context limit is the only bound.
 */
public class CgroupMemoryLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(CgroupMemoryLimiter.class);

    private final CgroupSupport support;
    private final Path cgroupBaseDir;
    private final boolean available;

    /** Detects this host's cgroup v2 shape (D-158); {@link #support()} says what it found. */
    public CgroupMemoryLimiter() {
        this(CgroupSupport.detect());
    }

    public CgroupMemoryLimiter(CgroupSupport support) {
        this.support = support;
        this.cgroupBaseDir = support.base();
        this.available = support.available();
        LOG.info("Worker cgroups: {}", support.detail());
        if (!available) {
            LOG.warn("cgroups v2 limits are not applied on this host — the engine's own per-context limit is the only "
                    + "bound. Set -Dcluster93.cgroup.dir, or run under a unit with Delegate=yes. ({})", support.detail());
        }
    }

    /** An explicit base, for tests and for an operator who pins one: {@code -Dcluster93.cgroup.dir}. */
    public CgroupMemoryLimiter(Path cgroupBaseDir) {
        this(new CgroupSupport(cgroupBaseDir, checkAvailability(cgroupBaseDir), Set.of(), "explicit base " + cgroupBaseDir));
    }

    /** What this host supports — the base, the delegated controllers and whether a limit can be applied. */
    public CgroupSupport support() {
        return support;
    }

    private static boolean checkAvailability(Path path) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return false;
        }
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            return Files.isWritable(path);
        } catch (Exception e) {
            LOG.debug("Linux cgroup v2 directory {} is not writable; falling back to process heap limits ({})",
                    path, e.getMessage());
            return false;
        }
    }

    /**
     * Checks if Linux cgroups v2 enforcement is active and writable.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Applies hard and soft memory bounds to a worker process via Linux cgroups v2.
     *
     * @param pid the process ID to constrain
     * @param maxBytes the hard limit (memory.max); process is killed by OOM killer if exceeded
     * @param highBytes the soft limit (memory.high); processes are throttled and pages reclaimed
     * @return true if cgroup limits were successfully applied, false if running in fallback mode
     */
    public boolean applyMemoryLimit(long pid, long maxBytes, long highBytes) {
        if (!available) {
            return false;
        }

        Path workerCgroup = cgroupBaseDir.resolve("worker-" + pid);
        try {
            if (!Files.exists(workerCgroup)) {
                Files.createDirectories(workerCgroup);
            }

            if (maxBytes > 0) {
                Files.writeString(workerCgroup.resolve("memory.max"), String.valueOf(maxBytes));
            }
            if (highBytes > 0) {
                Files.writeString(workerCgroup.resolve("memory.high"), String.valueOf(highBytes));
            }

            // Assign process to this cgroup
            Files.writeString(workerCgroup.resolve("cgroup.procs"), String.valueOf(pid));
            LOG.debug("Applied cgroup v2 limits to worker PID {}: max={} bytes, high={} bytes", pid, maxBytes, highBytes);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to apply cgroup v2 limits to PID {}: {}", pid, e.getMessage());
            return false;
        }
    }

    /**
     * Inspects memory.events to check whether a worker was terminated due to an OOM kill event.
     *
     * @param pid the target process ID
     * @return true if oom_kill count is greater than 0
     */
    public boolean checkOomKilled(long pid) {
        if (!available) {
            return false;
        }

        Path eventsFile = cgroupBaseDir.resolve("worker-" + pid).resolve("memory.events");
        if (!Files.exists(eventsFile)) {
            return false;
        }

        try {
            List<String> lines = Files.readAllLines(eventsFile);
            for (String line : lines) {
                if (line.startsWith("oom_kill ")) {
                    int count = Integer.parseInt(line.substring("oom_kill ".length()).trim());
                    return count > 0;
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not inspect memory.events for PID {}: {}", pid, e.getMessage());
        }
        return false;
    }

    /**
     * Cleans up the worker's cgroup slice once the process exits.
     *
     * @param pid the process ID
     */
    public void cleanup(long pid) {
        if (!available) {
            return;
        }

        Path workerCgroup = cgroupBaseDir.resolve("worker-" + pid);
        try {
            if (Files.exists(workerCgroup)) {
                Files.deleteIfExists(workerCgroup);
            }
        } catch (Exception e) {
            LOG.debug("Could not delete cgroup directory for PID {}: {}", pid, e.getMessage());
        }
    }
}
