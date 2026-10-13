package cluster93.runtime.javet;

import cluster93.runtime.js.JsContext;
import cluster93.runtime.js.JsEngine;
import cluster93.runtime.js.JsEngineException;
import com.caoccao.javet.interop.options.V8Flags;
import com.caoccao.javet.interop.options.V8RuntimeOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V8, through Javet (D-002, D-126): an isolate per run, with V8's i18n build and the ICU data shipped with the runtime
 * (D-093). Keeps what isolates share: the compiled prelude, web scripts and built-ins (D-106).
 */
public final class JavetEngine implements JsEngine {

    /** ICU's data, which V8's i18n build needs for Intl and Unicode regular expressions (D-093): the runtime's version. */
    static final String ICU_DATA = "icudt78l.dat";

    /** V8's compiled prelude, from the first isolate that ran it (D-106); null until then. */
    private volatile byte[] preludeCache;
    /** The WHATWG globals' compiled scripts (D-124), shared by every isolate as the prelude's is. */
    private final Map<String, byte[]> webCaches = new ConcurrentHashMap<>();
    /** V8's compiled built-in modules, by module name (D-106). */
    private final Map<String, byte[]> moduleCache = new ConcurrentHashMap<>();

    /**
     * @param maxHeapMb per-isolate heap limit. V8 applies this flag process-wide and only before the first isolate is
     *                  created, so the first engine constructed in a JVM decides it.
     */
    public JavetEngine(int maxHeapMb) {
        configureHeapLimit(maxHeapMb);
    }

    @Override
    public JsContext newContext(Runnable memoryLimitReached) throws JsEngineException {
        return new JavetContext(this, memoryLimitReached);
    }

    byte[] preludeCache() {
        return preludeCache;
    }

    void preludeCache(byte[] cache) {
        preludeCache = cache;
    }

    Map<String, byte[]> webCaches() {
        return webCaches;
    }

    Map<String, byte[]> moduleCache() {
        return moduleCache;
    }

    private static synchronized void configureHeapLimit(int maxHeapMb) {
        V8Flags flags = V8RuntimeOptions.V8_FLAGS;
        if (!flags.isSealed()) {
            flags.setMaxHeapSize(maxHeapMb);
            flags.setIcuDataFile(icuDataFile());
        }
    }

    /**
     * Writes the ICU data shipped with the runtime to a file V8 can map, once per host. V8 needs a path, and the
     * data is in the jar, so it goes to a shared file in the temp directory that is reused across processes.
     */
    private static String icuDataFile() {
        String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
        java.nio.file.Path target = java.nio.file.Path.of(tmpDir, "cluster93-" + ICU_DATA);
        try {
            if (java.nio.file.Files.exists(target) && java.nio.file.Files.size(target) > 0) {
                return target.toString();
            }
            try (java.io.InputStream in = JavetEngine.class.getResourceAsStream("icu/" + ICU_DATA)) {
                if (in == null) {
                    throw new IllegalStateException("Missing ICU data (icu/" + ICU_DATA + "): the build downloads it; run ./mvnw package");
                }
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile(java.nio.file.Path.of(tmpDir), "cluster93-icu-", ".tmp");
                java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try {
                    java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException ignored) {
                    // Another process wrote it concurrently
                    java.nio.file.Files.deleteIfExists(tmp);
                }
                return target.toString();
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
