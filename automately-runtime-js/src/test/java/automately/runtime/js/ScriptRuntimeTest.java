package automately.runtime.js;

import automately.core.runtime.ScriptRuntime;

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

class ScriptRuntimeTest {

    private static Vertx vertx;
    private static ScriptRuntime runtime;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        runtime = EngineUnderTest.create(vertx, 64, 8);
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
        void consoleHasTheMethodsLibrariesCall() throws Exception {
            List<String> logs = new CopyOnWriteArrayList<>();
            run("""
                    export default () => {
                      console.debug('details');
                      console.dir({ a: 1 });
                      console.trace('here');
                      console.group('outer');
                      console.count('x');
                      console.count('x');
                      console.groupEnd();
                      console.assert(1 === 2, 'math');
                      console.assert(true, 'fine');
                      console.time('t');
                      console.timeEnd('t');
                      console.timeEnd('never');
                    }
                    """, new JsonObject(), Duration.ofSeconds(2), logs);

            assertThat(logs).hasSize(9);
            assertThat(logs.get(0)).isEqualTo("debug:details");
            assertThat(logs.get(1)).isEqualTo("info:{\"a\":1}");
            assertThat(logs.get(2)).startsWith("debug:here \n").contains("job.mjs");
            assertThat(logs.subList(3, 9)).containsExactly("info:outer", "info:  x: 1", "info:  x: 2", "error:Assertion failed: math",
                    logs.get(7), "warn:No such label 'never' for console.timeEnd()");
            assertThat(logs.get(7)).matches("info:t: \\d+ms");
        }

        @Test
        void aHugeLineIsClippedBeforeItReachesTheHost() throws Exception {
            List<String> logs = new CopyOnWriteArrayList<>();
            run("""
                    export default () => {
                      console.log('x'.repeat(5_000_000));
                      log.info('y'.repeat(20_000), { big: 'z'.repeat(20_000) });
                    }
                    """, new JsonObject(), Duration.ofSeconds(5), logs);

            assertThat(logs).hasSize(2);
            assertThat(logs.get(0)).isEqualTo("info:" + "x".repeat(16384) + " … [" + (5_000_000 - 16384) + " more characters]");
            assertThat(logs.get(1)).startsWith("info:" + "y".repeat(16384) + " … [3616 more characters]")
                    .endsWith("{\"fieldsOmitted\":\"20010 characters\"}");
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
                      // process is a global (D-096), but plain JavaScript: no host object behind it, and it reports only Node's release (D-115).
                      Object.getPrototypeOf(process) === Object.prototype && Object.keys(process.versions).join() === 'node',
                      Object.isFrozen(console),
                    ]
                    """);

            assertThat(result.output()).hasToString("[\"undefined\",\"undefined\",\"undefined\",true,true]");
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
            assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
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

    @Nested
    class TimersAndServices {

        private RunningExecution start(String script, boolean longRunning, Duration timeout, List<String> logs) {
            Execution execution = new Execution("svc-1", "tenant-a", script, new JsonObject(), timeout,
                    JobContext.firstAttempt("svc-1", OrphanPolicy.FAIL), longRunning);
            return runtime.execute(execution, (level, line) -> logs.add(line), StateSink.DISCARD);
        }

        private ExecutionResult await(RunningExecution running) throws Exception {
            return running.result().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }

        @Test
        void setTimeoutAndSleepResolveInOrder() throws Exception {
            ExecutionResult result = run("""
                    export default async (input, ctx) => {
                      const order = [];
                      setTimeout(() => order.push('timeout 30'), 30);
                      setTimeout((x) => order.push(x), 10, 'timeout 10');
                      await ctx.sleep(60);
                      order.push('slept 60');
                      return order;
                    }
                    """);

            assertThat(result.output()).isEqualTo(new JsonArray().add("timeout 10").add("timeout 30").add("slept 60"));
        }

        @Test
        void intervalsRepeatUntilCleared() throws Exception {
            ExecutionResult result = run("""
                    export default () => new Promise((resolve) => {
                      let ticks = 0;
                      const id = setInterval(() => { if (++ticks === 3) { clearInterval(id); resolve(ticks); } }, 5);
                    })
                    """);

            assertThat(result.output()).isEqualTo(3);
        }

        @Test
        void aClearedTimeoutNeverFires() throws Exception {
            ExecutionResult result = run("""
                    export default async (input, ctx) => {
                      let fired = false;
                      clearTimeout(setTimeout(() => { fired = true; }, 5));
                      await ctx.sleep(30);
                      return fired;
                    }
                    """);

            assertThat(result.output()).isEqualTo(false);
        }

        @Test
        void aJobWaitingOnNothingStillNeverSettles() throws Exception {
            ExecutionResult result = run("export default () => new Promise(() => { setTimeout(() => {}, 10); })");

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("never settles");
        }

        @Test
        void aJobWaitingForAStopWaitsAndCanBeCancelledGracefully() throws Exception {
            RunningExecution running = start("export default async (input, ctx) => { await ctx.stopRequested; return 'tidied'; }",
                    false, Duration.ofSeconds(10), new CopyOnWriteArrayList<>());
            Thread.sleep(200);
            assertThat(running.result().isComplete()).as("waiting for a stop is not 'never settles'").isFalse();

            running.stop("cancelled through the API", Duration.ofSeconds(5));

            ExecutionResult result = await(running);
            assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(result.output()).isEqualTo("tidied");
        }

        @Test
        void anErrorThrownInATimerFailsTheRun() throws Exception {
            ExecutionResult result = run("""
                    export default (input, ctx) => {
                      setTimeout(() => { throw new Error('tick exploded'); }, 5);
                      return ctx.sleep(1000);
                    }
                    """);

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("Uncaught error in timer callback").contains("tick exploded");
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
                "queueMicrotask(() => { throw new Error('micro exploded'); })|queueMicrotask callback",
                "process.nextTick(() => { throw new Error('micro exploded'); })|process.nextTick callback"})
        void anErrorThrownInAMicrotaskOrNextTickFailsTheRunAsInNode(String schedule, String where) throws Exception {
            ExecutionResult result = run("""
                    export default (input, ctx) => {
                      %s;
                      return ctx.sleep(1000);
                    }
                    """.formatted(schedule));

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("Uncaught error in " + where).contains("micro exploded");
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                // Rejected and never handled, while the script waits.
                "Promise.reject(new Error('left alone')); return ctx.sleep(1000);",
                // An async timer callback whose promise rejects: Node ignores the promise, so nothing handles it.
                "setTimeout(async () => { throw new Error('left alone'); }, 5); return ctx.sleep(1000);",
                // A handler added only after a later turn of the loop is too late, as in Node.
                "const p = Promise.reject(new Error('left alone')); await ctx.sleep(20); await p.catch(() => {});",
                // Rejected in the very turn the script returns.
                "Promise.reject(new Error('left alone')); return 'done';"})
        void anUnhandledRejectionFailsTheRunAsInNode(String body) throws Exception {
            ExecutionResult result = run("export default async (input, ctx) => { " + body + " }");

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("Unhandled promise rejection").contains("left alone");
        }

        @Test
        void rejectionsHandledInTheSameTurnOrHeardByAListenerDont() throws Exception {
            ExecutionResult result = run("""
                    export default async (input, ctx) => {
                      const heard = [];
                      // Handled later in the same turn: V8 reports it, then takes it back.
                      const p = Promise.reject(new Error('caught late'));
                      await null;
                      p.catch(() => heard.push('caught'));
                      // Heard by a listener: the job goes on, and handling it afterwards is reported too.
                      process.on('unhandledRejection', (reason, promise) => heard.push('unhandled: ' + reason.message));
                      process.on('rejectionHandled', () => heard.push('handled after all'));
                      const q = Promise.reject(new Error('heard'));
                      await ctx.sleep(20);
                      q.catch(() => {});
                      await ctx.sleep(20);
                      return heard;
                    }
                    """);

            assertThat(result.error()).isNull();
            if (EngineUnderTest.engine().reportsRejectionsHandledLater()) {
                assertThat((JsonArray) result.output()).containsExactly("caught", "unhandled: heard", "handled after all");
            } else {
                // The engine can't say a rejection was handled in a later turn: the rest of D-110 still holds.
                assertThat((JsonArray) result.output()).containsExactly("caught", "unhandled: heard");
            }
        }

        @Test
        void aThrowInAnUnhandledRejectionListenerFailsTheRun() throws Exception {
            ExecutionResult result = run("""
                    export default async (input, ctx) => {
                      process.on('unhandledRejection', () => { throw new Error('listener exploded'); });
                      Promise.reject(new Error('first'));
                      return ctx.sleep(1000);
                    }
                    """);

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("'unhandledRejection' listener").contains("listener exploded");
        }

        @Test
        void aModuleThatFailsToLoadIsReportedAsItselfNotAsARejection() throws Exception {
            ExecutionResult result = run("""
                    await null;
                    throw new Error('broken at load');
                    export default () => 1;
                    """);

            assertThat(result.status()).isEqualTo(JobStatus.FAILED);
            assertThat(result.error()).contains("broken at load").doesNotContain("Unhandled promise rejection");
        }

        @Test
        void timerCountIsLimited() throws Exception {
            ExecutionResult result = run("""
                    export default () => {
                      try { for (let i = 0; i < 20000; i++) setTimeout(() => {}, 100000); } catch (e) { return e.name; }
                    }
                    """);

            assertThat(result.output()).isEqualTo("RangeError");
        }

        @Test
        void timerGlobalsCannotBeReplaced() throws Exception {
            ExecutionResult result = run("""
                    export default () => { try { globalThis.setTimeout = () => 'hijacked'; } catch {} return setTimeout(() => {}, 1).constructor.name; }
                    """);

            // Still the isolate's own: it returns Node's Timeout (D-123).
            assertThat(result.output()).isEqualTo("Timeout");
        }

        @Test
        void aLongRunningScriptWaitsIdleUntilStoppedAndCanCleanUp() throws Exception {
            List<String> logs = new CopyOnWriteArrayList<>();
            RunningExecution running = start("""
                    export default async (input, ctx) => {
                      const { reason } = await ctx.stopRequested;
                      console.log('cleaning up: ' + reason);
                      return 'clean';
                    }
                    """, true, Duration.ZERO, logs);
            Thread.sleep(300);
            assertThat(running.result().isComplete()).as("still running while idle").isFalse();

            running.stop("service stopped", Duration.ofSeconds(5));
            ExecutionResult result = await(running);

            assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(result.output()).isEqualTo("clean");
            assertThat(result.error()).isEqualTo("Stopped: service stopped");
            assertThat(logs).containsExactly("cleaning up: service stopped");
        }

        @Test
        void aServiceOnIntervalsKeepsTickingUntilStopped() throws Exception {
            List<String> logs = new CopyOnWriteArrayList<>();
            RunningExecution running = start("""
                    export default (input, ctx) => {
                      setInterval(() => console.log('tick'), 20);
                      return ctx.stopRequested;
                    }
                    """, true, Duration.ZERO, logs);
            Thread.sleep(300);
            running.stop("enough", Duration.ofSeconds(5));
            ExecutionResult result = await(running);

            assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(logs.size()).isGreaterThanOrEqualTo(5);
        }

        @Test
        void aScriptThatIgnoresTheStopIsTerminatedAfterTheGracePeriod() throws Exception {
            RunningExecution running = start("export default () => { while (true) {} }", true, Duration.ZERO, new CopyOnWriteArrayList<>());
            Thread.sleep(100);
            long start = System.nanoTime();

            running.stop("redeploy", Duration.ofMillis(200));
            ExecutionResult result = await(running);

            assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(result.error()).contains("Stopped: redeploy").contains("grace period");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }

        @Test
        void cancellingAnIdleScriptEndsItPromptly() throws Exception {
            RunningExecution running = start("export default () => new Promise(() => {})", true, Duration.ZERO, new CopyOnWriteArrayList<>());
            Thread.sleep(200);
            long start = System.nanoTime();

            running.cancel("user asked");
            ExecutionResult result = await(running);

            assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(result.error()).isEqualTo("Cancelled: user asked");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }

        @Test
        void aTimeoutStillAppliesToIdleJobsWithTimers() throws Exception {
            RunningExecution running = start("export default (input, ctx) => ctx.sleep(60000)", false, Duration.ofMillis(200),
                    new CopyOnWriteArrayList<>());

            assertThat(await(running).status()).isEqualTo(JobStatus.TIMED_OUT);
        }

        @Test
        void exposesTheServiceContextFrozen() throws Exception {
            JsonObject service = new JsonObject().put("name", "poller").put("revision", 2).put("run", 3)
                    .put("config", new JsonObject().put("everyMs", 1000)).putNull("previousRun");
            JobContext context = new JobContext("svc-1", 1, OrphanPolicy.FAIL, null,
                    new JobState(new JsonObject().put("cursor", 41), List.of()), service);

            ExecutionResult result = run("""
                    export default (input, ctx) => {
                      try { ctx.service.config.everyMs = 1; } catch {}
                      return [ctx.service.name, ctx.service.revision, ctx.service.run, ctx.service.config.everyMs, ctx.checkpoint.cursor, Object.isFrozen(ctx.service.config)];
                    }
                    """, context, StateSink.DISCARD);

            assertThat(result.output()).isEqualTo(new JsonArray().add("poller").add(2).add(3).add(1000).add(41).add(true));
        }
    }
}
