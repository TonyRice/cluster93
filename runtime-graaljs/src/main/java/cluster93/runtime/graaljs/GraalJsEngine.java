package cluster93.runtime.graaljs;

import cluster93.runtime.js.JsContext;
import cluster93.runtime.js.JsEngine;
import cluster93.runtime.js.JsEngineException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded GraalJS (D-126): GraalVM's JavaScript engine through its polyglot API, in this process.
 *
 * <p>One polyglot {@link Engine} per instance: its contexts share parsed and compiled code, as Javet's isolates share
 * V8's code cache (D-106). In the default {@link Mode#ISOLATED} mode the engine spawns its own polyglot isolate, with its
 * own heap, and each run's context has a heap limit there; buffers, Intl objects and the rest live in that heap, so the
 * limit bounds them (unlike V8's, which bounds only its JavaScript heap).
 *
 * <p>The policy is GraalVM's {@code TRUSTED}, which only says which options GraalVM validates: the stricter policies
 * refuse the stack-trace API and rejection tracking Node's behaviour needs. Every context is locked down by hand
 * instead: no host classes, no host access but byte buffers and our own proxies, no I/O but the run's modules, no
 * threads, processes, native code, environment or polyglot access, and GraalJS's own extra globals switched off.
 */
public final class GraalJsEngine implements JsEngine {

    /** Where the guest runs. */
    public enum Mode {
        /** In the engine's own polyglot isolate, with a heap limit per run (the default). */
        ISOLATED,
        /** In this JVM's heap: no limit per run, only the JVM's own. */
        IN_PROCESS
    }

    private static final String HELPERS = resource("helpers.js");

    private final Mode mode;
    private final int maxHeapMb;
    private final Engine engine;
    private final HostAccess hostAccess = HostAccess.newBuilder().allowBufferAccess(true).build();
    private final Source helpers = Source.newBuilder("js", HELPERS, "c93:graaljs-helpers").cached(true).buildLiteral();
    private final Map<String, String> sharedSources = new ConcurrentHashMap<>();
    private final Map<String, Source> webScripts = new ConcurrentHashMap<>();
    private volatile Source prelude;

    /**
     * @param maxHeapMb each run's heap limit (in {@link Mode#ISOLATED} mode)
     * @param contexts  how many runs may be live at once, which sizes the isolate's own limit
     */
    public GraalJsEngine(Mode mode, int maxHeapMb, int contexts) {
        this.mode = mode;
        this.maxHeapMb = maxHeapMb;
        Engine.Builder builder = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream());
        if (mode == Mode.ISOLATED) {
            builder.option("engine.SpawnIsolate", "true")
                    .option("engine.MaxIsolateMemory", ((long) maxHeapMb * Math.max(1, contexts) + 256) + "MB");
        }
        this.engine = builder.build();
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public JsContext newContext(Runnable memoryLimitReached) throws JsEngineException {
        return new GraalJsContext(this, memoryLimitReached);
    }

    Context newContext(FileSystem files) throws JsEngineException {
        Context.Builder builder = Context.newBuilder("js").engine(engine)
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(name -> false)
                .allowIO(IOAccess.newBuilder().fileSystem(files).build())
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .in(InputStream.nullInputStream())
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .allowExperimentalOptions(true)
                // Node's: Error.captureStackTrace and prepareStackTrace, Temporal, and unhandled-rejection tracking.
                .option("js.stack-trace-api", "true")
                .option("js.temporal", "true")
                .option("js.unhandled-rejections", "handler")
                .option("js.esm-eval-returns-exports", "true")
                .option("js.locale", "en-US")
                // GraalJS's own globals, which neither Node nor the isolate has.
                .option("js.console", "false")
                .option("js.print", "false")
                .option("js.load", "false")
                .option("js.polyglot-builtin", "false")
                .option("js.java-package-globals", "false")
                .option("js.global-arguments", "false");
        if (mode == Mode.ISOLATED) {
            builder.option("sandbox.MaxHeapMemory", maxHeapMb + "MB");
        }
        try {
            return builder.build();
        } catch (RuntimeException e) {
            throw new JsEngineException(e.getMessage(), e);
        }
    }

    Source helpers() {
        return helpers;
    }

    Source prelude(String source) {
        Source known = prelude;
        if (known == null || !known.getCharacters().toString().equals(source)) {
            known = Source.newBuilder("js", source, "c93:prelude").cached(true).buildLiteral();
            prelude = known;
        }
        return known;
    }

    Source webScript(String name, String source) {
        return webScripts.computeIfAbsent(name, n -> Source.newBuilder("js", source, "c93:web/" + n).cached(true).buildLiteral());
    }

    Map<String, String> sharedSources() {
        return sharedSources;
    }

    private static String resource(String file) {
        try (InputStream in = GraalJsEngine.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + file);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
