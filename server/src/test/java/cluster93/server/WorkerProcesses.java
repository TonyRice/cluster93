package cluster93.server;

import cluster93.runner.protocol.pool.TenantWorkerPool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Observes live worker processes, so a test can prove how a node uses them (D-155, D-156). */
final class WorkerProcesses {

    private WorkerProcesses() {
    }

    /**
     * A symlink to a worker binary whose <b>name is unique</b>, so the workers one test's node spawns can be told
     * apart from any other node's on the same machine. The name survives {@code exec} as {@code argv[0]}, so it
     * appears in {@code /proc/<pid>/cmdline} for exactly the processes this node started. Returns null when no
     * native worker binary is present (the node would fall back to a child JVM, whose {@code argv[0]} is java).
     */
    static Path markedBinary(String marker) throws IOException {
        String real = TenantWorkerPool.resolveBinary(null, "graaljs");
        if (real == null) {
            return null;
        }
        Path link = Files.createTempDirectory("c93-marked-worker-").resolve(marker);
        Files.createSymbolicLink(link, Path.of(real));
        return link;
    }

    /** Every live process whose command line contains {@code marker} — i.e. the workers of one marked node. */
    static List<Long> pids(String marker) throws Exception {
        List<Long> pids = new ArrayList<>();
        try (var entries = Files.list(Path.of("/proc"))) {
            for (Path dir : entries.toList()) {
                String name = dir.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                String cmdline;
                try {
                    cmdline = new String(Files.readAllBytes(dir.resolve("cmdline")), StandardCharsets.ISO_8859_1);
                } catch (Exception unreadable) {
                    continue; // the process ended between listing and reading
                }
                if (cmdline.contains(marker)) {
                    pids.add(Long.parseLong(name));
                }
            }
        }
        return pids;
    }
}
