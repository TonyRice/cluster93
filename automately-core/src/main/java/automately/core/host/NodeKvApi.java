package automately.core.host;

import automately.core.files.StorageCapacity;
import automately.core.runtime.HostException;
import automately.core.runtime.api.HostArgs;
import automately.core.runtime.api.KvApi;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import static automately.core.host.Names.guarded;
import static automately.core.host.Names.name;

/** {@code kv} on the node's {@link KvStore}; writes are checked against storage capacity and the tenant's quota (D-038, D-043). */
final class NodeKvApi implements KvApi {

    private final HostScope scope;
    private final KvStore kv;
    private final StorageCapacity capacity;
    private final TenantQuota quota;

    NodeKvApi(HostScope scope, KvStore kv, StorageCapacity capacity, TenantQuota quota) {
        this.scope = scope;
        this.kv = kv;
        this.capacity = capacity;
        this.quota = quota;
    }

    @Override
    public Future<Object> get(String namespace, String key) {
        return guarded(() -> kv.get(scope.tenant(), name(namespace, "ns", false), name(key, "key", false)));
    }

    @Override
    public Future<Void> set(String namespace, String key, Object value, Long ttlMs) {
        return guarded(() -> {
            String ns = name(namespace, "ns", false);
            String k = name(key, "key", false);
            Object json = HostArgs.json(value, MAX_VALUE_BYTES);
            if (ttlMs != null && ttlMs < 1) {
                throw HostException.invalid("\"ttlMs\" must be a positive number of milliseconds");
            }
            return capacity.ensureRoom()
                    .compose(v -> fitsQuota(ns, k, KvStore.entrySize(ns, k, json)))
                    .compose(v -> kv.set(scope.tenant(), ns, k, json, ttlMs));
        });
    }

    @Override
    public Future<Boolean> delete(String namespace, String key) {
        return guarded(() -> kv.delete(scope.tenant(), name(namespace, "ns", false), name(key, "key", false)));
    }

    @Override
    public Future<JsonObject> list(String namespace, String prefix, int limit, String cursor) {
        return guarded(() -> kv.list(scope.tenant(), name(namespace, "ns", false), name(prefix, "prefix", true),
                Names.limit(limit, MAX_LIST_LIMIT), cursor));
    }

    @Override
    public Future<Long> increment(String namespace, String key, long by) {
        return guarded(() -> {
            String ns = name(namespace, "ns", false);
            String k = name(key, "key", false);
            return capacity.ensureRoom()
                    .compose(v -> fitsQuota(ns, k, KvStore.entrySize(ns, k, Long.MIN_VALUE)))
                    .compose(v -> kv.increment(scope.tenant(), ns, k, by));
        });
    }

    /**
     * The tenant's quota covers kv too (D-043). The quick check counts the whole entry as new; only when that says no
     * is it rechecked properly, from a fresh count and against what the write really adds over the entry it replaces,
     * so a full tenant can still update or shrink what it has.
     */
    private Future<Void> fitsQuota(String namespace, String key, long size) {
        String tenant = scope.tenant();
        return quota.ensureFits(tenant, size).recover(err -> {
            if (!TenantQuota.isExceeded(err)) {
                return Future.failedFuture(err);
            }
            return kv.get(tenant, namespace, key).compose(old -> {
                long added = size - (old == null ? 0 : KvStore.entrySize(namespace, key, old));
                return added <= 0 ? Future.succeededFuture() : quota.remaining(tenant).compose(left -> quota.ensureFits(tenant, added));
            });
        });
    }
}
