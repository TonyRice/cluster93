package cluster93.runner.protocol.client;

import cluster93.core.job.OrphanPolicy;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.LogSink;
import cluster93.core.runtime.Packages;
import cluster93.core.runtime.RunningExecution;
import cluster93.core.runtime.ScriptRuntime;
import cluster93.core.runtime.StateSink;
import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import cluster93.runner.protocol.message.BindMessage;
import cluster93.runner.protocol.message.EventMessage;
import cluster93.runner.protocol.message.HelloMessage;
import cluster93.runner.protocol.message.LogMessage;
import cluster93.runner.protocol.message.OpenStreamMessage;
import cluster93.runner.protocol.message.ResultRunMessage;
import cluster93.runner.protocol.message.RunMessage;
import cluster93.runner.protocol.message.StateMessage;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker-side protocol daemon: handles HELLO, BIND, and RUN dispatching to a {@link ScriptRuntime}, and routes the
 * node's asynchronous events and response streams to the run's {@link RemoteHostApis}
 * (worker-node-protocol.md §4, §6, D-128).
 */
public class WorkerDaemon {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerDaemon.class);

    private final WorkerClientConnection connection;
    private final ScriptRuntime runtime;
    private final String engineName;
    /** The pre-shared secret to present in HELLO (D-147, D-168); null when the node spawned this worker over a pipe. */
    private final String secret;
    /** This worker's per-job heap budget and how many jobs it runs at once, so the node can reserve on connect (D-169). */
    private final int heapMb;
    private final int maxJobs;
    private String boundTenantId;

    private final Map<Integer, RunningExecution> activeRuns = new ConcurrentHashMap<>();
    private final Map<Integer, RemoteHostApis> hostsByChannel = new ConcurrentHashMap<>();
    private final Map<String, RemoteHostApis> hostsByRunId = new ConcurrentHashMap<>();

    public WorkerDaemon(WorkerClientConnection connection, ScriptRuntime runtime, String engineName) {
        this(connection, runtime, engineName, null, 0, 0);
    }

    /**
     * @param secret  the pre-shared secret to present in {@code HELLO} (D-168), or null/blank for a worker the node
     *                spawned over a pipe — a pipe already binds the channel to the child the node made
     * @param heapMb  this worker's per-job heap budget, as its engine was started
     * @param maxJobs how many jobs it runs at once (its {@code --threads}); the deployment sized it, and the node
     *                reserves memory for it from connect, so it declares both (D-169)
     */
    public WorkerDaemon(WorkerClientConnection connection, ScriptRuntime runtime, String engineName,
                        String secret, int heapMb, int maxJobs) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.engineName = Objects.requireNonNull(engineName, "engineName");
        this.secret = secret;
        this.heapMb = heapMb;
        this.maxJobs = maxJobs;

        connection.setFrameHandler(this::onFrame);
        connection.streamMultiplexer().setOpenHandler(this::onOpenStream);
    }

    /**
     * Starts the worker by sending the HELLO announcement to the node.
     */
    public Future<Void> start() {
        long pid = ProcessHandle.current().pid();
        HelloMessage hello = new HelloMessage(engineName, "2.0.0", pid, secret, heapMb, maxJobs);
        return connection.send(hello.toFrame());
    }

    public String boundTenantId() {
        return boundTenantId;
    }

    private void onFrame(Frame frame) {
        switch (frame.type()) {
            case WELCOME -> LOG.info("Worker welcomed by node");
            case BIND -> onBind(frame);
            case RUN -> onRun(frame);
            case STOP -> onStop(frame);
            case CANCEL -> onCancel(frame);
            case EVENT -> onEvent(frame);
            case GOODBYE -> LOG.info("Worker received GOODBYE from node");
            default -> LOG.warn("Worker received unhandled frame {}", frame.type());
        }
    }

    private void onBind(Frame frame) {
        BindMessage bind = BindMessage.fromFrame(frame);
        this.boundTenantId = bind.tenantId();
        LOG.info("Worker successfully bound to tenant {}", boundTenantId);
    }

    private void onRun(Frame frame) {
        RunMessage runMsg = RunMessage.fromFrame(frame);
        int channelId = runMsg.channelId();
        String runId = runMsg.runId();

        RemoteHostApis hostApis = new RemoteHostApis(connection, runId, channelId);
        LogSink logs = (level, line) -> connection.send(new LogMessage(channelId, runId, level, line).toFrame());
        StateSink state = new StateSink() {
            @Override
            public void saveCheckpoint(Object value) {
                connection.send(StateMessage.checkpoint(channelId, runId, value).toFrame());
            }

            @Override
            public void completeStep(String name, Object result) {
                connection.send(StateMessage.step(channelId, runId, name, result).toFrame());
            }
        };

        JobContext jobContext = JobContext.fromJson(runMsg.context());
        if (jobContext == null) {
            jobContext = new JobContext(runId, 1, OrphanPolicy.FAIL, null, cluster93.core.state.JobState.EMPTY, null);
        }
        Packages packages = Packages.fromJson(runMsg.packages());
        Map<String, String> env = new java.util.LinkedHashMap<>();
        runMsg.env().forEach(entry -> env.put(entry.getKey(), String.valueOf(entry.getValue())));

        Execution execution = new Execution(runId, boundTenantId != null ? boundTenantId : "unbound",
                runMsg.script(), runMsg.input(), Duration.ofMillis(runMsg.timeoutMs()), jobContext,
                runMsg.longRunning(), packages, env);

        hostsByChannel.put(channelId, hostApis);
        hostsByRunId.put(runId, hostApis);
        RunningExecution running = runtime.execute(execution, logs, state, hostApis);
        activeRuns.put(channelId, running);

        running.result().onComplete(ar -> {
            activeRuns.remove(channelId);
            hostsByChannel.remove(channelId);
            hostsByRunId.remove(runId);
            hostApis.close();

            ResultRunMessage resultMsg;
            if (ar.succeeded()) {
                ExecutionResult res = ar.result();
                resultMsg = new ResultRunMessage(channelId, runId, res.status().name(), res.output(), res.error());
            } else {
                resultMsg = new ResultRunMessage(channelId, runId, "FAILED", null, ar.cause().getMessage());
            }
            connection.send(resultMsg.toFrame());
        });
    }

    private void onEvent(Frame frame) {
        EventMessage event = EventMessage.fromFrame(frame);
        RemoteHostApis host = hostsByChannel.get(event.channelId());
        if (host != null) {
            host.dispatchEvent(event);
        } else {
            LOG.warn("Worker received event {} for unknown run channel {}", event.type(), event.channelId());
        }
    }

    private void onOpenStream(OpenStreamMessage open) {
        String runId = open.metadata().getString("runId");
        RemoteHostApis host = runId != null ? hostsByRunId.get(runId) : null;
        if (host == null) {
            connection.streamMultiplexer().resetStream(open.streamId(), "UNKNOWN_RUN", 404);
            return;
        }
        host.onIncomingStream(open);
    }

    private void onStop(Frame frame) {
        int channelId = frame.channelId();
        RunningExecution running = activeRuns.get(channelId);
        if (running != null) {
            String reason = frame.json().getString("reason", "Requested stop");
            long graceMs = frame.json().getLong("graceMs", 5000L);
            running.stop(reason, Duration.ofMillis(graceMs));
        }
    }

    private void onCancel(Frame frame) {
        int channelId = frame.channelId();
        RunningExecution running = activeRuns.get(channelId);
        if (running != null) {
            String reason = frame.json().getString("reason", "Requested cancel");
            running.cancel(reason);
        }
    }
}
