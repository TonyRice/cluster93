package automately.core.runtime;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The packages one attempt can import, already fetched and checked against their hashes (D-089). A runtime resolves
 * imports from this alone, with no I/O: the job's lock decided the versions when it was submitted.
 *
 * @param byName every package in the job's lock, by name
 */
public record Packages(Map<String, Package> byName) {

    public static final Packages NONE = new Packages(Map.of());

    public Packages {
        byName = Map.copyOf(byName);
    }

    /**
     * One locked version.
     *
     * @param exports      what can be imported from it: {@code "."} or {@code "./subpath"} → a file
     * @param dependencies the packages it may import by name (the versions are the lock's)
     * @param files        its files' bytes, by path
     */
    public record Package(String name, String version, Map<String, String> exports, Set<String> dependencies, Map<String, byte[]> files) {

        public Package {
            exports = Map.copyOf(exports);
            dependencies = Set.copyOf(dependencies);
            files = Map.copyOf(files);
        }

        /** Where its file appears to code: {@code import.meta.url}, stack traces, and the resolver's module names. */
        public String url(String path) {
            return "file:///packages/" + name + "/" + version + "/" + path;
        }
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Serializes this lock (names, versions, exports, dependencies and file bytes as Base64) so an out-of-process
     * worker can rebuild it when it runs a script (D-127, D-128).
     *
     * @return the JSON form accepted by {@link #fromJson(JsonObject)}
     */
    public JsonObject toJson() {
        JsonObject names = new JsonObject();
        for (Map.Entry<String, Package> entry : byName.entrySet()) {
            Package pkg = entry.getValue();
            JsonObject files = new JsonObject();
            pkg.files().forEach((path, bytes) -> files.put(path, Base64.getEncoder().encodeToString(bytes)));
            names.put(entry.getKey(), new JsonObject()
                    .put("name", pkg.name())
                    .put("version", pkg.version())
                    .put("exports", new JsonObject(new LinkedHashMap<>(pkg.exports())))
                    .put("dependencies", new JsonArray(new java.util.ArrayList<>(pkg.dependencies())))
                    .put("files", files));
        }
        return new JsonObject().put("byName", names);
    }

    /**
     * Restores a lock serialized by {@link #toJson()}.
     *
     * @param json the serialized lock, or null/empty for {@link #NONE}
     * @return the restored lock
     */
    public static Packages fromJson(JsonObject json) {
        if (json == null) {
            return NONE;
        }
        JsonObject names = json.getJsonObject("byName");
        if (names == null || names.isEmpty()) {
            return NONE;
        }
        Map<String, Package> byName = new LinkedHashMap<>();
        for (String key : names.fieldNames()) {
            JsonObject pkg = names.getJsonObject(key);
            if (pkg == null) {
                continue;
            }
            Map<String, String> exports = new LinkedHashMap<>();
            JsonObject exportsJson = pkg.getJsonObject("exports");
            if (exportsJson != null) {
                exportsJson.forEach(e -> exports.put(e.getKey(), String.valueOf(e.getValue())));
            }
            Set<String> dependencies = new java.util.LinkedHashSet<>();
            for (Object dep : pkg.getJsonArray("dependencies", new JsonArray())) {
                dependencies.add(String.valueOf(dep));
            }
            Map<String, byte[]> files = new LinkedHashMap<>();
            JsonObject filesJson = pkg.getJsonObject("files");
            if (filesJson != null) {
                filesJson.forEach(e -> files.put(e.getKey(), Base64.getDecoder().decode(String.valueOf(e.getValue()))));
            }
            byName.put(key, new Package(pkg.getString("name", key), pkg.getString("version", "0.0.0"), exports, dependencies, files));
        }
        return byName.isEmpty() ? NONE : new Packages(byName);
    }
}
