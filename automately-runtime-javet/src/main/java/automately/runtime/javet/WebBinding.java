package automately.runtime.javet;

import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WHATWG globals' scripts (D-124), loaded when a script first touches one of them rather than with the prelude, so
 * isolates that never use ReadableStream or Response pay nothing for them. Each is compiled from V8's code cache once one
 * exists, as the prelude is (D-106); the cache only ever comes from this process, from our own scripts.
 */
public final class WebBinding {

    /** The scripts there are: js/web/<name>.js, each evaluating to an object of its classes. */
    static final Set<String> SCRIPTS = Set.of("streams", "blob", "fetch", "messaging", "codecs");

    private final V8Runtime v8;
    private final Map<String, byte[]> caches;

    WebBinding(V8Runtime v8, Map<String, byte[]> caches) {
        this.v8 = v8;
        this.caches = caches;
    }

    /** Evaluates the script and returns what it evaluates to; the prelude keeps it, so each loads once per isolate. */
    @V8Function(name = "load")
    public V8Value load(String name) throws JavetException {
        if (!SCRIPTS.contains(name)) {
            throw new IllegalArgumentException("No web script named " + name);
        }
        String source = Sources.WEB.get(name);
        byte[] cache = caches.get(name);
        try (com.caoccao.javet.values.reference.V8Script script = (cache == null ? v8.getExecutor(source) : v8.getExecutor(source, cache))
                .setResourceName("automately:web/" + name).compileV8Script()) {
            V8Value value = script.execute(true);
            if (cache == null) {
                caches.putIfAbsent(name, script.getCachedData());
            }
            return value;
        }
    }

    /** Read once per process. */
    private static final class Sources {
        static final Map<String, String> WEB = load();

        private static Map<String, String> load() {
            Map<String, String> out = new ConcurrentHashMap<>();
            for (String name : SCRIPTS) {
                try (java.io.InputStream in = WebBinding.class.getResourceAsStream("js/web/" + name + ".js")) {
                    if (in == null) {
                        throw new IllegalStateException("Missing js/web/" + name + ".js");
                    }
                    out.put(name, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return Map.copyOf(out);
        }
    }
}
