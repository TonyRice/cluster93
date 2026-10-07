package automately.core.storage;

import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * Which of a tenant's providers each new file goes to (D-057): the longest matching path prefix wins, then the
 * default; {@code null} for either means the cluster's own default backend.
 */
public record StorageRoutes(String tenant, String defaultProvider, Map<String, String> prefixes) {

    public static StorageRoutes none(String tenant) {
        return new StorageRoutes(tenant, null, Map.of());
    }

    /** The provider name for a path, or null for the cluster's default. */
    public String providerFor(String path) {
        String best = null;
        for (String prefix : prefixes.keySet()) {
            if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        String chosen = best != null ? prefixes.get(best) : defaultProvider;
        return chosen == null || chosen.equals(DEFAULT) ? null : chosen;
    }

    /** The name routes use for the cluster's own default backend. */
    public static final String DEFAULT = "default";

    public JsonObject toJson() {
        JsonObject map = new JsonObject();
        new TreeMap<>(prefixes).forEach(map::put);
        return new JsonObject().put("tenant", tenant).put("default", defaultProvider == null ? DEFAULT : defaultProvider).put("prefixes", map);
    }

    public static StorageRoutes fromJson(JsonObject json) {
        Map<String, String> prefixes = new TreeMap<>();
        json.getJsonObject("prefixes", new JsonObject()).forEach(e -> prefixes.put(e.getKey(), String.valueOf(e.getValue())));
        String chosen = json.getString("default");
        return new StorageRoutes(json.getString("tenant"), chosen == null || chosen.equals(DEFAULT) ? null : chosen, Map.copyOf(prefixes));
    }
}
