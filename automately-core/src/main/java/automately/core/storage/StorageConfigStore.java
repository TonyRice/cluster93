package automately.core.storage;

import io.vertx.core.Future;

import java.util.List;

/** Tenants' storage policies (admin), providers and routes (D-057), kept in the cluster's database. */
public interface StorageConfigStore {

    /** The tenant's policy, or {@link StoragePolicy#none} if the admin never set one. */
    Future<StoragePolicy> policy(String tenant);

    Future<Void> putPolicy(StoragePolicy policy);

    Future<StorageProvider> provider(String tenant, String name);

    Future<StorageProvider> providerById(String id);

    Future<List<StorageProvider>> providers(String tenant);

    Future<Void> putProvider(StorageProvider provider);

    Future<Boolean> deleteProvider(String tenant, String name);

    /** The tenant's routes, or {@link StorageRoutes#none} if it never set any. */
    Future<StorageRoutes> routes(String tenant);

    Future<Void> putRoutes(StorageRoutes routes);

    /** Everything of a tenant's, when the tenant is deleted. */
    Future<Void> forget(String tenant);
}
