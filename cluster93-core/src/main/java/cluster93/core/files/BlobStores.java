package cluster93.core.files;

import cluster93.core.runtime.HostException;
import io.vertx.core.Future;

/**
 * Which {@link BlobStore} a file's bytes go to and come from (D-056, D-057): the cluster's own default, or a tenant's
 * own provider when its routes say so. Reads follow the backend recorded with the file, whatever the routes say now.
 */
public interface BlobStores {

    /** The cluster's own default backend (C93_FILES_BACKEND). */
    BlobStore primary();

    /** Where a new file at this path goes. */
    Future<BlobStore> forWrite(String tenant, String path);

    /** The store named by a file's recorded backend. */
    Future<BlobStore> forRead(String tenant, String backend);

    /** Top-level names a tenant can't write under: their owners (jobs' scratch space, packages) write there (D-078). */
    java.util.Set<String> RESERVED = java.util.Set.of("tmp", "packages", ".packages");
    /** Where mounts go by default; anything else under it must be a mount. */
    String MOUNTS = "mnt";

    /**
     * Whether a new file or folder may appear at {@code path} (D-078): not under a reserved name, not under {@code mnt}
     * unless that's a mount, and not beneath a read-only mount. Files already there stay readable and removable.
     */
    default Future<Void> checkWritable(String tenant, String path) {
        return reserved(path, java.util.Map.of());
    }

    /** Whether a file or folder may be deleted or moved away from {@code path}: not beneath a read-only mount. */
    default Future<Void> checkRemovable(String tenant, String path) {
        return Future.succeededFuture();
    }

    /**
     * What a listing shows about storage (D-078): {@code {mounts: {folder: {provider, readOnly}}, names: {backend: name}}},
     * where a backend's name is "default" for the cluster's own, or the provider's name.
     */
    default Future<io.vertx.core.json.JsonObject> describe(String tenant) {
        return Future.succeededFuture(new io.vertx.core.json.JsonObject().put("mounts", new io.vertx.core.json.JsonObject())
                .put("names", new io.vertx.core.json.JsonObject().put(primary().name(), "default")));
    }

    /** The reserved-name rules, given the tenant's mounts (folder → provider). */
    static Future<Void> reserved(String path, java.util.Map<String, String> mounts) {
        String[] segments = path.split("/");
        if (RESERVED.contains(segments[0])) {
            return Future.failedFuture(new HostException("Error", "reserved", segments[0] + "/ is reserved"
                    + (segments[0].equals("tmp") ? " for jobs' scratch space" : " for packages")));
        }
        if (segments[0].equals(MOUNTS) && segments.length > 1 && !mounts.containsKey(MOUNTS + "/" + segments[1])) {
            return Future.failedFuture(new HostException("Error", "reserved", MOUNTS + "/" + segments[1]
                    + " isn't a mount; mnt/ holds mounts only (PUT /v1/mounts/" + MOUNTS + "/" + segments[1] + " makes one)"));
        }
        return Future.succeededFuture();
    }

    /** Every file in one store. */
    static BlobStores single(BlobStore store) {
        return new BlobStores() {
            @Override
            public BlobStore primary() {
                return store;
            }

            @Override
            public Future<BlobStore> forWrite(String tenant, String path) {
                return Future.succeededFuture(store);
            }

            @Override
            public Future<BlobStore> forRead(String tenant, String backend) {
                return store.name().equals(backend) ? Future.succeededFuture(store) : Future.failedFuture(elsewhere(backend, store.name()));
            }
        };
    }

    static HostException elsewhere(String backend, String current) {
        return new HostException("Error", "storage_unavailable", "This file is stored in the " + backend
                + " backend, but the cluster now uses " + current + " (C93_FILES_BACKEND); move it back to read it");
    }
}
