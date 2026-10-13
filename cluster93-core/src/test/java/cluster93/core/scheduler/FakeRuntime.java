package cluster93.core.scheduler;

import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.LogSink;
import cluster93.core.runtime.RunningExecution;
import cluster93.core.runtime.ScriptRuntime;
import cluster93.core.runtime.StateSink;
import cluster93.core.state.StateException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A runtime whose "scripts" are keywords:
 * <ul>
 *   <li>{@code hold}: runs until released or cancelled</li>
 *   <li>{@code crash}: the runtime itself fails</li>
 *   <li>{@code resumable}: runs steps a and b, holds on attempt 1 (so the test can kill the node), then runs
 *       step c. Completed steps carried into a resumed attempt are skipped, like {@code ctx.step}</li>
 *   <li>{@code late-step}: waits until released, then tries to complete step "late"; a rejected write is
 *       recorded in {@link #stateErrors}. Models a zombie attempt writing after a takeover</li>
 *   <li>anything else: returns the script upper-cased</li>
 * </ul>
 * Steps run on a worker thread because {@link StateSink} calls block.
 */
final class FakeRuntime implements ScriptRuntime {

    private final Vertx vertx;
    private final Map<String, Promise<ExecutionResult>> held = new ConcurrentHashMap<>();
    /** Every step body that actually ran, as {@code name@attempt}. */
    final List<String> executedSteps = new CopyOnWriteArrayList<>();
    /** Graceful stop requests, as {@code jobId#attempt: reason}. */
    final List<String> stops = new CopyOnWriteArrayList<>();
    /** Every execution started, in order: {@code jobId#attempt}. Services also record {@code service run N}. */
    final List<String> started = new CopyOnWriteArrayList<>();
    /** The contexts executions were started with, by {@code jobId#attempt}. */
    final Map<String, cluster93.core.runtime.JobContext> contexts = new ConcurrentHashMap<>();
    /** Cancellations, as {@code jobId#attempt: reason}. */
    final List<String> cancellations = new CopyOnWriteArrayList<>();
    /** State writes that were rejected, as {@code KIND: message}. */
    final List<String> stateErrors = new CopyOnWriteArrayList<>();
    private final Map<String, Promise<Void>> gates = new ConcurrentHashMap<>();

    FakeRuntime(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public RunningExecution execute(Execution execution, LogSink logs, StateSink state, cluster93.core.runtime.api.HostApis host) {
        logs.log("info", "running " + execution.script() + " attempt " + execution.context().attempt());
        String key = execution.jobId() + "#" + execution.context().attempt();
        started.add(key);
        contexts.put(key, execution.context());
        Promise<ExecutionResult> promise = Promise.promise();
        switch (execution.script()) {
            case "hold" -> held.put(key, promise);
            case "crash" -> promise.fail("engine exploded");
            case "fail" -> promise.complete(ExecutionResult.failed("script failed"));
            case "checkpoint" -> vertx.<Void>executeBlocking(() -> {
                Object previous = execution.context().carried().checkpoint();
                int count = previous == null ? 1 : ((Number) ((io.vertx.core.json.JsonObject) previous).getValue("count")).intValue() + 1;
                state.saveCheckpoint(new io.vertx.core.json.JsonObject().put("count", count));
                return null;
            }).onSuccess(v -> promise.tryComplete(ExecutionResult.failed("crashed after checkpoint"))).onFailure(promise::tryFail);
            case "resumable" -> vertx.<Void>executeBlocking(() -> {
                step(execution, state, "a");
                step(execution, state, "b");
                return null;
            }).onSuccess(v -> {
                if (execution.context().attempt() == 1) {
                    held.put(key, promise);
                    return;
                }
                vertx.<Void>executeBlocking(() -> {
                    step(execution, state, "c");
                    return null;
                }).onSuccess(x -> promise.tryComplete(ExecutionResult.succeeded(new JsonArray(List.of("a", "b", "c")))))
                        .onFailure(promise::tryFail);
            }).onFailure(promise::tryFail);
            case "late-step" -> {
                Promise<Void> gate = Promise.promise();
                gates.put(key, gate);
                gate.future().compose(v -> vertx.<String>executeBlocking(() -> {
                    try {
                        state.completeStep("late", true);
                        return "wrote";
                    } catch (StateException e) {
                        stateErrors.add(e.kind() + ": " + e.getMessage());
                        throw e;
                    }
                })).onSuccess(result -> promise.tryComplete(ExecutionResult.succeeded(result))).onFailure(promise::tryFail);
            }
            default -> promise.complete(ExecutionResult.succeeded(execution.script().toUpperCase()));
        }
        return new RunningExecution() {
            @Override
            public Future<ExecutionResult> result() {
                return promise.future();
            }

            @Override
            public void stop(String reason, Duration grace) {
                stops.add(key + ": " + reason);
                // A well-behaved script returns promptly when asked to stop.
                promise.tryComplete(ExecutionResult.cancelled("Stopped: " + reason));
            }

            @Override
            public void cancel(String reason) {
                cancellations.add(key + ": " + reason);
                promise.tryComplete(ExecutionResult.cancelled("Cancelled: " + reason));
            }
        };
    }

    private void step(Execution execution, StateSink state, String name) {
        if (execution.context().carried().step(name).isPresent()) {
            return;
        }
        executedSteps.add(name + "@" + execution.context().attempt());
        state.completeStep(name, name.toUpperCase());
    }

    /** Lets a held attempt finish successfully. */
    void release(String jobId, int attempt) {
        Promise<Void> gate = gates.remove(jobId + "#" + attempt);
        if (gate != null) {
            gate.tryComplete();
        }
        Promise<ExecutionResult> promise = held.remove(jobId + "#" + attempt);
        if (promise != null) {
            promise.tryComplete(ExecutionResult.succeeded("released attempt " + attempt));
        }
    }

    void releaseAll() {
        held.values().forEach(promise -> promise.tryComplete(ExecutionResult.succeeded("released")));
        held.clear();
    }

    boolean isHeld(String jobId, int attempt) {
        return held.containsKey(jobId + "#" + attempt) || gates.containsKey(jobId + "#" + attempt);
    }
}
