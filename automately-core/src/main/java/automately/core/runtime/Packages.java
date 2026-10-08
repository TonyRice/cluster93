package automately.core.runtime;

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
}
