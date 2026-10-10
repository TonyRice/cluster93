package automately.runtime.js;

import automately.core.packages.Imports;
import automately.core.runtime.Packages;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps what a module imports to a module, from the job's packages alone, with no I/O (D-083 §5, D-089). Engine-neutral
 * (D-126): each engine's module loader asks this for the URL and source of what's imported.
 * <ul>
 *   <li>Built-ins ({@code fs}, {@code automately:fs}, and stand-ins for Node's {@code path}, {@code events},
 *       {@code buffer}, {@code url} and {@code util}) are the isolate's own.</li>
 *   <li>A bare name is a package in the job's lock, through its {@code exports}. The job's script can import any of
 *       them; a package only those it declared as dependencies (and itself).</li>
 *   <li>{@code ./} and {@code ../} paths are files of the same package, never outside it. The job's script has no
 *       files beside it.</li>
 *   <li>Everything else (URLs, absolute paths, names not in the lock) is refused with a message saying why.</li>
 * </ul>
 * Each file becomes one module, named {@code file:///packages/<name>/<version>/<path>}; the engine keeps it for the run
 * so a file imported twice, or in a cycle, is the same module.
 */
public final class ModuleSources {

    /** The job's own script: its module name, and what {@code import.meta.url} says in it. */
    public static final String JOB = "job.mjs";
    public static final String JOB_URL = "file:///job.mjs";

    private final Packages packages;
    /** Every name a built-in is imported as → its module name; and each module's source. */
    private final Map<String, String> builtIns;
    private final Map<String, String> builtInSources;
    /** Which package file each module named so far is, so its own imports resolve against it. */
    private final Map<String, Location> locations = new HashMap<>();

    private record Location(Packages.Package pkg, String path) {
    }

    /**
     * What an import names: the module's URL (its name for the engine, and its cache key) and its source.
     * {@code cacheable} says the source is the one shared by every job, so an engine may reuse its compiled form.
     */
    public record Target(String url, String source, boolean cacheable) {
    }

    public ModuleSources(Packages packages, Map<String, String> builtIns, Map<String, String> builtInSources) {
        this.packages = packages;
        this.builtIns = builtIns;
        this.builtInSources = builtInSources;
    }

    /** The URL {@code name} resolves to from module {@code referrerUrl} (null for the job's script); throws {@link ImportRefused}. */
    public String url(String name, String referrerUrl) {
        return resolve(name, referrerUrl).url;
    }

    /** The module {@code name} resolves to from module {@code referrerUrl} (null for the job's script); throws {@link ImportRefused}. */
    public Target target(String name, String referrerUrl) {
        Resolved resolved = resolve(name, referrerUrl);
        if (resolved.builtIn != null) {
            // The shared source, not process or os, which carry the job's own values.
            return new Target(resolved.url, resolved.builtIn, resolved.builtIn == Builtins.BUILT_IN_SOURCES.get(resolved.url));
        }
        locations.put(resolved.url, resolved.file);
        String source = Imports.rewrite(new String(resolved.file.pkg.files().get(resolved.file.path), StandardCharsets.UTF_8), resolved.url);
        return new Target(resolved.url, source, false);
    }

    private record Resolved(String url, String builtIn, Location file) {
    }

    private Resolved resolve(String name, String referrerUrl) {
        if (builtIns.containsKey(name)) {
            String module = builtIns.get(name);
            return new Resolved(module, builtInSources.get(module), null);
        }
        String from = referrerUrl == null ? JOB : referrerUrl;
        Location here = locations.get(from);
        String who = here == null ? "the job's script" : here.pkg.name() + "@" + here.pkg.version() + "/" + here.path;
        if (Imports.isPath(name)) {
            if (!(name.startsWith("./") || name.startsWith("../"))) {
                throw new ImportRefused("Can't import \"" + name + "\" from " + who + ": it's a path or URL; code is imported from "
                        + "packages, by name, never fetched");
            }
            if (here == null) {
                throw new ImportRefused("Can't import \"" + name + "\" from " + who + ": it has no files beside it, so it imports packages by name");
            }
            String path = join(here.path, name);
            if (path == null) {
                throw new ImportRefused("Can't import \"" + name + "\" from " + who + ": it's outside the package");
            }
            return file(here.pkg, path, "\"" + name + "\" from " + who);
        }
        String why = Imports.notAPackage(name);
        if (why != null) {
            throw new ImportRefused("Can't import \"" + name + "\" from " + who + ": " + why);
        }
        String packageName = Imports.packageName(name);
        if (here != null && !packageName.equals(here.pkg.name()) && !here.pkg.dependencies().contains(packageName)) {
            throw new ImportRefused("Can't import \"" + name + "\" from " + who + ": " + packageName + " isn't one of "
                    + here.pkg.name() + "'s dependencies");
        }
        Packages.Package pkg = packages.byName().get(packageName);
        if (pkg == null) {
            throw new ImportRefused("Can't import \"" + name + "\" from " + who + ": " + packageName + " isn't in this job's packages. "
                    + "A job's packages are found when it's submitted, from imports with literal names");
        }
        String subpath = Imports.subpath(name);
        String file = pkg.exports().get(subpath);
        if (file == null) {
            throw new ImportRefused("Can't import \"" + name + "\" from " + who + ": " + pkg.name() + "@" + pkg.version() + " doesn't export "
                    + (subpath.equals(".") ? "a main module" : "\"" + subpath + "\""));
        }
        return file(pkg, file, "\"" + name + "\" from " + who);
    }

    private static Resolved file(Packages.Package pkg, String path, String what) {
        if (!pkg.files().containsKey(path)) {
            throw new ImportRefused("Can't import " + what + ": " + pkg.name() + "@" + pkg.version() + " has no file " + path);
        }
        if (!(path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs"))) {
            throw new ImportRefused("Can't import " + what + ": " + path + " isn't a JavaScript module");
        }
        return new Resolved(pkg.url(path), null, new Location(pkg, path));
    }

    /** {@code relative} against the folder of {@code path}, both inside a package; null if it climbs out of it. */
    static String join(String path, String relative) {
        Deque<String> parts = new ArrayDeque<>();
        String[] base = path.split("/");
        for (int i = 0; i < base.length - 1; i++) {
            parts.addLast(base[i]);
        }
        for (String part : relative.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    /** An import that can't be resolved; its message is what the script's error says. */
    public static final class ImportRefused extends RuntimeException {
        public ImportRefused(String message) {
            super(message, null, false, false);
        }
    }
}
