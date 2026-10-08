package automately.core.host;

import automately.core.runtime.Packages;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * {@code /packages} in a job's file system (D-083 §5, D-091): every file of every package in the job's lock, at
 * {@code /packages/<name>/<version>/<path>}, the same path as its {@code import.meta.url}. Read-only, and answered from
 * the files fetched before the job started, so a package can read its own JSON, templates and WebAssembly.
 */
final class PackageFiles {

    static final String ROOT = "packages";

    /** Every file by its path under {@code /packages}, sorted so a folder's entries are one range. */
    private final NavigableMap<String, byte[]> files = new TreeMap<>();

    PackageFiles(Packages packages) {
        for (Packages.Package pkg : packages.byName().values()) {
            for (Map.Entry<String, byte[]> file : pkg.files().entrySet()) {
                files.put(pkg.name() + "/" + pkg.version() + "/" + file.getKey(), file.getValue());
            }
        }
    }

    boolean isEmpty() {
        return files.isEmpty();
    }

    /** The file at {@code rel} (a path under {@code /packages}), or null. */
    byte[] read(String rel) {
        return files.get(rel);
    }

    /** {@code {kind, size}} for a file or folder at {@code rel}, or null if there's neither. */
    JsonObject stat(String rel) {
        byte[] file = files.get(rel);
        if (file != null) {
            return new JsonObject().put("kind", "file").put("size", (long) file.length).put("modifiedAt", 0L);
        }
        return isDir(rel) ? new JsonObject().put("kind", "dir").put("size", 0L).put("modifiedAt", 0L) : null;
    }

    boolean isDir(String rel) {
        if (rel.isEmpty()) {
            return true;
        }
        String prefix = rel + "/";
        String next = files.ceilingKey(prefix);
        return next != null && next.startsWith(prefix);
    }

    /** One folder's entries, by name; null if it isn't a folder. */
    JsonArray list(String rel) {
        if (!isDir(rel)) {
            return null;
        }
        String prefix = rel.isEmpty() ? "" : rel + "/";
        Map<String, JsonObject> entries = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : files.tailMap(prefix, true).entrySet()) {
            if (!file.getKey().startsWith(prefix)) {
                break;
            }
            String rest = file.getKey().substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                entries.put(rest, new JsonObject().put("name", rest).put("kind", "file").put("size", (long) file.getValue().length));
            } else {
                entries.putIfAbsent(rest.substring(0, slash), new JsonObject().put("name", rest.substring(0, slash)).put("kind", "dir").put("size", 0L));
            }
        }
        return new JsonArray(java.util.List.copyOf(entries.values()));
    }
}
