package cluster93.runner.protocol.pool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What this host's cgroup v2 setup gives the node (D-158): where its writable subtree is, which controllers are
 * delegated into it, and whether a limit can actually be applied — so a node can say plainly whether tenant CPU and
 * memory limits are enforced here, instead of assuming a path that only works when it runs as root.
 *
 * <p>The old default was the absolute {@code /sys/fs/cgroup/cluster93}. Creating that needs **root** — it is the root
 * cgroup's child, owned by root — so a node running as any ordinary user (or as the {@code cluster93} service
 * account) could not write it and silently applied **no** cgroup limit. The writable subtree is the node's **own**
 * cgroup, which systemd delegates when a unit sets {@code Delegate=yes}: read from {@code /proc/self/cgroup} and the
 * cgroup2 mount in {@code /proc/self/mountinfo}. This finds it, and reports the delegated controllers.
 *
 * <p>Detection has no side effects; {@link #base()} is where a caller would create children. The one wrinkle worth
 * knowing: cgroup v2 forbids enabling a controller for children while a process sits in the same cgroup ("no
 * internal processes"), so a node that wants to use its own subtree must first move itself into a leaf child — which
 * is why the shipped probe does exactly that before writing {@code cpu.max}.
 *
 * @param own         the node's own cgroup (under which its workers live), or null when undetectable
 * @param base        the directory under which the node creates its worker cgroups, or null when unusable
 * @param available   whether a limit could be applied there (Linux, cgroup v2, and a writable base)
 * @param controllers the controllers delegated into the node's own cgroup ({@code cpu}, {@code memory}, …)
 * @param detail      a human-readable one-liner, for the startup log and the snapshot
 */
public record CgroupSupport(Path own, Path base, boolean available, Set<String> controllers, String detail) {

    public boolean memory() {
        return controllers.contains("memory");
    }

    public boolean cpu() {
        return controllers.contains("cpu");
    }

    /** A support reading with no usable base; the detail says why. */
    public static CgroupSupport unavailable(String detail) {
        return new CgroupSupport(null, null, false, Set.of(), detail);
    }

    /** Reads this host's cgroup v2 shape, honouring {@code -Dcluster93.cgroup.dir} as an override. */
    public static CgroupSupport detect() {
        return detect(cgroupMount(), ownCgroupPath(), override());
    }

    /**
     * The detection itself, with its inputs passed in so it can be tested without touching the real host.
     *
     * @param mount         the cgroup2 mount point (normally {@code /sys/fs/cgroup})
     * @param ownCgroupPath the node's cgroup from {@code /proc/self/cgroup}, e.g. {@code /system.slice/cluster93.service}
     * @param override      an explicit base ({@code -Dcluster93.cgroup.dir}), or null to derive one
     */
    static CgroupSupport detect(Path mount, String ownCgroupPath, Path override) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return unavailable("not Linux");
        }
        Path own = (ownCgroupPath == null || ownCgroupPath.isBlank())
                ? null : mount.resolve(stripLeadingSlash(ownCgroupPath));
        Set<String> controllers = own == null ? Set.of() : readControllers(own);
        Path base = override != null ? override
                : (own != null ? own.resolve("cluster93") : mount.resolve("cluster93"));
        boolean writable = writable(base);
        String detail = (own == null
                ? "no cgroup2 entry in /proc/self/cgroup"
                : "own cgroup " + own + "; delegated controllers: " + (controllers.isEmpty() ? "(none)" : String.join(",", controllers)))
                + (writable ? "" : "; base " + base + " is not writable");
        return new CgroupSupport(own, writable ? base : null, writable, controllers, detail);
    }

    private static Path override() {
        String configured = System.getProperty("cluster93.cgroup.dir");
        return (configured == null || configured.isBlank()) ? null : Path.of(configured);
    }

    /** The cgroup2 mount, from /proc/self/mountinfo; falls back to the conventional /sys/fs/cgroup. */
    static Path cgroupMount() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/mountinfo"))) {
                String[] fields = line.split(" ");
                for (int i = 0; i + 2 < fields.length; i++) {
                    if ("-".equals(fields[i]) && "cgroup2".equals(fields[i + 1]) && fields.length > 4) {
                        return Path.of(fields[4]);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // no mountinfo; the conventional path below is the fallback
        }
        return Path.of("/sys/fs/cgroup");
    }

    /** This process's cgroup, the {@code 0::<path>} line of /proc/self/cgroup, or null when absent. */
    static String ownCgroupPath() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                if (line.startsWith("0::")) {
                    return line.substring(3).trim();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // no /proc; detection will report it
        }
        return null;
    }

    /** The controllers listed in a cgroup's {@code cgroup.controllers}, or an empty set when it cannot be read. */
    private static Set<String> readControllers(Path cgroup) {
        Path file = cgroup.resolve("cgroup.controllers");
        try {
            if (!Files.isReadable(file)) {
                return Set.of();
            }
            return new LinkedHashSet<>(java.util.Arrays.asList(Files.readString(file).trim().split("\\s+")));
        } catch (IOException | RuntimeException ignored) {
            return Set.of();
        }
    }

    /** Whether a base can be created — checks the nearest existing ancestor, and creates nothing. */
    private static boolean writable(Path base) {
        Path existing = base;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing != null && Files.isWritable(existing);
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
