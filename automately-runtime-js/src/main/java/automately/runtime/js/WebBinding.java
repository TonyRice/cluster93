package automately.runtime.js;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WHATWG globals' scripts (D-124), loaded when a script first touches one of them rather than with the prelude, so
 * isolates that never use ReadableStream or Response pay nothing for them. The engine may reuse each script's compiled
 * form across runs, as Javet does with V8's code cache (D-106); the scripts only ever come from this process.
 */
public final class WebBinding {

    /** The scripts there are: js/web/<name>.js, each evaluating to an object of its classes. */
    static final Set<String> SCRIPTS = Set.of("streams", "blob", "fetch", "messaging", "codecs", "websocket");

    /** The built-ins a web script may take (D-125): WebSocket's handshake runs over node:http's client. */
    static final Set<String> MODULES = Set.of("node:http", "node:https", "node:crypto", "node:zlib");

    private final JsContext context;

    WebBinding(JsContext context) {
        this.context = context;
    }

    /** Evaluates the script and returns what it evaluates to; the prelude keeps it, so each loads once per isolate. */
    @HostFunction(name = "load")
    public JsHandle load(String name) throws JsEngineException {
        if (!SCRIPTS.contains(name)) {
            throw new IllegalArgumentException("No web script named " + name);
        }
        return context.loadWebScript(name, Sources.WEB.get(name));
    }

    /**
     * A built-in's namespace, evaluated if it wasn't yet: the same module instance the job's own imports get, through the
     * job's resolver. A classic script has no import(), and the WebSocket global needs node:http's socket-driven client.
     */
    @HostFunction(name = "module")
    public JsHandle module(String name) throws JsEngineException {
        if (!MODULES.contains(name)) {
            throw new IllegalArgumentException("No built-in for web scripts named " + name);
        }
        return context.builtinNamespace(name);
    }

    /** Read once per process. */
    static final class Sources {
        static final Map<String, String> WEB = load();

        private static Map<String, String> load() {
            Map<String, String> out = new ConcurrentHashMap<>();
            for (String name : SCRIPTS) {
                out.put(name, Builtins.resource("web/" + name + ".js"));
            }
            return Map.copyOf(out);
        }
    }
}
