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
import automately.runner.protocol.message.OpenStreamMessage;
import automately.runner.protocol.message.ResultMessage;
import automately.runner.protocol.message.ResultRunMessage;
import automately.runner.protocol.message.RunMessage;
import automately.runner.protocol.message.StateMessage;
import automately.runner.protocol.message.WelcomeMessage;
import automately.runner.protocol.stream.StreamMultiplexer;
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
    private final StreamMultiplexer streamMultiplexer;
    private final AtomicInteger inFlightCalls = new AtomicInteger(0);

    private String boundTenantId;
    private final Promise<HelloMessage> helloPromise = Promise.promise();
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    private record ActiveRun(int channelId, String runId, HostApis hostApis, LogSink logs, StateSink state,
                             Map<Integer, automately.core.files.FileWriter> writers,
                             Promise<ExecutionResult> completion) {
        ActiveRun(int channelId, String runId, HostApis hostApis, LogSink logs, StateSink state, Promise<ExecutionResult> completion) {
            this(channelId, runId, hostApis, logs, state, new ConcurrentHashMap<>(), completion);
        }
    }

    private final NodeRpcAuthorizer authorizer;

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input) {
        this(workerId, output, input, new CreditManager(), NodeRpcAuthorizer.ALLOW_ALL);
    }

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input, CreditManager creditManager) {
        this(workerId, output, input, creditManager, NodeRpcAuthorizer.ALLOW_ALL);
    }

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input, CreditManager creditManager, NodeRpcAuthorizer authorizer) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.output = output;
        this.input = input;
        this.creditManager = creditManager;
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.streamMultiplexer = new StreamMultiplexer(this::send, creditManager);
        this.streamMultiplexer.setOpenHandler(this::onOpenStream);

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

    public StreamMultiplexer streamMultiplexer() {
        return streamMultiplexer;
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
        if (streamMultiplexer.handleFrame(frame)) {
            return;
        }

        switch (frame.type()) {
            case HELLO -> onHello(frame);
            case CALL -> onCall(frame);
            case LOG -> onLog(frame);
            case STATE -> onState(frame);
            case RESULT_RUN -> onResultRun(frame);
            case PING -> send(Frame.pong());
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

        if (!authorizer.isAuthorized(boundTenantId, call.runId(), call.method(), call.params())) {
            inFlightCalls.decrementAndGet();
            send(new ErrorMessage(call.callId(), "FORBIDDEN", "Unauthorized method call: " + call.method()).toFrame());
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
            for (var writer : run.writers().values()) {
                writer.abort();
            }
            run.writers().clear();

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

    private void onOpenStream(OpenStreamMessage open) {
        int streamId = open.streamId();
        String type = open.streamType();
        JsonObject meta = open.metadata();
        String runId = meta != null ? meta.getString("runId") : null;
        ActiveRun run = runId != null ? activeRuns.get(runId) : null;
        if (run == null) {
            LOG.warn("Worker {}: received stream OPEN for unknown run {}", workerId, runId);
            streamMultiplexer.resetStream(streamId, "UNKNOWN_RUN", 404);
            return;
        }

        if ("files.create".equals(type)) {
            String path = meta.getString("path");
            String contentType = meta.getString("contentType");
            var inStream = streamMultiplexer.createInputStream(streamId);
            run.hostApis().files().create(path, contentType).onComplete(arWriter -> {
                if (arWriter.failed()) {
                    LOG.error("Worker {}: failed to create file {}: {}", workerId, path, arWriter.cause().getMessage());
                    streamMultiplexer.resetStream(streamId, arWriter.cause().getMessage(), 500);
                    return;
                }
                var writer = arWriter.result();
                run.writers().put(streamId, writer);
                inStream.handler(chunk -> writer.write(chunk));
                inStream.endHandler(v -> {
                    writer.finish().onComplete(arFinish -> {
                        run.writers().remove(streamId);
                        if (arFinish.succeeded()) {
                            JsonObject fileInfoJson = arFinish.result().toViewJson();
                            send(new ResultMessage(streamId, fileInfoJson).toFrame());
                        } else {
                            send(new ErrorMessage(streamId, "FILE_FINISH_FAILED", arFinish.cause().getMessage()).toFrame());
                        }
                    });
                });
                inStream.exceptionHandler(err -> {
                    run.writers().remove(streamId);
                    writer.abort();
                });
            });
        } else {
            LOG.warn("Worker {}: unhandled stream type {}", workerId, type);
            streamMultiplexer.resetStream(streamId, "UNSUPPORTED_STREAM_TYPE", 400);
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
