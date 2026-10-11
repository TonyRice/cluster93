package automately.runner.protocol.server;

import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.HostException;
import automately.core.runtime.LogSink;
import automately.core.runtime.StateSink;
import automately.core.runtime.api.HostApis;
import automately.runner.protocol.flow.CreditManager;
import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameCodec;
import automately.runner.protocol.frame.FrameType;
import automately.runner.protocol.message.BindMessage;
import automately.runner.protocol.message.CallMessage;
import automately.runner.protocol.message.ErrorMessage;
import automately.runner.protocol.message.HelloMessage;
import automately.runner.protocol.message.LogMessage;
import automately.runner.protocol.message.ResultRunMessage;
import automately.runner.protocol.message.RunMessage;
import automately.runner.protocol.message.StateMessage;
import automately.runner.protocol.message.WelcomeMessage;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Node-side channel handler for an in-cluster worker runner connection (worker-node-protocol.md §4, §7).
 * Enforces zero-trust invariants: tenant identity injection, in-flight quotas, and method allowlists.
 */
public class NodeWorkerChannel {

    private static final Logger LOG = LoggerFactory.getLogger(NodeWorkerChannel.class);

    public static final int MAX_IN_FLIGHT_CALLS = 1_024;

    private final String workerId;
    private final WriteStream<Buffer> output;
    private final ReadStream<Buffer> input;
    private final CreditManager creditManager;
    private final AtomicInteger inFlightCalls = new AtomicInteger(0);

    private String boundTenantId;
    private final Promise<HelloMessage> helloPromise = Promise.promise();
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    private record ActiveRun(int channelId, String runId, HostApis hostApis, LogSink logs, StateSink state, Promise<ExecutionResult> completion) {}

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input) {
        this(workerId, output, input, new CreditManager());
    }

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input, CreditManager creditManager) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.output = output;
        this.input = input;
        this.creditManager = creditManager;

        FrameCodec.FrameParser parser = FrameCodec.parser(this::onFrame, this::onError);
        input.handler(parser);
        input.exceptionHandler(this::onError);
    }

    public String workerId() {
        return workerId;
    }

    public String boundTenantId() {
        return boundTenantId;
    }

    public Future<HelloMessage> awaitHello() {
        return helloPromise.future();
    }

    public synchronized Future<Void> send(Frame frame) {
        Buffer encoded = FrameCodec.encode(frame);
        return output.write(encoded);
    }

    /**
     * Sends BIND message to bind the worker to one tenant for life.
     */
    public Future<Void> bind(String tenantId, JsonObject limits, JsonObject env) {
        this.boundTenantId = Objects.requireNonNull(tenantId, "tenantId");
        BindMessage msg = new BindMessage(tenantId, limits, env);
        return send(msg.toFrame());
    }

    /**
     * Dispatches a script execution run to the worker.
     */
    public Future<ExecutionResult> run(int channelId, Execution execution, HostApis hostApis, LogSink logs, StateSink state) {
        if (boundTenantId == null) {
            return Future.failedFuture(new IllegalStateException("Worker is not bound to a tenant"));
        }
        if (!boundTenantId.equals(execution.tenant())) {
            return Future.failedFuture(new IllegalArgumentException("Tenant mismatch: worker bound to " + boundTenantId + ", execution for " + execution.tenant()));
        }

        Promise<ExecutionResult> completion = Promise.promise();
        ActiveRun activeRun = new ActiveRun(channelId, execution.jobId(), hostApis, logs, state, completion);
        activeRuns.put(execution.jobId(), activeRun);

        JsonObject context = new JsonObject()
                .put("attempt", execution.context().attempt())
                .put("orphanPolicy", execution.context().policy().name());

        RunMessage runMsg = new RunMessage(channelId, execution.jobId(), execution.script(), execution.input(), context, execution.timeout().toMillis());
        send(runMsg.toFrame()).onFailure(err -> {
            activeRuns.remove(execution.jobId());
            completion.fail(err);
        });

        return completion.future().onComplete(ar -> activeRuns.remove(execution.jobId()));
    }

    /**
     * Sends STOP frame to request graceful shutdown of an execution.
     */
    public Future<Void> stop(int channelId, String reason, long graceMs) {
        JsonObject json = new JsonObject().put("reason", reason).put("graceMs", graceMs);
        return send(Frame.json(FrameType.STOP, channelId, json));
    }

    /**
     * Sends CANCEL frame to forcefully terminate an execution.
     */
    public Future<Void> cancel(int channelId, String reason) {
        JsonObject json = new JsonObject().put("reason", reason);
        return send(Frame.json(FrameType.CANCEL, channelId, json));
    }

    private void onFrame(Frame frame) {
        switch (frame.type()) {
            case HELLO -> onHello(frame);
            case CALL -> onCall(frame);
            case LOG -> onLog(frame);
            case STATE -> onState(frame);
            case RESULT_RUN -> onResultRun(frame);
            case PING -> send(Frame.pong());
            case CREDIT -> onCredit(frame);
            case GOODBYE -> onGoodbye(frame);
            default -> LOG.warn("Worker {}: unhandled frame type {}", workerId, frame.type());
        }
    }

    private void onHello(Frame frame) {
        HelloMessage hello = HelloMessage.fromFrame(frame);
        helloPromise.tryComplete(hello);
        // Reply with WELCOME
        WelcomeMessage welcome = new WelcomeMessage(workerId, new JsonObject().put("status", "accepted"));
        send(welcome.toFrame());
    }

    private void onCall(Frame frame) {
        CallMessage call = CallMessage.fromFrame(frame);
        if (inFlightCalls.incrementAndGet() > MAX_IN_FLIGHT_CALLS) {
            inFlightCalls.decrementAndGet();
            send(new ErrorMessage(call.callId(), "QUOTA_EXCEEDED", "Maximum concurrent in-flight RPC limit (1024) exceeded").toFrame());
            return;
        }

        ActiveRun run = activeRuns.get(call.runId());
        if (run == null) {
            inFlightCalls.decrementAndGet();
            send(new ErrorMessage(call.callId(), "INVALID_RUN", "No active execution with id: " + call.runId()).toFrame());
            return;
        }

        NodeRpcDispatcher.dispatch(call.callId(), run.hostApis(), call.method(), call.params(), call.binary())
                .onSuccess(result -> send(result.toFrame()))
                .onFailure(err -> {
                    String code = err instanceof HostException he ? he.code() : "INTERNAL_ERROR";
                    String message = err.getMessage() != null ? err.getMessage() : "Internal node error";
                    send(new ErrorMessage(call.callId(), code, message).toFrame());
                })
                .onComplete(ar -> inFlightCalls.decrementAndGet());
    }

    private void onLog(Frame frame) {
        LogMessage log = LogMessage.fromFrame(frame);
        ActiveRun run = activeRuns.get(log.runId());
        if (run != null && run.logs() != null) {
            run.logs().log(log.level(), log.line());
        }
    }

    private void onState(Frame frame) {
        StateMessage state = StateMessage.fromFrame(frame);
        ActiveRun run = activeRuns.get(state.runId());
        if (run != null && run.state() != null) {
            if ("step".equalsIgnoreCase(state.type())) {
                run.state().completeStep(state.stepName(), state.value());
            } else {
                run.state().saveCheckpoint(state.value());
            }
        }
    }

    private void onResultRun(Frame frame) {
        ResultRunMessage result = ResultRunMessage.fromFrame(frame);
        ActiveRun run = activeRuns.remove(result.runId());
        if (run != null) {
            ExecutionResult res;
            if ("FAILED".equalsIgnoreCase(result.status())) {
                res = ExecutionResult.failed(result.error() != null ? result.error() : "Execution failed");
            } else if ("CANCELLED".equalsIgnoreCase(result.status())) {
                res = ExecutionResult.cancelled(result.error() != null ? result.error() : "Execution cancelled");
            } else if ("TIMED_OUT".equalsIgnoreCase(result.status())) {
                res = ExecutionResult.timedOut(result.error() != null ? result.error() : "Execution timed out");
            } else {
                res = ExecutionResult.succeeded(result.output());
            }
            run.completion().tryComplete(res);
        }
    }

    private void onCredit(Frame frame) {
        int bytesToAdd = frame.json().getInteger("bytesToAdd", 0);
        if (frame.channelId() == 0) {
            creditManager.addConnectionCredits(bytesToAdd);
        } else {
            creditManager.addStreamCredits(frame.channelId(), bytesToAdd);
        }
    }

    private void onGoodbye(Frame frame) {
        LOG.info("Worker {}: received GOODBYE", workerId);
    }

    private void onError(Throwable err) {
        LOG.error("Worker {}: protocol error", workerId, err);
        for (ActiveRun run : activeRuns.values()) {
            run.completion().tryFail(err);
        }
        activeRuns.clear();
    }
}
