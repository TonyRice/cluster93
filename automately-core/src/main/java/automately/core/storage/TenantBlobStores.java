package automately.core.storage;

import automately.core.files.BlobStore;
import automately.core.files.BlobStores;
import automately.core.files.DiskBlobStore;
import automately.core.files.s3.S3BlobStore;
import automately.core.files.s3.S3Client;
import automately.core.files.s3.S3Settings;
import automately.core.host.EgressPolicy;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * File bytes routed per tenant (D-057): the tenant's routes pick one of its providers, or the cluster's own default.
 * The admin's policy decides which provider types a tenant may write to; reads always work, wherever a file is.
 * One store per provider version is kept, so keys rotate without breaking reads already in progress.
 */
public final class TenantBlobStores implements BlobStores {

    private final Vertx vertx;
    private final BlobStore primary;
    private final StorageConfigStore config;
    private final SecretBox secrets;
    private final Path diskRoot;
    private final long minFreeDiskBytes;
    private final BooleanSupplier diskRootShared;
    private final boolean egressAllowPrivate;
    private final Map<String, Cached> stores = new ConcurrentHashMap<>();

    private record Cached(long updatedAt, boolean allowPrivate, BlobStore store) {
    }

    /**
     * @param secrets          null without AUTOMATELY_SECRETS_KEY (then S3 providers can't be used)
     * @param diskRoot         null without AUTOMATELY_DISK_ROOT (then disk providers can't be used)
     * @param diskRootShared   whether every live node sees the same disk root (a single node always does)
     */
    public TenantBlobStores(Vertx vertx, BlobStore primary, StorageConfigStore config, SecretBox secrets, Path diskRoot,
                            long minFreeDiskBytes, BooleanSupplier diskRootShared, boolean egressAllowPrivate) {
        this.vertx = vertx;
        this.primary = primary;
        this.config = config;
        this.secrets = secrets;
        this.diskRoot = diskRoot;
        this.minFreeDiskBytes = minFreeDiskBytes;
        this.diskRootShared = diskRootShared;
        this.egressAllowPrivate = egressAllowPrivate;
    }

    @Override
    public BlobStore primary() {
        return primary;
    }

    @Override
    public Future<BlobStore> forWrite(String tenant, String path) {
        return config.routes(tenant).compose(routes -> {
            String name = routes.providerFor(path);
            if (name == null) {
                return Future.succeededFuture(primary);
            }
            return Future.all(config.provider(tenant, name), config.policy(tenant)).compose(both -> {
                StorageProvider provider = both.resultAt(0);
                StoragePolicy policy = both.resultAt(1);
                if (provider == null) {
                    return Future.failedFuture(new HostException("Error", "storage_unavailable",
                            "The storage routes send " + path + " to provider \"" + name + "\", which doesn't exist"));
                }
                if (!policy.allows(provider.type())) {
                    return Future.failedFuture(disabled(tenant, provider.type()));
                }
                return store(provider, policy);
            });
        });
    }

    @Override
    public Future<BlobStore> forRead(String tenant, String backend) {
        if (backend.equals(primary.name())) {
            return Future.succeededFuture(primary);
        }
        if (!backend.startsWith(StorageProvider.BACKEND_PREFIX)) {
            return Future.failedFuture(BlobStores.elsewhere(backend, primary.name()));
        }
        String id = backend.substring(StorageProvider.BACKEND_PREFIX.length());
        return Future.all(config.providerById(id), config.policy(tenant)).compose(both -> {
            StorageProvider provider = both.resultAt(0);
            // A provider only ever serves its own tenant's files.
            if (provider == null || !provider.tenant().equals(tenant)) {
                return Future.failedFuture(new HostException("Error", "storage_unavailable", "This file's storage provider no longer exists"));
            }
            return store(provider, both.resultAt(1));
        });
    }

    static HostException disabled(String tenant, String type) {
        return new HostException("Error", "storage_disabled", "The admin hasn't enabled " + type + " storage providers for tenant " + tenant);
    }

    /** The store for one version of a provider. Also used to check a provider before it's saved. */
    public Future<BlobStore> store(StorageProvider provider, StoragePolicy policy) {
        boolean allowPrivate = egressAllowPrivate || policy.allowPrivateEndpoints();
        Cached cached = stores.get(provider.id());
        if (cached != null && cached.updatedAt() == provider.updatedAt() && cached.allowPrivate() == allowPrivate) {
            return Future.succeededFuture(cached.store());
        }
        BlobStore store;
        try {
            store = build(provider, allowPrivate);
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
        stores.put(provider.id(), new Cached(provider.updatedAt(), allowPrivate, store));
        return Future.succeededFuture(store);
    }

    private BlobStore build(StorageProvider provider, boolean allowPrivate) {
        return switch (provider.type()) {
            case StorageProvider.S3 -> {
                if (secrets == null) {
                    throw new HostException("Error", "storage_unavailable", "Tenant S3 providers need AUTOMATELY_SECRETS_KEY on every node");
                }
                JsonObject s = provider.settings();
                S3Settings settings = new S3Settings(URI.create(s.getString("endpoint")), s.getString("region", "us-east-1"),
                        s.getString("bucket"), s.getString("prefix", "automately/"), s.getBoolean("pathStyle", true), s.getString("accessKeyId"),
                        secrets.open(provider.sealedSecret(), secretContext(provider.tenant(), provider.id())), null);
                yield new S3BlobStore(provider.backend(), new S3Client(vertx, settings, new EgressPolicy(allowPrivate)));
            }
            case StorageProvider.DISK -> {
                if (diskRoot == null) {
                    throw new HostException("Error", "storage_unavailable", "Tenant disk providers need AUTOMATELY_DISK_ROOT");
                }
                if (!diskRootShared.getAsBoolean()) {
                    throw new HostException("Error", "storage_unavailable",
                            "AUTOMATELY_DISK_ROOT isn't the same folder on every node (it must be shared storage, e.g. NFS), so disk providers are off");
                }
                yield new DiskBlobStore(provider.backend(), vertx, diskFolder(provider.tenant(), provider.name()), minFreeDiskBytes);
            }
            default -> throw new HostException("Error", "storage_unavailable", "Unknown provider type " + provider.type());
        };
    }

    /** Chosen by the server, never by the tenant: no path a tenant sends can reach outside its folder. */
    public Path diskFolder(String tenant, String name) {
        return diskRoot.resolve(tenant).resolve(name);
    }

    /** What a provider's secret is sealed to, so it can't be moved to another provider or tenant and still open. */
    public static String secretContext(String tenant, String providerId) {
        return tenant + "/" + providerId;
    }
}
