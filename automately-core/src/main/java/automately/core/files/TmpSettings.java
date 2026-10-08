package automately.core.files;

import java.nio.file.Path;

/**
 * How big a job's {@code /tmp} may get (D-079): {@code memoryBytes} in memory, then spilled under {@code root} (the
 * node's data directory) up to {@code totalBytes}. Without a root it's memory only.
 */
public record TmpSettings(Path root, long memoryBytes, long totalBytes, long minFreeDiskBytes) {

    public static final long MB = 1024 * 1024;

    /** In-memory nodes: 64 MB per job, in memory. */
    public static TmpSettings memoryOnly() {
        return new TmpSettings(null, 64 * MB, 64 * MB, 0);
    }

    /** A job's store: its own spill folder under the root. */
    public TmpStore open(String jobId) {
        return new TmpStore(root == null ? null : root.resolve(jobId), memoryBytes, root == null ? Math.min(totalBytes, memoryBytes) : totalBytes,
                minFreeDiskBytes);
    }
}
