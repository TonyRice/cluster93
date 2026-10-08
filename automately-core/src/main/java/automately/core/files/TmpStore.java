package automately.core.files;

import automately.core.runtime.HostException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One job's {@code /tmp} (D-079): private, on the node running the job, gone when it ends. Files are kept in memory up
 * to {@code memoryLimit} bytes in all; beyond that they spill to {@code spillDir} (the node's data directory) if there
 * is one, up to {@code totalLimit}. Never replicated and never counted against the tenant's quota. Paths are relative
 * to {@code /tmp}, with the store's path rules. Thread-safe: host calls may complete on other threads.
 */
public final class TmpStore implements AutoCloseable {

    private record Entry(byte[] memory, Path disk, long size, long modifiedAt) {
    }

    private final Path spillDir;
    private final long memoryLimit;
    private final long totalLimit;
    private final long minFreeDiskBytes;
    private final Map<String, Entry> files = new TreeMap<>();
    private final TreeSet<String> dirs = new TreeSet<>();
    private long inMemory;
    private long total;
    private boolean closed;

    /** Above this share of the heap, nothing more is kept in memory (D-038's admission control, checked in place). */
    static final double MAX_HEAP_FILL = 0.85;

    /**
     * @param spillDir         this job's folder for spilled files, made on first use; null to keep everything in memory
     * @param minFreeDiskBytes spilling stops once the disk would have less free than this (AUTOMATELY_MIN_FREE_DISK_MB)
     */
    public TmpStore(Path spillDir, long memoryLimit, long totalLimit, long minFreeDiskBytes) {
        this.spillDir = spillDir;
        this.memoryLimit = memoryLimit;
        this.totalLimit = totalLimit;
        this.minFreeDiskBytes = minFreeDiskBytes;
        dirs.add("");
    }

    public synchronized JsonObject stat(String path) {
        Entry file = files.get(path);
        if (file != null) {
            return new JsonObject().put("kind", "file").put("size", file.size()).put("modifiedAt", file.modifiedAt());
        }
        return dirs.contains(path) ? new JsonObject().put("kind", "dir").put("size", 0L).put("modifiedAt", 0L) : null;
    }

    /** One level: {@code [{name, kind, size}]} by name. */
    public synchronized JsonArray list(String dir) {
        requireDir(dir);
        String prefix = dir.isEmpty() ? "" : dir + "/";
        Map<String, JsonObject> entries = new TreeMap<>();
        dirs.tailSet(prefix, false).stream().takeWhile(d -> d.startsWith(prefix)).filter(d -> !d.substring(prefix.length()).contains("/"))
                .forEach(d -> entries.put(Paths.name(d), new JsonObject().put("name", Paths.name(d)).put("kind", "dir").put("size", 0L)));
        files.forEach((path, entry) -> {
            if (Paths.parent(path).equals(dir)) {
                entries.put(Paths.name(path), new JsonObject().put("name", Paths.name(path)).put("kind", "file").put("size", entry.size()));
            }
        });
        return new JsonArray(java.util.List.copyOf(entries.values()));
    }

    public synchronized void mkdir(String path, boolean recursive) {
        if (files.containsKey(path)) {
            throw new HostException("Error", "already_exists", "/tmp/" + path + " is a file");
        }
        if (dirs.contains(path)) {
            if (!recursive) {
                throw new HostException("Error", "already_exists", "/tmp/" + path + " already exists");
            }
            return;
        }
        String parent = Paths.parent(path);
        if (!dirs.contains(parent)) {
            if (!recursive) {
                throw new HostException("Error", "not_found", "No such directory: /tmp/" + parent);
            }
            mkdir(parent, true);
        }
        dirs.add(path);
    }

    public synchronized byte[] read(String path) {
        Entry entry = files.get(path);
        if (entry == null) {
            throw dirs.contains(path) ? new HostException("Error", "is_a_directory", "/tmp/" + path + " is a directory")
                    : new HostException("Error", "not_found", "No such file: /tmp/" + path);
        }
        if (entry.memory() != null) {
            return entry.memory().clone();
        }
        try {
            return Files.readAllBytes(entry.disk());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes (replaces) a file, making its folders. */
    public synchronized void write(String path, byte[] data) {
        open();
        if (dirs.contains(path)) {
            throw new HostException("Error", "is_a_directory", "/tmp/" + path + " is a directory");
        }
        for (String up = Paths.parent(path); !up.isEmpty(); up = Paths.parent(up)) {
            if (files.containsKey(up)) {
                throw new HostException("Error", "not_a_directory", "/tmp/" + up + " is a file");
            }
        }
        Entry old = files.get(path);
        long oldSize = old == null ? 0 : old.size();
        if (total - oldSize + data.length > totalLimit) {
            throw HostException.limit("/tmp is limited to " + totalLimit / (1024 * 1024) + " MB per job");
        }
        remove(path);
        Entry entry;
        if (inMemory + data.length <= memoryLimit && heapRoom()) {
            entry = new Entry(data.clone(), null, data.length, System.currentTimeMillis());
            inMemory += data.length;
        } else if (spillDir != null) {
            Path file = spillDir.resolve(UUID.randomUUID().toString());
            try {
                Files.createDirectories(spillDir);
                if (Files.getFileStore(spillDir).getUsableSpace() - data.length < minFreeDiskBytes) {
                    throw StorageCapacity.insufficient("the node's disk is nearly full, so /tmp can't grow");
                }
                Files.write(file, data);
            } catch (IOException e) {
                throw new HostException("Error", "insufficient_storage", "Can't write /tmp/" + path + " to the node's disk: " + e.getMessage());
            }
            entry = new Entry(null, file, data.length, System.currentTimeMillis());
        } else if (!heapRoom()) {
            throw StorageCapacity.insufficient("the node's memory is nearly full, so /tmp can't grow");
        } else {
            throw HostException.limit("/tmp is limited to " + memoryLimit / (1024 * 1024) + " MB per job on this node");
        }
        String parent = Paths.parent(path);
        if (!dirs.contains(parent)) {
            mkdir(parent, true);
        }
        files.put(path, entry);
        total += data.length;
    }

    public synchronized boolean delete(String path) {
        if (dirs.contains(path)) {
            throw new HostException("Error", "is_a_directory", "/tmp/" + path + " is a directory");
        }
        return remove(path);
    }

    /** Removes a folder: an empty one, or with {@code recursive} everything in it. */
    public synchronized boolean rmdir(String path, boolean recursive) {
        if (path.isEmpty() || !dirs.contains(path)) {
            if (files.containsKey(path)) {
                throw new HostException("Error", "not_a_directory", "/tmp/" + path + " is a file");
            }
            return false;
        }
        String prefix = path + "/";
        boolean empty = files.keySet().stream().noneMatch(p -> p.startsWith(prefix)) && dirs.stream().noneMatch(d -> d.startsWith(prefix));
        if (!empty && !recursive) {
            throw new HostException("Error", "not_empty", "/tmp/" + path + " isn't empty");
        }
        files.keySet().stream().filter(p -> p.startsWith(prefix)).toList().forEach(this::remove);
        dirs.removeIf(d -> d.equals(path) || d.startsWith(prefix));
        return true;
    }

    /** Renames a file or folder within {@code /tmp}. */
    public synchronized void rename(String from, String to, boolean overwrite) {
        if (files.containsKey(from)) {
            if (files.containsKey(to) && !overwrite) {
                throw new HostException("Error", "already_exists", "/tmp/" + to + " already exists");
            }
            byte[] data = read(from);
            write(to, data);
            remove(from);
            return;
        }
        if (!dirs.contains(from)) {
            throw new HostException("Error", "not_found", "No such file or directory: /tmp/" + from);
        }
        if (to.equals(from) || to.startsWith(from + "/")) {
            throw HostException.invalid("A folder can't go inside itself");
        }
        String prefix = from + "/";
        mkdir(to, true);
        for (String dir : dirs.tailSet(prefix, false).stream().takeWhile(d -> d.startsWith(prefix)).toList()) {
            dirs.add(to + dir.substring(from.length()));
        }
        for (String file : files.keySet().stream().filter(p -> p.startsWith(prefix)).toList()) {
            files.put(to + file.substring(from.length()), files.remove(file));
        }
        dirs.removeIf(d -> d.equals(from) || d.startsWith(prefix));
    }

    public synchronized long size() {
        return total;
    }

    private boolean remove(String path) {
        Entry entry = files.remove(path);
        if (entry == null) {
            return false;
        }
        total -= entry.size();
        if (entry.memory() != null) {
            inMemory -= entry.size();
        } else {
            try {
                Files.deleteIfExists(entry.disk());
            } catch (IOException ignored) {
                // The whole folder goes when the job ends, and at the node's next start.
            }
        }
        return true;
    }

    private static boolean heapRoom() {
        Runtime runtime = Runtime.getRuntime();
        return (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory() <= MAX_HEAP_FILL;
    }

    private void requireDir(String dir) {
        if (!dirs.contains(dir)) {
            throw files.containsKey(dir) ? new HostException("Error", "not_a_directory", "/tmp/" + dir + " is a file")
                    : new HostException("Error", "not_found", "No such directory: /tmp/" + dir);
        }
    }

    private void open() {
        if (closed) {
            throw new HostException("Error", "unavailable", "This job's /tmp is gone: the job has ended");
        }
    }

    /** Drops everything, and the job's spill folder. */
    @Override
    public synchronized void close() {
        closed = true;
        files.clear();
        dirs.clear();
        inMemory = 0;
        total = 0;
        if (spillDir != null) {
            deleteTree(spillDir);
        }
    }

    /** Deletes a folder and everything in it; used for a job's spill folder, and the node's whole tmp folder at start. */
    public static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** For tests. */
    synchronized long inMemory() {
        return inMemory;
    }
}
