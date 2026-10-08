package automately.runtime.javet;

import automately.core.packages.Imports;
import automately.core.runtime.Packages;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.callback.IV8ModuleResolver;
import com.caoccao.javet.values.reference.IV8Module;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps what a module imports to a module, from the job's packages alone, with no I/O (D-083 §5, D-089):
 * <ul>
 *   <li>Built-ins ({@code fs}, {@code automately:fs}) are the isolate's own.</li>
 *   <li>A bare name is a package in the job's lock, through its {@code exports}. The job's script can import any of
 *       them; a package only those it declared as dependencies (and itself).</li>
 *   <li>{@code ./} and {@code ../} paths are files of the same package, never outside it. The job's script has no
 *       files beside it.</li>
 *   <li>Everything else (URLs, absolute paths, names not in the lock) is refused with a message saying why.</li>
 * </ul>
 * Each file becomes one module, named {@code file:///packages/<name>/<version>/<path>}, kept for the run so a file
 * imported twice, or in a cycle, is the same module.
 */
final class ModuleResolver implements IV8ModuleResolver {

    /** The job's own script: its module name, and what {@code import.meta.url} says in it. */
    static final String JOB = "job.mjs";
    static final String JOB_URL = "file:///job.mjs";

    private final Packages packages;
    private final Map<String, String> builtIns;
    private final Map<String, IV8Module> modules = new HashMap<>();
    /** Which package file each compiled module is, so its own imports resolve against it. */
    private final Map<String, Location> locations = new HashMap<>();

    private record Location(Packages.Package pkg, String path) {
    }

    /** What an import names: a module name and either a built-in's source or a package file. */
    private record Target(String url, String builtIn, Location file) {
    }

    ModuleResolver(Packages packages, Map<String, String> builtIns) {
        this.packages = packages;
        this.builtIns = builtIns;
    }

    @Override
    public String getAbsoluteResourceName(V8Runtime runtime, String name, IV8Module referrer) {
        try {
            return target(name, referrer).url;
        } catch (ImportRefused | JavetException e) {
            // A name no module has, so Javet asks resolve(), which refuses it with the reason. (Given no name at all,
            // Javet gives up with its own "Cannot find package".)
            return "automately:refused:" + name;
        }
    }

    @Override
    public IV8Module resolve(V8Runtime runtime, String name, IV8Module referrer) throws JavetException {
        Target target = target(name, referrer);
        IV8Module known = modules.get(target.url);
        if (known != null) {
            return known;
        }
        String source = target.builtIn != null
                ? target.builtIn
                : Imports.rewrite(new String(target.file.pkg.files().get(target.file.path), StandardCharsets.UTF_8), target.url);
        IV8Module module = runtime.getExecutor(source).setResourceName(target.url).compileV8Module();
        modules.put(target.url, module);
        if (target.file != null) {
            locations.put(target.url, target.file);
        }
        return module;
    }

    private Target target(String name, IV8Module referrer) throws JavetException {
        if (builtIns.containsKey(name)) {
            return new Target(name, builtIns.get(name), null);
        }
        String from = referrer == null ? JOB : referrer.getResourceName();
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

    private static Target file(Packages.Package pkg, String path, String what) {
        if (!pkg.files().containsKey(path)) {
            throw new ImportRefused("Can't import " + what + ": " + pkg.name() + "@" + pkg.version() + " has no file " + path);
        }
        if (!(path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs"))) {
            throw new ImportRefused("Can't import " + what + ": " + path + " isn't a JavaScript module");
        }
        return new Target(pkg.url(path), null, new Location(pkg, path));
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
    static final class ImportRefused extends RuntimeException {
        ImportRefused(String message) {
            super(message, null, false, false);
        }
    }
}
