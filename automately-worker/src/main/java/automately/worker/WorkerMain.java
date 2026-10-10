package automately.worker;

import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.NodeHostApis;
import automately.core.host.ServeRegistry;
import automately.core.host.SharedDataKvStore;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.ScriptRuntime;
import automately.core.runtime.StateSink;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

/**
 * The worker's entry (D-126): one engine, scripts run with in-process host APIs. Its commands measure what the engine
 * comparison needs; each prints one JSON line.
 * <ul>
 *   <li>{@code bench <workload.js> [--warmup-ms N] [--warmup-min N] [--measured N]}: one long-lived run that warms the
 *       workload up and then times each iteration, with nanosecond marks taken as the script logs them</li>
 *   <li>{@code idle <count>}: memory with that many idle services</li>
 *   <li>{@code hostile <name>}: one memory attack; run it under a cgroup cap to see what stops it</li>
 *   <li>{@code run <script.mjs>}: a script, once</li>
 *   <li>{@code start}: how long the process took to answer its first script</li>
 * </ul>
 * Every command takes {@code --heap-mb N} (default 128): each run's heap limit.
 */
public final class WorkerMain {

    /** What the benchmark harness logs around each measured iteration; the log sink stamps it as it arrives. */
    static final String MARK = "\u0001bench:";

    private WorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        long processStart = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis());
        if (args.length == 0) {
            System.err.println("Usage: worker-<engine> bench|idle|hostile|run|start [arguments] [--heap-mb N]");
            System.exit(2);
        }
        WorkerEngine engine = ServiceLoader.load(WorkerEngine.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("This worker was built without an engine: build it with -Pjavet or -Pgraaljs"));
        int heapMb = intOption(args, "--heap-mb", 128);
        Vertx vertx = Vertx.vertx();
        int code = 0;
        try {
            ScriptRuntime runtime = engine.runtime(vertx, heapMb, Math.max(2, intOption(args, "--threads", 2)));
            JsonObject out = switch (args[0]) {
                case "bench" -> bench(vertx, runtime, Files.readString(Path.of(args[1])), args);
                case "idle" -> idle(vertx, runtime, Integer.parseInt(args[1]));
                case "hostile" -> hostile(vertx, runtime, args[1]);
                case "run" -> describe(run(vertx, runtime, Files.readString(Path.of(args[1])), new JsonObject(), Duration.ofMinutes(10), null));
                case "start" -> start(vertx, runtime, processStart);
                default -> throw new IllegalArgumentException("Unknown command " + args[0]);
            };
            System.out.println(out.put("engine", engine.name()).put("heapMb", heapMb).encode());
        } catch (Exception e) {
            System.out.println(new JsonObject().put("engine", engine.name()).put("error", String.valueOf(e)).encode());
            code = 1;
        }
        // The engine's threads would keep the process up.
        System.exit(code);
    }

    private static int intOption(String[] args, String name, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return fallback;
    }

    static ExecutionResult run(Vertx vertx, ScriptRuntime runtime, String script, JsonObject input, Duration timeout, List<Long> marks)
            throws Exception {
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(false)),
                new ServeRegistry());
        String id = "job-" + System.nanoTime();
        Execution execution = new Execution(id, "tenant-a", script, input, timeout, JobContext.firstAttempt(id, OrphanPolicy.FAIL));
        return runtime.execute(execution, (level, line) -> {
                    if (line.startsWith(MARK) && marks != null) {
                        marks.add(System.nanoTime());
                    }
                }, StateSink.DISCARD, NodeHostApis.factory(node).create("tenant-a", id, false))
                .result().toCompletionStage().toCompletableFuture().get(timeout.toMillis() + 30_000, TimeUnit.MILLISECONDS);
    }

    private static JsonObject describe(ExecutionResult result) {
        return new JsonObject().put("status", result.status().name()).put("output", result.output()).put("error", result.error());
    }

    // --- bench ---

    private static JsonObject bench(Vertx vertx, ScriptRuntime runtime, String workload, String[] args) throws Exception {
        JsonObject input = new JsonObject()
                .put("warmupMs", intOption(args, "--warmup-ms", 20_000))
                .put("warmupMin", intOption(args, "--warmup-min", 200))
                .put("measured", intOption(args, "--measured", 50));
        String script = workload + "\n;\n" + resource("harness.js");
        List<Long> marks = Collections.synchronizedList(new ArrayList<>());
        ExecutionResult result = run(vertx, runtime, script, input, Duration.ofHours(1), marks);
        JsonObject out = new JsonObject().put("workload", args[1]).put("status", result.status().name());
        if (result.error() != null) {
            return out.put("error", result.error());
        }
        // Marks come in pairs: the first iteration's, then each measured one's.
        List<Double> times = new ArrayList<>();
        for (int i = 0; i + 1 < marks.size(); i += 2) {
            times.add((marks.get(i + 1) - marks.get(i)) / 1_000_000.0);
        }
        double first = times.isEmpty() ? -1 : times.remove(0);
        List<Double> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        JsonObject output = result.output() instanceof JsonObject o ? o : new JsonObject();
        return out.put("firstMs", round(first))
                .put("warmupIterations", output.getValue("warmupIterations"))
                .put("warmupMs", output.getValue("warmupMs"))
                .put("measured", times.size())
                .put("medianMs", round(percentile(sorted, 50)))
                .put("p90Ms", round(percentile(sorted, 90)))
                .put("p99Ms", round(percentile(sorted, 99)))
                .put("meanMs", round(sorted.stream().mapToDouble(Double::doubleValue).average().orElse(-1)))
                .put("rssMb", memory("VmRSS")).put("peakRssMb", memory("VmHWM")).put("privateMb", memory("RssAnon"));
    }

    private static double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    // --- idle ---

    private static JsonObject idle(Vertx vertx, ScriptRuntime runtime, int count) throws Exception {
        long before = memory("VmRSS");
        long privateBefore = memory("RssAnon");
        String service = "export default async () => { await new Promise((resolve) => setTimeout(resolve, 3600000)); return 1; };";
        for (int i = 0; i < count; i++) {
            NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(false)),
                    new ServeRegistry());
            String id = "idle-" + i;
            Execution execution = new Execution(id, "tenant-a", service, new JsonObject(), Duration.ofHours(1), JobContext.firstAttempt(id, OrphanPolicy.FAIL));
            runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD, NodeHostApis.factory(node).create("tenant-a", id, false));
        }
        Thread.sleep(5_000);
        System.gc();
        Thread.sleep(1_000);
        return new JsonObject().put("idle", count).put("rssMbBefore", before).put("rssMb", memory("VmRSS"))
                .put("privateMbBefore", privateBefore).put("privateMb", memory("RssAnon")).put("sharedMb", memory("RssShmem") + memory("RssFile"));
    }

    // --- hostile ---

    private static JsonObject hostile(Vertx vertx, ScriptRuntime runtime, String name) throws Exception {
        String script = switch (name) {
            case "heap-bomb" -> "export default () => { const a = []; for (;;) a.push({ x: 'y'.repeat(100) + a.length }); };";
            case "buffer-8gb" -> "export default () => new Uint8Array(2 ** 33).fill(1).length;";
            case "buffers-1gb" -> "export default () => { const h = []; for (let i = 0; i < 64; i++) h.push(new Uint8Array(16 * 1024 * 1024).fill(1)); return h.length; };";
            case "resizable-1gb" -> "export default () => { const r = new ArrayBuffer(1, { maxByteLength: 2 ** 31 }); r.resize(2 ** 30); new Uint8Array(r).fill(1); return r.byteLength; };";
            case "growable-sab-1gb" -> "export default () => { const g = new SharedArrayBuffer(1, { maxByteLength: 2 ** 31 }); g.grow(2 ** 30); new Uint8Array(g).fill(1); return g.byteLength; };";
            case "tolocale-churn" -> "export default () => { for (let i = 0; i < 100000; i++) new Date(i * 1000).toLocaleString('en', { timeZone: 'Asia/Tokyo', dateStyle: 'full' }); return 'done'; };";
            case "intl-held" -> "export default () => { const k = []; for (let i = 0; i < 100000; i++) k.push(new Intl.DateTimeFormat('en', { timeZone: ['Asia/Tokyo', 'Europe/Paris', 'America/New_York'][i % 3], dateStyle: 'full', timeStyle: 'long' })); return k.length; };";
            case "wasm-1gb" -> "const bytes = new Uint8Array([0,97,115,109,1,0,0,0,1,6,1,96,1,127,1,127,3,2,1,0,5,6,1,1,1,128,128,4,7,8,1,4,103,114,111,119,0,0,10,8,1,6,0,32,0,64,0,11]);"
                    + "export default () => { if (typeof WebAssembly === 'undefined') return 'no WebAssembly'; const { grow } = new WebAssembly.Instance(new WebAssembly.Module(bytes)).exports; return grow(16000); };";
            case "host-copy-1gb" -> "import crypto from 'node:crypto'; export default () => { const big = new Uint8Array(512 * 1024 * 1024).fill(1); const h = crypto.createHash('sha256'); h.update(big); h.update(big); return h.digest('hex').slice(0, 8); };";
            default -> throw new IllegalArgumentException("No hostile script named " + name);
        };
        long started = System.currentTimeMillis();
        ExecutionResult result = run(vertx, runtime, script, new JsonObject(), Duration.ofMinutes(5), null);
        return describe(result).put("hostile", name).put("ms", System.currentTimeMillis() - started)
                .put("peakRssMb", memory("VmHWM")).put("rssMb", memory("VmRSS"));
    }

    // --- start ---

    private static JsonObject start(Vertx vertx, ScriptRuntime runtime, long processStart) throws Exception {
        ExecutionResult result = run(vertx, runtime, "export default () => 'ready';", new JsonObject(), Duration.ofMinutes(1), null);
        return describe(result).put("msToFirstScript", System.currentTimeMillis() - processStart).put("rssMb", memory("VmRSS"))
                .put("privateMb", memory("RssAnon"));
    }

    // --- helpers ---

    /** A /proc/self/status figure, in MB (-1 if unknown). */
    static long memory(String field) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith(field + ":")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
                }
            }
        } catch (java.io.IOException e) {
            return -1;
        }
        return -1;
    }

    private static String resource(String name) {
        try (java.io.InputStream in = WorkerMain.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Unused, but keeps JsonArray's class reachable for scripts' outputs in a native image. */
    static final Class<?> JSON_ARRAY = JsonArray.class;
}
