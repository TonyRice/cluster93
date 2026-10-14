package cluster93.core.storage;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * What the admin lets a tenant do with its own storage (D-057): which provider types it may use, how much of the
 * shared disk root it gets, and whether its endpoints may be private addresses. By default nothing is allowed.
 */
public record StoragePolicy(String tenant, Set<String> allow, long diskAllotmentBytes, boolean allowPrivateEndpoints) {

    public static final List<String> TYPES = List.of(StorageProvider.S3, StorageProvider.DISK);

    public static StoragePolicy none(String tenant) {
        return new StoragePolicy(tenant, Set.of(), 0, false);
    }

    public boolean allows(String type) {
        return allow.contains(type);
    }

    public JsonObject toJson() {
        return new JsonObject().put("tenant", tenant).put("allow", new JsonArray(allow.stream().sorted().toList()))
                .put("diskAllotmentMb", diskAllotmentBytes >> 20).put("allowPrivateEndpoints", allowPrivateEndpoints);
    }

    public static StoragePolicy fromJson(JsonObject json) {
        return new StoragePolicy(json.getString("tenant"), Set.copyOf(json.getJsonArray("allow", new JsonArray()).getList().stream()
                .map(String::valueOf).toList()), json.getLong("diskAllotmentMb", 0L) << 20, json.getBoolean("allowPrivateEndpoints", false));
    }
}
