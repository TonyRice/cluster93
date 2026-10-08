package automately.core.storage;

import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * Which of a tenant's providers each new file goes to (D-057): the longest matching path prefix wins, then the
 * default; {@code null} for either means the cluster's own default backend.
 *
 * <p>A prefix ending in {@code /} is a <em>mount</em> (D-078): the folder of that name is bound to the provider. A
 * mount can be read-only, which refuses new files, deletes and moves beneath it.
 */
public record StorageRoutes(String tenant, String defaultProvider, Map<String, String> prefixes, java.util.Set<String> readOnly) {

    public StorageRoutes {
        readOnly = readOnly == null ? java.util.Set.of() : java.util.Set.copyOf(readOnly);
    }

    public StorageRoutes(String tenant, String defaultProvider, Map<String, String> prefixes) {
        this(tenant, defaultProvider, prefixes, java.util.Set.of());
    }

    public static StorageRoutes none(String tenant) {
        return new StorageRoutes(tenant, null, Map.of());
    }

    /** The mounts: folder path (no trailing slash) → provider. */
    public Map<String, String> mounts() {
        Map<String, String> mounts = new TreeMap<>();
        prefixes.forEach((prefix, provider) -> {
            if (prefix.endsWith("/")) {
                mounts.put(prefix.substring(0, prefix.length() - 1), provider);
            }
        });
        return mounts;
    }

    /** Whether {@code path} is beneath a read-only mount (the mount's own folder isn't). */
    public boolean readOnlyAt(String path) {
        return readOnly.stream().anyMatch(prefix -> path.startsWith(prefix));
    }

    public StorageRoutes withMount(String folder, String provider, boolean readOnlyMount) {
        Map<String, String> next = new TreeMap<>(prefixes);
        next.put(folder + "/", provider);
        java.util.Set<String> ro = new java.util.TreeSet<>(readOnly);
        if (readOnlyMount) {
            ro.add(folder + "/");
        } else {
            ro.remove(folder + "/");
        }
        return new StorageRoutes(tenant, defaultProvider, next, ro);
    }

    public StorageRoutes withoutMount(String folder) {
        Map<String, String> next = new TreeMap<>(prefixes);
        next.remove(folder + "/");
        java.util.Set<String> ro = new java.util.TreeSet<>(readOnly);
        ro.remove(folder + "/");
        return new StorageRoutes(tenant, defaultProvider, next, ro);
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
        return new JsonObject().put("tenant", tenant).put("default", defaultProvider == null ? DEFAULT : defaultProvider).put("prefixes", map)
                .put("readOnly", new io.vertx.core.json.JsonArray(new java.util.TreeSet<>(readOnly).stream().toList()));
    }

    public static StorageRoutes fromJson(JsonObject json) {
        Map<String, String> prefixes = new TreeMap<>();
        json.getJsonObject("prefixes", new JsonObject()).forEach(e -> prefixes.put(e.getKey(), String.valueOf(e.getValue())));
        String chosen = json.getString("default");
        java.util.Set<String> readOnly = new java.util.TreeSet<>();
        json.getJsonArray("readOnly", new io.vertx.core.json.JsonArray()).forEach(p -> readOnly.add(String.valueOf(p)));
        return new StorageRoutes(json.getString("tenant"), chosen == null || chosen.equals(DEFAULT) ? null : chosen, Map.copyOf(prefixes), readOnly);
    }
}
