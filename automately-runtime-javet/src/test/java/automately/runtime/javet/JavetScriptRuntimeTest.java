package automately.runtime.javet;

import automately.core.job.JobStatus;
import automately.core.job.Attempt;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.StateSink;
import automately.core.state.JobState;
import automately.core.state.StateException;
import automately.core.state.StepRecord;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JavetScriptRuntimeTest {

    private static Vertx vertx;
    private static JavetScriptRuntime runtime;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        runtime = new JavetScriptRuntime(vertx, 64);
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static ExecutionResult run(String script) throws Exception {
        return run(script, new JsonObject(), Duration.ofSeconds(2), new CopyOnWriteArrayList<>());
    }

    private static ExecutionResult run(String script, JsonObject input, Duration timeout, List<String> logs) throws Exception {
        Execution execution = new Execution("job-1", "tenant-a", script, input, timeout, JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        return runtime.execute(execution, (level, line) -> logs.add(level + ":" + line), StateSink.DISCARD)
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static ExecutionResult run(String script, JobContext context, StateSink state) throws Exception {
        Execution execution = new Execution(context.jobId(), "tenant-a", script, new JsonObject(), Duration.ofSeconds(5), context);
        return runtime.execute(execution, (level, line) -> { }, state)
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** Records state writes, optionally failing them. */
    private static final class RecordingSink implements StateSink {

        final List<String> writes = new CopyOnWriteArrayList<>();
        StateException failWith;

        @Override
        public void saveCheckpoint(Object value) {
            fail();
            writes.add("checkpoint=" + Json.encode(value));
        }

        @Override
        public void completeStep(String name, Object result) {
            fail();
            writes.add("step " + name + "=" + Json.encode(result));
        }

        private void fail() {
            if (failWith != null) {
                throw failWith;
            }
        }
    }

    @Nested
    class WellBehavedScripts {

        @Test
        void returnsAValueComputedFromTheInput() throws Exception {
            ExecutionResult result = run("export default (input) => ({ greeting: `hello ${input.name}` })",
                    new JsonObject().put("name", "tony"), Duration.ofSeconds(2), new CopyOnWriteArrayList<>());

            assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(result.output()).isEqualTo(new JsonObject().put("greeting", "hello tony"));
        }

        @Test
        void awaitsAsyncFunctions() throws Exception {
            ExecutionResult result = run("""
                    export default async () => {
                      const parts = await Promise.all([1, 2, 3].map(async n => n * 2));
                      return parts.reduce((a, b) => a + b, 0);
                    }
                    """);

            assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(result.output()).isEqualTo(12);
        }

        @Test
        void sendsConsoleOutputToTheLogSink() throws Exception {
            List<String> logs = new CopyOnWriteArrayList<>();
            run("""
                    export default () => {
                      console.log('starting', { step: 1 });
                      console.error('something odd');
                    }
                    """, new JsonObject(), Duration.ofSeconds(2), logs);

            assertThat(logs).containsExactly("info:starting {\"step\":1}", "error:something odd");
        }

        @Test
        void undefinedResultBecomesNull() throws Exception {
            ExecutionResult result = run("export default () => {}");

            assertThat(result.status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(result.output()).isNull();
        }
    }

    @Nested
    class BrokenScripts {

        @Test
        void thrownErrorFailsTheJob() throws Exception {
            ExecutionResult result = run("export default () => { throw new Error('boom') }");

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("boom");
        }

        @Test
        void syntaxErrorFailsTheJob() throws Exception {
            ExecutionResult result = run("export default () => {");

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).containsIgnoringCase("SyntaxError");
        }

        @Test
        void missingDefaultExportFailsTheJob() throws Exception {
            ExecutionResult result = run("export const notDefault = 1");

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("default function");
        }

        @Test
        void promiseThatNeverSettlesFailsTheJob() throws Exception {
            ExecutionResult result = run("export default () => new Promise(() => {})");

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("never settles");
        }
    }

    @Nested
    class HostileScripts {

        @Test
        void infiniteLoopIsTerminated() throws Exception {
            long start = System.nanoTime();
            ExecutionResult result = run("export default () => { while (true) {} }",
                    new JsonObject(), Duration.ofMillis(300), new CopyOnWriteArrayList<>());

            assertThat(result.status()).isEqualTo(JobStatus.TIMED_OUT);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }

        @Test
        void infiniteLoopAfterAnAwaitIsTerminated() throws Exception {
            ExecutionResult result = run("export default async () => { await null; while (true) {} }",
                    new JsonObject(), Duration.ofMillis(300), new CopyOnWriteArrayList<>());

            assertThat(result.status()).isEqualTo(JobStatus.TIMED_OUT);
        }

        @Test
        void infiniteLoopAtModuleTopLevelIsTerminated() throws Exception {
            ExecutionResult result = run("while (true) {}\nexport default () => 1",
                    new JsonObject(), Duration.ofMillis(300), new CopyOnWriteArrayList<>());

            assertThat(result.status()).isEqualTo(JobStatus.TIMED_OUT);
        }

        @Test
        void memoryBombIsTerminatedWithoutTakingDownTheJvm() throws Exception {
            ExecutionResult result = run("""
                    export default () => {
                      const hoard = [];
                      while (true) hoard.push(new Array(100_000).fill('x'));
                    }
                    """, new JsonObject(), Duration.ofSeconds(20), new CopyOnWriteArrayList<>());

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("memory limit");
            assertThat(run("export default () => 'still alive'").output()).isEqualTo("still alive");
        }

        @Test
        void runtimeStaysUsableAfterATermination() throws Exception {
            run("export default () => { while (true) {} }", new JsonObject(), Duration.ofMillis(200), new CopyOnWriteArrayList<>());

            assertThat(run("export default () => 'ok'").output()).isEqualTo("ok");
        }

        @Test
        void globalStateDoesNotLeakBetweenJobs() throws Exception {
            run("globalThis.secret = 'tenant-a data'; export default () => 1");
            ExecutionResult result = run("export default () => typeof globalThis.secret");

            assertThat(result.output()).isEqualTo("undefined");
        }

        @Test
        void prototypePollutionDoesNotLeakBetweenJobs() throws Exception {
            run("Object.prototype.polluted = true; export default () => 1");
            ExecutionResult result = run("export default () => ({}).polluted === undefined");

            assertThat(result.output()).isEqualTo(true);
        }

        @Test
        void hostInternalsAreNotReachable() throws Exception {
            ExecutionResult result = run("""
                    export default () => [
                      typeof globalThis.__automately,
                      typeof globalThis.Java,
                      typeof globalThis.require,
                      typeof globalThis.process,
                      Object.isFrozen(console),
                    ]
                    """);

            assertThat(result.output()).hasToString("[\"undefined\",\"undefined\",\"undefined\",\"undefined\",true]");
        }
    }

    @Nested
    class JobContextApi {

        private final Attempt orphanedFirst = new Attempt(1, "node1", "inst-1", 100L, 200L, JobStatus.ORPHANED);

        @Test
        void describesAFirstAttempt() throws Exception {
            ExecutionResult result = run("""
                    export default (input, ctx) => ({
                      jobId: ctx.jobId, attempt: ctx.attempt, policy: ctx.policy, isRetry: ctx.isRetry,
                      isResume: ctx.isResume, previous: ctx.previous, checkpoint: ctx.checkpoint,
                      completedSteps: ctx.completedSteps, key: ctx.stepKey('a'), frozen: Object.isFrozen(ctx),
                    })
                    """, JobContext.firstAttempt("job-7", OrphanPolicy.FAIL), StateSink.DISCARD);

            assertThat(result.output()).isEqualTo(new JsonObject()
                    .put("jobId", "job-7").put("attempt", 1).put("policy", "fail").put("isRetry", false)
                    .put("isResume", false).putNull("previous").putNull("checkpoint")
                    .put("completedSteps", new JsonArray()).put("key", "job-7:a").put("frozen", true));
        }

        @Test
        void aResumedAttemptSkipsCompletedStepsAndRunsTheRest() throws Exception {
            JobState carried = new JobState(new JsonObject().put("page", 7),
                    List.of(new StepRecord("open", new JsonObject().put("batch", "b-1"), 1, 150L)));
            JobContext context = new JobContext("job-7", 2, OrphanPolicy.RESUME, orphanedFirst, carried);
            RecordingSink sink = new RecordingSink();

            ExecutionResult result = run("""
                    export default async (input, ctx) => {
                      const batch = await ctx.step('open', () => { throw new Error('must not run again'); });
                      const sent = await ctx.step('send', ({ key, attempt }) => ({ key, attempt }));
                      return { batch, sent, isResume: ctx.isResume, checkpoint: ctx.checkpoint,
                               completed: ctx.completedSteps, previous: ctx.previous.outcome };
                    }
                    """, context, sink);

            assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(result.output()).isEqualTo(new JsonObject()
                    .put("batch", new JsonObject().put("batch", "b-1"))
                    .put("sent", new JsonObject().put("key", "job-7:send").put("attempt", 2))
                    .put("isResume", true)
                    .put("checkpoint", new JsonObject().put("page", 7))
                    .put("completed", new JsonArray().add("open"))
                    .put("previous", "ORPHANED"));
            assertThat(sink.writes).containsExactly("step send={\"key\":\"job-7:send\",\"attempt\":2}");
        }

        @Test
        void aRetryStartsCleanButKnowsItIsARetry() throws Exception {
            ExecutionResult result = run("export default (input, ctx) => [ctx.isRetry, ctx.isResume, ctx.previous.node, ctx.completedSteps.length]",
                    new JobContext("job-7", 2, OrphanPolicy.RETRY, orphanedFirst, JobState.EMPTY), StateSink.DISCARD);

            assertThat(result.output()).isEqualTo(new JsonArray().add(true).add(false).add("node1").add(0));
        }

        @Test
        void savesCheckpoints() throws Exception {
            RecordingSink sink = new RecordingSink();

            run("export default async (input, ctx) => { await ctx.saveCheckpoint({ page: 1 }); await ctx.saveCheckpoint({ page: 2 }); }",
                    JobContext.firstAttempt("job-7", OrphanPolicy.RESUME), sink);

            assertThat(sink.writes).containsExactly("checkpoint={\"page\":1}", "checkpoint={\"page\":2}");
        }

        @Test
        void aFailingStepIsNotRecorded() throws Exception {
            RecordingSink sink = new RecordingSink();

            ExecutionResult result = run("export default async (input, ctx) => ctx.step('charge', () => { throw new Error('card declined') })",
                    JobContext.firstAttempt("job-7", OrphanPolicy.RESUME), sink);

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("card declined");
            assertThat(sink.writes).isEmpty();
        }

        @Test
        void stepNamesMustBeUnique() throws Exception {
            ExecutionResult result = run("export default async (input, ctx) => { await ctx.step('a', () => 1); await ctx.step('a', () => 2); }",
                    JobContext.firstAttempt("job-7", OrphanPolicy.RESUME), new RecordingSink());

            assertThat(result.error()).contains("already used");
        }

        @Test
        void aSupersededWriteSurfacesAsSupersededError() throws Exception {
            RecordingSink sink = new RecordingSink();
            sink.failWith = new StateException(StateException.Kind.SUPERSEDED, "Attempt 1 of job job-7 has been superseded");

            ExecutionResult result = run("""
                    export default async (input, ctx) => {
                      try { await ctx.step('a', () => 1); } catch (e) { return [e.name, e instanceof SupersededError, e.message]; }
                    }
                    """, JobContext.firstAttempt("job-7", OrphanPolicy.RESUME), sink);

            assertThat(result.output()).isEqualTo(new JsonArray().add("SupersededError").add(true).add("Attempt 1 of job job-7 has been superseded"));
        }

        @Test
        void aLimitSurfacesAsStateLimitError() throws Exception {
            RecordingSink sink = new RecordingSink();
            sink.failWith = new StateException(StateException.Kind.LIMIT, "too big");

            ExecutionResult result = run("""
                    export default async (input, ctx) => {
                      try { await ctx.saveCheckpoint('x'); } catch (e) { return e.name; }
                    }
                    """, JobContext.firstAttempt("job-7", OrphanPolicy.RESUME), sink);

            assertThat(result.output()).isEqualTo("StateLimitError");
        }

        @Test
        void cancelStopsARunningScript() throws Exception {
            Execution execution = new Execution("job-7", "tenant-a", "export default () => { while (true) {} }", new JsonObject(),
                    Duration.ofSeconds(20), JobContext.firstAttempt("job-7", OrphanPolicy.FAIL));
            RunningExecution running = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD);
            Thread.sleep(200);

            running.cancel("superseded");

            ExecutionResult result = running.result().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).isEqualTo("Cancelled: superseded");
        }

        @Test
        void errorClassesCannotBeReplaced() throws Exception {
            ExecutionResult result = run("""
                    export default () => {
                      try { globalThis.SupersededError = class {}; } catch {}
                      return SupersededError.name;
                    }
                    """, JobContext.firstAttempt("job-7", OrphanPolicy.FAIL), StateSink.DISCARD);

            assertThat(result.output()).isEqualTo("SupersededError");
        }
    }
}
