package automately.core.files;

import automately.core.runtime.HostException;
import io.vertx.core.Future;

/**
 * Which {@link BlobStore} a file's bytes go to and come from (D-056, D-057): the cluster's own default, or a tenant's
 * own provider when its routes say so. Reads follow the backend recorded with the file, whatever the routes say now.
 */
public interface BlobStores {

    /** The cluster's own default backend (AUTOMATELY_FILES_BACKEND). */
    BlobStore primary();

    /** Where a new file at this path goes. */
    Future<BlobStore> forWrite(String tenant, String path);

    /** The store named by a file's recorded backend. */
    Future<BlobStore> forRead(String tenant, String backend);

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
                + " backend, but the cluster now uses " + current + " (AUTOMATELY_FILES_BACKEND); move it back to read it");
    }
}
