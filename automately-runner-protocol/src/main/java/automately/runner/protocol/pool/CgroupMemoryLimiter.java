package automately.runner.protocol.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manages Linux cgroups v2 memory limits (MemoryMax, MemoryHigh) for tenant worker subprocesses (D-127, §7).
 * Provides graceful fallback when running in unprivileged environments, containers, or non-Linux OS.
 */
public class CgroupMemoryLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(CgroupMemoryLimiter.class);

    private final Path cgroupBaseDir;
    private final boolean available;

    public CgroupMemoryLimiter() {
        this(resolveDefaultCgroupPath());
    }

    public CgroupMemoryLimiter(Path cgroupBaseDir) {
        this.cgroupBaseDir = cgroupBaseDir;
        this.available = checkAvailability(cgroupBaseDir);
    }

    private static Path resolveDefaultCgroupPath() {
        String configured = System.getProperty("automately.cgroup.dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("/sys/fs/cgroup/automately");
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
