package cluster93.core.secrets;

import cluster93.core.host.KvStore;
import cluster93.core.queue.QueueService;
import cluster93.core.storage.SecretBox;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tenants' named secrets (D-068): write-only through the API, readable only by scripts allowed to. Kept in the
 * replicated kv store under the system tenant, which no token can reach, one namespace per tenant, and always sealed
 * with {@code C93_SECRETS_KEY} (D-057) with the tenant and name as associated data, so a sealed value moved to
 * another name or tenant doesn't open. Without that key the store is unavailable rather than storing values in the clear.
 */
public final class TenantSecrets {

    public static final int MAX_VALUE_BYTES = 64 * 1024;
    public static final int MAX_PER_TENANT = 500;
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    static final String NAMESPACE_PREFIX = "secrets/";

    private final KvStore kv;
    private final SecretBox box;

    /** @param box null if the cluster has no secrets key: then every call fails with {@link Unavailable} */
    public TenantSecrets(KvStore kv, SecretBox box) {
        this.kv = kv;
        this.box = box;
    }

    /** Whether the cluster has a secrets key. */
    public boolean available() {
        return box != null;
    }

    /** A secret's details, never its value: {@code {name, version, createdAt, updatedAt, updatedBy}}. */
    public record Info(String name, int version, long createdAt, long updatedAt, String updatedBy) {

        public JsonObject toJson() {
            return new JsonObject().put("name", name).put("version", version).put("createdAt", createdAt).put("updatedAt", updatedAt)
                    .put("updatedBy", updatedBy);
        }

        static Info of(String name, JsonObject stored) {
            return new Info(name, stored.getInteger("version", 1), stored.getLong("createdAt", 0L), stored.getLong("updatedAt", 0L),
                    stored.getString("updatedBy"));
        }
    }

    /** The cluster has no secrets key. */
    public static final class Unavailable extends RuntimeException {
        Unavailable() {
            super("Secrets need C93_SECRETS_KEY to be set on every node", null, false, false);
        }
    }

    /** Creates or replaces a secret. {@code by} is who set it (a token id), shown in its details. */
    public Future<Info> put(String tenant, String name, String value, String by) {
        return guarded(() -> {
            checkName(name);
            if (value == null) {
                throw new IllegalArgumentException("A secret's value must be a string");
            }
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("A secret's value is limited to " + MAX_VALUE_BYTES + " bytes");
            }
            String ns = namespace(tenant);
            return kv.get(QueueService.SYSTEM_TENANT, ns, name).compose(old -> {
                long now = System.currentTimeMillis();
                JsonObject previous = old instanceof JsonObject json ? json : null;
                JsonObject stored = new JsonObject()
                        .put("sealed", box.seal(value, context(tenant, name)))
                        .put("version", previous == null ? 1 : previous.getInteger("version", 1) + 1)
                        .put("createdAt", previous == null ? now : previous.getLong("createdAt", now))
                        .put("updatedAt", now)
                        .put("updatedBy", by);
                Future<Void> room = previous != null ? Future.succeededFuture() : count(tenant).compose(n -> n >= MAX_PER_TENANT
                        ? Future.failedFuture(new IllegalStateException("A tenant can have at most " + MAX_PER_TENANT + " secrets"))
                        : Future.succeededFuture());
                return room.compose(v -> kv.set(QueueService.SYSTEM_TENANT, ns, name, stored, null)).map(v -> Info.of(name, stored));
            });
        });
    }

    /** A secret's details, or null if there's none. */
    public Future<Info> info(String tenant, String name) {
        return guarded(() -> {
            checkName(name);
            return kv.get(QueueService.SYSTEM_TENANT, namespace(tenant), name)
                    .map(stored -> stored instanceof JsonObject json ? Info.of(name, json) : null);
        });
    }

    /** Every secret's details, by name. */
    public Future<List<Info>> list(String tenant) {
        return guarded(() -> page(tenant, null, new ArrayList<>()));
    }

    /** The value, or null if there's none. Fails if it can't be opened (the secrets key changed). */
    public Future<String> value(String tenant, String name) {
        return guarded(() -> {
            checkName(name);
            return kv.get(QueueService.SYSTEM_TENANT, namespace(tenant), name).map(stored -> stored instanceof JsonObject json
                    ? box.open(json.getString("sealed"), context(tenant, name))
                    : null);
        });
    }

    /** Whether there was one. */
    public Future<Boolean> delete(String tenant, String name) {
        return guarded(() -> {
            checkName(name);
            return kv.delete(QueueService.SYSTEM_TENANT, namespace(tenant), name);
        });
    }

    /** Every secret of a deleted tenant. Works without a secrets key, so nothing is left behind. */
    public Future<Void> forget(String tenant) {
        return kv.list(QueueService.SYSTEM_TENANT, namespace(tenant), "", 1_000, null).compose(page -> {
            JsonArray keys = page.getJsonArray("keys", new JsonArray());
            Future<Void> all = Future.succeededFuture();
            for (Object key : keys) {
                all = all.compose(v -> kv.delete(QueueService.SYSTEM_TENANT, namespace(tenant), String.valueOf(key)).mapEmpty());
            }
            return all.compose(v -> keys.size() < 1_000 ? Future.succeededFuture() : forget(tenant));
        });
    }

    private Future<List<Info>> page(String tenant, String cursor, List<Info> into) {
        String ns = namespace(tenant);
        return kv.list(QueueService.SYSTEM_TENANT, ns, "", 1_000, cursor).compose(page -> {
            List<String> names = page.getJsonArray("keys", new JsonArray()).stream().map(String::valueOf).toList();
            Future<Void> all = Future.succeededFuture();
            for (String name : names) {
                all = all.compose(v -> kv.get(QueueService.SYSTEM_TENANT, ns, name).map(stored -> {
                    if (stored instanceof JsonObject json) {
                        into.add(Info.of(name, json));
                    }
                    return null;
                }));
            }
            String next = page.getString("cursor");
            return all.compose(v -> next == null ? Future.succeededFuture(into) : page(tenant, next, into));
        });
    }

    private Future<Integer> count(String tenant) {
        return kv.list(QueueService.SYSTEM_TENANT, namespace(tenant), "", MAX_PER_TENANT, null)
                .map(page -> page.getJsonArray("keys", new JsonArray()).size());
    }

    private <T> Future<T> guarded(java.util.function.Supplier<Future<T>> call) {
        if (box == null) {
            return Future.failedFuture(new Unavailable());
        }
        try {
            return call.get();
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static void checkName(String name) {
        if (!validName(name)) {
            throw new IllegalArgumentException("A secret's name is 1 to 128 letters, digits, '_', '.' or '-', starting with a letter or digit");
        }
    }

    static String namespace(String tenant) {
        return NAMESPACE_PREFIX + tenant;
    }

    private static String context(String tenant, String name) {
        return "secret/" + tenant + "/" + name;
    }
}
