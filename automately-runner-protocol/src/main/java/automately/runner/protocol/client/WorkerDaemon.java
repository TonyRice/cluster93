package automately.runner.protocol.client;

import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.LogSink;
import automately.core.runtime.RunningExecution;
import automately.core.runtime.ScriptRuntime;
import automately.core.runtime.StateSink;
import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameType;
import automately.runner.protocol.message.BindMessage;
import automately.runner.protocol.message.HelloMessage;
import automately.runner.protocol.message.LogMessage;
import automately.runner.protocol.message.ResultRunMessage;
import automately.runner.protocol.message.RunMessage;
import automately.runner.protocol.message.StateMessage;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker-side protocol daemon: handles HELLO, BIND, and RUN dispatching to a {@link ScriptRuntime} (worker-node-protocol.md §4).
 */
public class WorkerDaemon {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerDaemon.class);

    private final WorkerClientConnection connection;
    private final ScriptRuntime runtime;
    private final String engineName;
    private String boundTenantId;

    private final Map<Integer, RunningExecution> activeRuns = new ConcurrentHashMap<>();

    public WorkerDaemon(WorkerClientConnection connection, ScriptRuntime runtime, String engineName) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.engineName = Objects.requireNonNull(engineName, "engineName");

        connection.setFrameHandler(this::onFrame);
    }

    /**
     * Starts the worker by sending the HELLO announcement to the node.
     */
    public Future<Void> start() {
        long pid = ProcessHandle.current().pid();
        HelloMessage hello = new HelloMessage(engineName, "2.0.0", pid);
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

        RemoteHostApis hostApis = new RemoteHostApis(connection, runId);
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

        JsonObject ctxJson = runMsg.context();
        int attempt = ctxJson.getInteger("attempt", 1);
        OrphanPolicy orphanPolicy = OrphanPolicy.valueOf(ctxJson.getString("orphanPolicy", "FAIL"));
        JobContext jobContext = new JobContext(runId, attempt, orphanPolicy, null, automately.core.state.JobState.EMPTY, null);

        Execution execution = new Execution(runId, boundTenantId != null ? boundTenantId : "unbound",
                runMsg.script(), runMsg.input(), Duration.ofMillis(runMsg.timeoutMs()), jobContext);

        RunningExecution running = runtime.execute(execution, logs, state, hostApis);
        activeRuns.put(channelId, running);

        running.result().onComplete(ar -> {
            activeRuns.remove(channelId);
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
