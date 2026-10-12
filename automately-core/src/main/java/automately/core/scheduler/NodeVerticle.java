package automately.core.scheduler;

import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.job.OrphanPolicy;
import automately.core.job.ServiceRef;
import automately.core.host.HostApisFactory;
import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.LogSink;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.ScriptRuntime;
import automately.core.runtime.StateSink;
import automately.core.state.JobState;
import automately.core.state.JobStateStore;
import automately.core.state.StateException;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One execution node. It advertises its capacity through the {@link NodeRegistry}, accepts attempts sent
 * to its instance address, runs them on the {@link ScriptRuntime} and records the outcome in the
 * {@link JobStore}.
 *
 * <p>Every write about a job names the attempt and this node's instance id, so if the cluster has
 * already given the job to another node (because this one looked dead), the write is rejected. On each
 * heartbeat the node also checks its running jobs: it cancels any it no longer owns, and gracefully stops
 * any with a pending cancel request (in case the stop message was lost).
 *
 * <p>All mutable state is confined to this verticle's event loop, so several nodes can share one
 * Vert.x instance in tests.
 */
public final class NodeVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(NodeVerticle.class);

    /** Stored logs keep the last lines, so a run that crashes shows how it ended. */
    static final int MAX_LOG_LINES = 1_000;
    static final long STATE_WRITE_TIMEOUT_SECONDS = 30;
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final String nodeId;
    private final String instanceId;
    private final int capacity;
    private final JobStore jobs;
    private final JobStateStore state;
    private final NodeRegistry nodes;
    private final ScriptRuntime runtime;
    private final Duration heartbeat;
    private final Duration stopGrace;
    private final HostApisFactory hostApis;
    private final String endpoint;
    private final automately.core.packages.PackageLoader packages;
    /** Reads the secrets a job's env names (D-095); null if this node has none. */
    private final automately.core.secrets.TenantSecrets secrets;

    /** Jobs this node has accepted, by job id. The execution is null until the script has started. */
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private long heartbeatTimer;
    private boolean stopping;

    private record Slot(int attempt, RunningExecution execution) {
    }

    public NodeVerticle(String nodeId, String instanceId, int capacity, JobStore jobs, JobStateStore state, NodeRegistry nodes,
                        ScriptRuntime runtime, Duration heartbeat, Duration stopGrace, HostApisFactory hostApis, String endpoint) {
        this(nodeId, instanceId, capacity, jobs, state, nodes, runtime, heartbeat, stopGrace, hostApis, endpoint, null, null);
    }

    /** @param packages fetches what a job imports before it runs (D-089); null if this node has no package store */
    public NodeVerticle(String nodeId, String instanceId, int capacity, JobStore jobs, JobStateStore state, NodeRegistry nodes,
                        ScriptRuntime runtime, Duration heartbeat, Duration stopGrace, HostApisFactory hostApis, String endpoint,
                        automately.core.packages.PackageLoader packages, automately.core.secrets.TenantSecrets secrets) {
        this.packages = packages;
        this.secrets = secrets;
        this.hostApis = hostApis;
        this.endpoint = endpoint;
        this.nodeId = nodeId;
        this.instanceId = instanceId;
        this.capacity = capacity;
        this.jobs = jobs;
        this.state = state;
        this.nodes = nodes;
        this.runtime = runtime;
        this.heartbeat = heartbeat;
        this.stopGrace = stopGrace;
    }

    @Override
    public Future<?> start() {
        io.micrometer.core.instrument.Gauge.builder("automately.jobs.running", slots, java.util.Map::size)
                .description("Attempts running on this node (one isolate each)").register(automately.core.metrics.Metrics.of(vertx).registry());
        io.micrometer.core.instrument.Gauge.builder("automately.jobs.capacity", () -> capacity)
                .description("Attempts this node can run at once").register(automately.core.metrics.Metrics.of(vertx).registry());
        heartbeatTimer = vertx.setPeriodic(heartbeat.toMillis(), id -> {
            announce();
            checkRunningJobs();
        });
        return vertx.eventBus().<JsonObject>consumer(Addresses.nodeRun(instanceId), this::onRun).completion()
                .compose(v -> vertx.eventBus().<JsonObject>consumer(Addresses.nodeControl(instanceId), this::onControl).completion())
                .compose(v -> announce());
    }

    /**
     * Stops the node's running scripts and waits for their isolates to exit, so no native V8 code is still
     * running when the process ends (that crashed the JVM on exit). The attempts are deliberately not
     * recorded as finished: they stay RUNNING on an instance that's gone, and the reaper applies each
     * job's {@code onOrphan} policy, the same path as after a crash (D-015).
     */
    @Override
    public Future<?> stop() {
        stopping = true;
        vertx.cancelTimer(heartbeatTimer);
        List<Future<ExecutionResult>> exiting = new ArrayList<>();
        // Cancelling can complete an attempt synchronously, which removes its slot; iterate over a copy.
        List.copyOf(slots.values()).forEach(slot -> {
            if (slot.execution() != null) {
                slot.execution().cancel("node " + nodeId + " is shutting down");
                exiting.add(slot.execution().result());
            }
        });
        if (!exiting.isEmpty()) {
            LOG.info("Node {} stopping; cancelled {} running attempt(s), leaving them to the orphan reaper", nodeId, exiting.size());
        }
        return Future.join(exiting).timeout(SHUTDOWN_WAIT)
                .transform(ignored -> nodes.remove(instanceId));
    }

    private Future<Void> announce() {
        return nodes.announce(new NodeInfo(nodeId, instanceId, capacity, slots.size(), System.currentTimeMillis(), endpoint));
    }

    private void onRun(Message<JsonObject> message) {
        String jobId = message.body().getString("jobId");
        int attempt = message.body().getInteger("attempt");
        if (slots.size() >= capacity) {
            message.fail(503, "Node " + nodeId + " is at capacity");
            return;
        }
        if (slots.containsKey(jobId)) {
            message.fail(409, "Node " + nodeId + " is already running job " + jobId);
            return;
        }
        slots.put(jobId, new Slot(attempt, null));
        announce();
        message.reply(instanceId);

        jobs.update(jobId, job -> job.started(attempt, nodeId, instanceId, System.currentTimeMillis()))
                .onSuccess(started -> started.ifPresent(job -> {
                    if (job.startedAt() != null) {
                        automately.core.metrics.Metrics.of(vertx).jobStarted(java.time.Duration.ofMillis(Math.max(0, job.startedAt() - job.queuedAt())));
                    }
                }))
                .compose(started -> started.isPresent() ? run(started.get()) : Future.succeededFuture())
                .onFailure(err -> {
                    LOG.error("Node {} could not run attempt {} of job {}", nodeId, attempt, jobId, err);
                    finish(jobId, attempt, ExecutionResult.failed("Node error: " + err.getMessage()), List.of());
                })
                .onComplete(ar -> {
                    slots.remove(jobId);
                    announce();
                });
    }

    /** A request to stop one of this node's attempts gracefully. */
    private void onControl(Message<JsonObject> message) {
        Slot slot = slots.get(message.body().getString("jobId"));
        if (slot == null || slot.attempt() != message.body().getInteger("attempt") || slot.execution() == null) {
            message.fail(404, "Node " + nodeId + " is not running that attempt");
            return;
        }
        slot.execution().stop(message.body().getString("reason"), stopGrace);
        message.reply(instanceId);
    }

    private Future<Void> run(Job job) {
        int attempt = job.attempt();
        return loadPackages(job).compose(loaded -> {
            if (loaded.failed()) {
                return finish(job.id(), attempt, ExecutionResult.failed("Couldn't load the job's packages: " + loaded.cause().getMessage()), List.of());
            }
            // Its process.env, with the secrets it names read now, on this node (D-095).
            return automately.core.job.JobEnv.resolve(job.env(), job.tenant(), job.secrets(), secrets).transform(env -> env.failed()
                    ? finish(job.id(), attempt, ExecutionResult.failed("Couldn't set up the job's env: " + env.cause().getMessage()), List.of())
                    : run(job, loaded.result(), env.result()));
        });
    }

    /** Everything the job's lock names, fetched and checked before an isolate is made (D-089). */
    private Future<io.vertx.core.AsyncResult<automately.core.runtime.Packages>> loadPackages(Job job) {
        if (job.packages() == null) {
            return Future.succeededFuture(Future.succeededFuture(automately.core.runtime.Packages.NONE));
        }
        if (packages == null) {
            return Future.succeededFuture(Future.failedFuture("this node has no package store"));
        }
        long loading = System.nanoTime();
        return packages.load(job.tenant(), job.packages()).andThen(ar -> automately.core.metrics.Metrics.of(vertx)
                .packagesLoaded(ar.succeeded(), java.time.Duration.ofNanos(System.nanoTime() - loading)))
                .transform(Future::succeededFuture);
    }

    private Future<Void> run(Job job, automately.core.runtime.Packages imports, automately.core.job.JobEnv.Resolved env) {
        int attempt = job.attempt();
        return carriedState(job).compose(carried -> {
            if (stopping) {
                return Future.succeededFuture();
            }
            if (attempt > 1) {
                publish(job.id(), new JsonObject().put("type", "attempt").put("attempt", attempt).put("node", nodeId)
                        .put("previousOutcome", job.previousAttempt().outcome().name()));
            }
            LogBook logs = new LogBook();
            // Clients connecting mid-run ask this node for the lines so far (the API's event stream).
            io.vertx.core.eventbus.MessageConsumer<Object> tail = vertx.eventBus().consumer(Addresses.jobTail(job.id()), msg -> msg.reply(logs.tail()));
            JobContext jobContext = new JobContext(job.id(), attempt, job.onOrphan(), job.previousAttempt(), carried.state(),
                    carried.service());
            RunningExecution execution = runtime.execute(execution(job, jobContext, imports, env), logSink(job.id(), logs, env), stateSink(job.id(), attempt),
                    hostApis.create(job.tenant(), job.id(), job.service() != null, imports));
            slots.put(job.id(), new Slot(attempt, execution));
            if (job.cancelRequested() != null) {
                execution.stop(job.cancelRequested(), stopGrace);
            }

            // A runtime may complete on any thread; continue on this verticle's context so `slots` stays confined.
            return Future.fromCompletionStage(execution.result().toCompletionStage(), context)
                    .recover(err -> Future.succeededFuture(ExecutionResult.failed("Runtime error: " + err.getMessage())))
                    .compose(result -> finish(job.id(), attempt, result, logs.lines()))
                    .onComplete(done -> tail.unregister());
        });
    }

    private record Carried(JobState state, JsonObject service) {
    }

    /**
     * What this attempt starts from: for a resumed job, the earlier attempts' state; for a service run, the
     * previous run's checkpoint (seeded into this run's state so it shows in /steps) plus ctx.service.
     */
    private Future<Carried> carriedState(Job job) {
        if (job.service() != null) {
            ServiceRef service = job.service();
            Future<Job> previous = service.previousJobId() == null ? Future.succeededFuture() : jobs.get(service.previousJobId());
            return previous.compose(prev -> {
                JsonObject serviceJson = new JsonObject()
                        .put("name", service.name())
                        .put("revision", service.revision())
                        .put("run", service.run())
                        .put("config", job.input())
                        .put("previousRun", prev == null ? null : new JsonObject()
                                .put("jobId", prev.id())
                                .put("status", prev.status().name())
                                .put("error", prev.error())
                                .put("endedAt", prev.finishedAt()));
                if (prev == null) {
                    return Future.succeededFuture(new Carried(JobState.EMPTY, serviceJson));
                }
                return state.load(prev.id(), prev.attempt()).compose(prevState -> prevState.checkpoint() == null
                        ? Future.succeededFuture(new Carried(JobState.EMPTY, serviceJson))
                        : state.saveCheckpoint(job.id(), job.attempt(), prevState.checkpoint())
                        .map(seeded -> new Carried(new JobState(prevState.checkpoint(), List.of()), serviceJson)));
            });
        }
        if (job.onOrphan() == OrphanPolicy.RESUME && job.attempt() > 1) {
            return state.resumeFrom(job.id(), job.attempt()).map(resumed -> new Carried(resumed, null));
        }
        return Future.succeededFuture(new Carried(JobState.EMPTY, null));
    }

    /** Records the end of an attempt. Rejected (and not announced) if the attempt was superseded meanwhile. */
    private Future<Void> finish(String jobId, int attempt, ExecutionResult result, List<String> logs) {
        if (stopping) {
            return Future.succeededFuture();
        }
        return jobs.update(jobId, job -> job.finished(attempt, instanceId, result.status(), result.output(), result.error(), logs,
                        System.currentTimeMillis()))
                .map(finished -> {
                    if (finished.isPresent()) {
                        Job done = finished.get();
                        long ran = done.startedAt() == null || done.finishedAt() == null ? 0 : done.finishedAt() - done.startedAt();
                        automately.core.metrics.Metrics.of(vertx).jobFinished(done.status().name(), java.time.Duration.ofMillis(Math.max(0, ran)));
                        LOG.atInfo().addKeyValue("jobId", jobId).addKeyValue("tenant", done.tenant()).addKeyValue("status", done.status().name())
                                .addKeyValue("attempt", attempt).addKeyValue("durationMs", ran)
                                .log("Job {} attempt {} ended {} after {} ms", jobId, attempt, done.status(), ran);
                        publish(jobId, new JsonObject().put("type", "end").put("status", finished.get().status().name()));
                    } else {
                        LOG.warn("Attempt {} of job {} on node {} was superseded; its result ({}) is discarded",
                                attempt, jobId, nodeId, result.status());
                    }
                    return null;
                });
    }

    /** Cancels attempts this node no longer owns, and stops attempts someone asked to cancel. */
    private void checkRunningJobs() {
        Map.copyOf(slots).forEach((jobId, slot) -> {
            if (slot.execution() == null) {
                return;
            }
            jobs.get(jobId).onSuccess(job -> {
                if (job == null || !job.isCurrent(slot.attempt(), instanceId)) {
                    slot.execution().cancel("attempt " + slot.attempt() + " was superseded");
                } else if (job.cancelRequested() != null) {
                    slot.execution().stop(job.cancelRequested(), stopGrace);
                }
            });
        });
    }

    /** The job's log; a secret value from its env that shows up in a line is masked (D-095). */
    private LogSink logSink(String jobId, LogBook logs, automately.core.job.JobEnv.Resolved env) {
        return new LogSink() {
            @Override
            public void log(String level, String line) {
                log(level, line, null);
            }

            @Override
            public void log(String level, String unmasked, JsonObject unmaskedFields) {
                String message = env.mask(unmasked);
                JsonObject fields = unmaskedFields == null || env.secretsByValue().isEmpty() ? unmaskedFields : new JsonObject(env.mask(unmaskedFields.encode()));
                String whole = fields == null || fields.isEmpty() ? message : message + " " + fields.encode();
                String line = LogBook.clip(whole);
                long seq = logs.add(line);
                JsonObject event = new JsonObject().put("type", "log").put("level", level).put("line", line).put("seq", seq);
                // A clipped line's fields would carry what was cut, so the event has only the line.
                if (fields != null && !fields.isEmpty() && line == whole) {
                    event.put("message", message).put("fields", fields);
                }
                publish(jobId, event);
            }
        };
    }



    /** Called from the script's thread. Each write first checks this attempt still owns the job. */
    private StateSink stateSink(String jobId, int attempt) {
        return new StateSink() {
            @Override
            public void saveCheckpoint(Object value) {
                await(ensureCurrent(jobId, attempt).compose(v -> state.saveCheckpoint(jobId, attempt, value)));
            }

            @Override
            public void completeStep(String name, Object result) {
                await(ensureCurrent(jobId, attempt).compose(v -> state.completeStep(jobId, attempt, name, result, System.currentTimeMillis())));
            }
        };
    }

    private Future<Void> ensureCurrent(String jobId, int attempt) {
        return jobs.get(jobId).compose(job -> job != null && job.isCurrent(attempt, instanceId)
                ? Future.succeededFuture()
                : Future.failedFuture(new StateException(StateException.Kind.SUPERSEDED,
                        "Attempt " + attempt + " of job " + jobId + " has been superseded")));
    }

    private static void await(Future<?> write) {
        try {
            write.toCompletionStage().toCompletableFuture().get(STATE_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof StateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("State write failed: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("State write timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while writing state", e);
        }
    }

    private void publish(String jobId, JsonObject event) {
        vertx.eventBus().publish(Addresses.jobEvents(jobId), event);
    }

    private static Execution execution(Job job, JobContext context, automately.core.runtime.Packages imports,
                                       automately.core.job.JobEnv.Resolved env) {
        return new Execution(job.id(), job.tenant(), job.script(), job.input(), Duration.ofMillis(job.timeoutMs()), context,
                job.service() != null, imports, env.values());
    }
}
