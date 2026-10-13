package cluster93.runtime.javet;

import cluster93.runtime.js.ModuleSources;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.callback.IV8ModuleResolver;
import com.caoccao.javet.values.reference.IV8Module;

import java.util.HashMap;
import java.util.Map;

/**
 * V8's module loader over the engine-neutral {@link ModuleSources} (D-089, D-126): each import's source comes from there,
 * and built-ins the same in every job compile from V8's code cache after the first time (D-106).
 */
final class JavetModuleResolver implements IV8ModuleResolver {

    private final ModuleSources sources;
    /** The runtime's compiled built-ins, shared by every isolate; only for sources the same in every job. */
    private final Map<String, byte[]> codeCache;
    private final Map<String, IV8Module> modules = new HashMap<>();

    JavetModuleResolver(ModuleSources sources, Map<String, byte[]> codeCache) {
        this.sources = sources;
        this.codeCache = codeCache;
    }

    @Override
    public String getAbsoluteResourceName(V8Runtime runtime, String name, IV8Module referrer) {
        try {
            return sources.url(name, referrer == null ? null : referrer.getResourceName());
        } catch (ModuleSources.ImportRefused | JavetException e) {
            // A name no module has, so Javet asks resolve(), which refuses it with the reason. (Given no name at all,
            // Javet gives up with its own "Cannot find package".)
            return "c93:refused:" + name;
        }
    }

    @Override
    public IV8Module resolve(V8Runtime runtime, String name, IV8Module referrer) throws JavetException {
        ModuleSources.Target target = sources.target(name, referrer == null ? null : referrer.getResourceName());
        IV8Module known = modules.get(target.url());
        if (known != null) {
            return known;
        }
        // V8 checks the cache against the source and compiles afresh if it differs.
        boolean cacheable = codeCache != null && target.cacheable();
        byte[] cached = cacheable ? codeCache.get(target.url()) : null;
        IV8Module module = (cached == null ? runtime.getExecutor(target.source()) : runtime.getExecutor(target.source(), cached))
                .setResourceName(target.url()).compileV8Module();
        if (cacheable && cached == null && module instanceof com.caoccao.javet.values.reference.V8Module compiled) {
            codeCache.putIfAbsent(target.url(), compiled.getCachedData());
        }
        modules.put(target.url(), module);
        return module;
    }
}
