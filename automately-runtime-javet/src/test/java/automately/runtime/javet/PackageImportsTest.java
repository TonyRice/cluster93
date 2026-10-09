package automately.runtime.javet;

import automately.core.job.JobStatus;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.Packages;
import automately.core.runtime.StateSink;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Importing packages in the isolate (D-089): resolved from the job's packages alone, with clear refusals. */
class PackageImportsTest {

    private static Vertx vertx;
    private static JavetScriptRuntime runtime;

    private static final Packages.Package STRINGS = pkg("strings", "1.4.0", Map.of(".", "index.js", "./report", "lib/report.js"), Set.of(),
            Map.of("index.js", "import { trim } from './lib/trim.js'; export const slug = s => trim(s).toLowerCase().replace(/ /g, '-');",
                    "lib/trim.js", "export const trim = s => s.trim();",
                    "lib/report.js", "import { slug } from '../index.js'; export const report = s => 'report:' + slug(s);",
                    "lib/escape.js", "import x from '../../outside.js';",
                    "data.json", "{}"));
    private static final Packages.Package APP = pkg("app", "2.0.0", Map.of(".", "index.js"), Set.of("strings"),
            Map.of("index.js", "import { slug } from 'strings'; import { other } from './cycle.js';"
                            + "export const run = s => slug(s) + other(); export const name = 'app';",
                    "cycle.js", "import { name } from './index.js'; export const other = () => '+' + name;",
                    "sneaky.js", "import 'secret';",
                    "meta.js", "export const url = import.meta.url;",
                    "throws.js", "throw new Error('broken at load');"));
    private static final Packages.Package SECRET = pkg("secret", "1.0.0", Map.of(".", "index.js"), Set.of(),
            Map.of("index.js", "export default 'not for app';"));

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        runtime = new JavetScriptRuntime(vertx, 64, 4);
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aScriptImportsPackagesAndTheirExports() throws Exception {
        ExecutionResult result = run("import { slug } from 'strings'; import { report } from 'strings/report'; import { run } from 'app';"
                + "export default () => [slug('  Hello World '), report('A B'), run('X Y')];", STRINGS, APP);
        assertThat(result.error()).isNull();
        assertThat((JsonArray) result.output()).containsExactly("hello-world", "report:a-b", "x-y+app");
    }

    @Test
    void literalDynamicImportsAndImportMetaWork() throws Exception {
        ExecutionResult result = run("export default async () => { const { slug } = await import('strings');"
                + " const meta = await import('app/meta'); return [slug('A B'), import.meta.url]; };",
                STRINGS, pkg("app", "2.0.0", Map.of("./meta", "meta.js"), Set.of(), Map.of("meta.js", "export const url = import.meta.url;")));
        assertThat(result.error()).isNull();
        assertThat((JsonArray) result.output()).containsExactly("a-b", "file:///job.mjs");

        ExecutionResult meta = run("import { url } from 'app'; export default () => url;",
                pkg("app", "2.0.0", Map.of(".", "meta.js"), Set.of(), Map.of("meta.js", "export const url = import.meta.url;")));
        assertThat(meta.output()).isEqualTo("file:///packages/app/2.0.0/meta.js");
    }

    @Test
    void aComputedImportRejects() throws Exception {
        ExecutionResult result = run("export default async () => { const name = 'strings'; try { await import(name); return 'loaded'; }"
                + " catch (e) { return 'refused'; } };", STRINGS);
        assertThat(result.output()).isEqualTo("refused");
    }

    @Test
    void namesOutsideTheLockAreRefusedWithAReason() throws Exception {
        assertThat(run("import x from 'nope'; export default () => x;", STRINGS).error())
                .contains("Can't import \"nope\" from the job's script: nope isn't in this job's packages");
        assertThat(run("import x from './local.js'; export default () => x;").error())
                .contains("it has no files beside it");
        assertThat(run("import x from 'https://esm.sh/x'; export default () => x;").error())
                .contains("it's a path or URL");
        assertThat(run("import w from 'worker_threads'; export default () => w;").error())
                .contains("Node's \"worker_threads\" module isn't available");
        assertThat(run("import { x } from 'strings/missing'; export default () => x;", STRINGS).error())
                .contains("strings@1.4.0 doesn't export \"./missing\"");
    }

    @Test
    void aPackageImportsOnlyItsDependenciesAndItsOwnFiles() throws Exception {
        Packages.Package sneaky = pkg("app", "2.0.0", Map.of(".", "sneaky.js"), Set.of(), APP.files().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> new String(e.getValue(), StandardCharsets.UTF_8))));
        assertThat(run("import 'app'; export default () => 1;", sneaky, SECRET).error())
                .contains("Can't import \"secret\" from app@2.0.0/sneaky.js: secret isn't one of app's dependencies");

        Packages.Package escaping = pkg("strings", "1.4.0", Map.of(".", "lib/escape.js"), Set.of(), Map.of("lib/escape.js", "import x from '../../outside.js';"));
        assertThat(run("import 'strings'; export default () => 1;", escaping).error()).contains("it's outside the package");

        Packages.Package json = pkg("strings", "1.4.0", Map.of(".", "index.js"), Set.of(), Map.of("index.js", "import d from './data.json';", "data.json", "{}"));
        assertThat(run("import 'strings'; export default () => 1;", json).error()).contains("data.json isn't a JavaScript module");
    }

    @Test
    void aModuleThatThrowsWhileLoadingFailsTheJobWithItsError() throws Exception {
        ExecutionResult result = run("import 'app'; export default () => 1;",
                pkg("app", "2.0.0", Map.of(".", "throws.js"), Set.of(), Map.of("throws.js", "throw new Error('broken at load');")));
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error()).contains("broken at load").contains("file:///packages/app/2.0.0/throws.js");

        ExecutionResult own = run("throw new Error('top level'); export default () => 1;");
        assertThat(own.error()).contains("top level");
    }

    @Test
    void topLevelAwaitIsWaitedFor() throws Exception {
        ExecutionResult result = run("const v = await new Promise(r => setTimeout(() => r(42), 10)); export default () => v;");
        assertThat(result.output()).isEqualTo(42);
    }

    @Test
    void builtInsStillResolve() throws Exception {
        ExecutionResult result = run("import fs from 'fs'; import { readFile } from 'node:fs/promises'; import * as afs from 'automately:fs';"
                + "export default () => [typeof fs.readFileSync, typeof readFile, typeof afs.readFile];");
        assertThat((JsonArray) result.output()).containsExactly("function", "function", "function");
    }

    @Test
    void theRuntimesBuiltInsAreTheOnesSubmissionKnows() {
        assertThat(JavetScriptRuntime.BUILT_IN_MODULES.keySet()).isEqualTo(automately.core.packages.Imports.BUILT_INS);
    }

    // --- helpers ---

    private static Packages.Package pkg(String name, String version, Map<String, String> exports, Set<String> deps, Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return new Packages.Package(name, version, exports, deps, bytes);
    }

    private static ExecutionResult run(String script, Packages.Package... packages) throws Exception {
        Map<String, Packages.Package> byName = new LinkedHashMap<>();
        for (Packages.Package p : packages) {
            byName.put(p.name(), p);
        }
        Execution execution = new Execution("job-1", "tenant-a", script, new JsonObject(), Duration.ofSeconds(5),
                JobContext.firstAttempt("job-1", OrphanPolicy.FAIL), false, new Packages(byName));
        return runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD)
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
