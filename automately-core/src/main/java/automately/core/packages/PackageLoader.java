package automately.core.packages;

import automately.core.runtime.HostException;
import automately.core.runtime.Packages;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches everything in a job's lock before its isolate is made (D-083 §5, D-089): Javet resolves imports on the
 * isolate's thread, synchronously, so nothing can be fetched once the script runs. Every file is checked against its
 * hash; a file that doesn't match is refetched once, then fails the attempt. Checked files are kept in a node-wide
 * cache by hash, bounded by bytes, least recently used first out.
 */
public final class PackageLoader {

    private final PackageStore store;
    private final Cache cache;

    public PackageLoader(PackageStore store, long cacheBytes) {
        this.store = store;
        this.cache = new Cache(cacheBytes);
    }

    /** Everything {@code lock} names, for a job of {@code tenant}; fails saying what couldn't be loaded. */
    public Future<Packages> load(String tenant, JsonObject lock) {
        if (lock == null || lock.isEmpty()) {
            return Future.succeededFuture(Packages.NONE);
        }
        List<String> names = List.copyOf(lock.fieldNames());
        List<Future<JsonObject>> versions = names.stream().map(name -> store.locked(tenant, name, lock.getString(name))).toList();
        return Future.all(versions).compose(all -> {
            int fileCount = 0;
            long bytes = 0;
            for (int i = 0; i < names.size(); i++) {
                JsonObject version = versions.get(i).result();
                if (version == null) {
                    return Future.failedFuture(new HostException("Error", "package_missing", "Package " + names.get(i) + "@" + lock.getString(names.get(i))
                            + " in this job's lock no longer exists"));
                }
                fileCount += version.getJsonObject("files").size();
                bytes += version.getLong("size", 0L);
            }
            if (fileCount > PackageStore.MAX_FILES || bytes > PackageStore.MAX_BYTES) {
                return Future.failedFuture(HostException.limit("A job's packages can have at most " + PackageStore.MAX_FILES + " files and "
                        + PackageStore.MAX_BYTES + " bytes in all; this job's have " + fileCount + " files and " + bytes + " bytes"));
            }
            List<Future<Packages.Package>> loaded = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                loaded.add(loadVersion(tenant, versions.get(i).result()));
            }
            return Future.all(loaded).map(done -> {
                Map<String, Packages.Package> byName = new LinkedHashMap<>();
                loaded.forEach(p -> byName.put(p.result().name(), p.result()));
                return new Packages(byName);
            });
        });
    }

    private Future<Packages.Package> loadVersion(String tenant, JsonObject version) {
        String name = version.getString("name");
        String owner = PackageStore.ownerOf(name, tenant);
        JsonObject files = version.getJsonObject("files");
        List<String> paths = List.copyOf(files.fieldNames());
        List<Future<byte[]>> contents = paths.stream()
                .map(path -> file(owner, files.getString(path).substring("sha256:".length()), name + "@" + version.getString("version") + "/" + path))
                .toList();
        return Future.all(contents).map(all -> {
            Map<String, byte[]> bytes = new LinkedHashMap<>();
            for (int i = 0; i < paths.size(); i++) {
                bytes.put(paths.get(i), contents.get(i).result());
            }
            Map<String, String> exports = new LinkedHashMap<>();
            version.getJsonObject("exports", new JsonObject()).forEach(e -> exports.put(e.getKey(), String.valueOf(e.getValue())));
            Set<String> dependencies = version.getJsonObject("dependencies", new JsonObject()).fieldNames();
            return new Packages.Package(name, version.getString("version"), exports, dependencies, bytes);
        });
    }

    /** One file, from the cache or the store; checked against its hash, with one retry. */
    private Future<byte[]> file(String owner, String sha256, String what) {
        byte[] cached = cache.get(owner, sha256);
        if (cached != null) {
            return Future.succeededFuture(cached);
        }
        return fetch(owner, sha256, what).recover(err -> err instanceof Mismatch ? fetch(owner, sha256, what) : Future.failedFuture(err))
                .recover(err -> Future.failedFuture(err instanceof Mismatch ? new HostException("Error", "package_corrupt", what + " doesn't match its SHA-256 ("
                        + sha256 + "); it may be corrupt, so the job didn't run") : err))
                .onSuccess(bytes -> cache.put(owner, sha256, bytes));
    }

    private Future<byte[]> fetch(String owner, String sha256, String what) {
        return store.read(owner, sha256).compose(buffer -> {
            if (buffer == null) {
                return Future.failedFuture(new HostException("Error", "package_missing", what + " is missing from the package store"));
            }
            byte[] bytes = buffer.getBytes();
            return sha256(bytes).equals(sha256) ? Future.succeededFuture(bytes) : Future.failedFuture(new Mismatch());
        });
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the cache holds now, in bytes; for tests and metrics. */
    public long cachedBytes() {
        return cache.bytes();
    }

    private static final class Mismatch extends RuntimeException {
        Mismatch() {
            super("hash mismatch", null, false, false);
        }
    }

    /**
     * Checked files by owner and hash. Keyed by owner too: the same bytes can be cached once per tenant, so whether a
     * file is cached never tells one tenant about another's.
     */
    private static final class Cache {

        private final long limit;
        private final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>(64, 0.75f, true);
        private long bytes;

        Cache(long limit) {
            this.limit = limit;
        }

        synchronized byte[] get(String owner, String sha256) {
            return files.get(owner + "/" + sha256);
        }

        synchronized void put(String owner, String sha256, byte[] content) {
            if (content.length > limit) {
                return;
            }
            byte[] previous = files.put(owner + "/" + sha256, content);
            bytes += content.length - (previous == null ? 0 : previous.length);
            var eldest = files.entrySet().iterator();
            while (bytes > limit && eldest.hasNext()) {
                bytes -= eldest.next().getValue().length;
                eldest.remove();
            }
        }

        synchronized long bytes() {
            return bytes;
        }
    }
}
