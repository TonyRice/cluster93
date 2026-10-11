package automately.runner.protocol.server;

import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.Inbound;
import automately.core.runtime.LogSink;
import automately.core.runtime.OutgoingRequest;
import automately.core.runtime.OutgoingSocket;
import automately.core.runtime.StateSink;
import automately.core.runtime.api.HostApis;
import automately.core.runtime.api.Registration;
import automately.runner.protocol.flow.CreditManager;
import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameCodec;
import automately.runner.protocol.frame.FrameType;
import automately.runner.protocol.message.BindMessage;
import automately.runner.protocol.message.CallMessage;
import automately.runner.protocol.message.ErrorMessage;
import automately.runner.protocol.message.EventMessage;
import automately.runner.protocol.message.HelloMessage;
import automately.runner.protocol.message.LogMessage;
import automately.runner.protocol.message.OpenStreamMessage;
import automately.runner.protocol.message.ReplyMessage;
import automately.runner.protocol.message.ResultMessage;
import automately.runner.protocol.message.ResultRunMessage;
import automately.runner.protocol.message.RunMessage;
import automately.runner.protocol.message.StateMessage;
import automately.runner.protocol.message.WelcomeMessage;
import automately.runner.protocol.stream.StreamMultiplexer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Node-side channel handler for an in-cluster worker runner connection (worker-node-protocol.md §4, §7).
 * Enforces zero-trust invariants: tenant identity injection, in-flight quotas, and method allowlists.
 *
 * <p>It also bridges the host APIs whose semantics are streaming or listener-based: {@code ctx.serve}/{@code listen},
 * bus/queue/job listeners (as {@link EventMessage}s answered with {@link ReplyMessage}), {@code fetch} out and
 * {@code net} sockets, and large file reads (worker-node-protocol.md §6, D-128).
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
    private final AtomicBoolean broken = new AtomicBoolean(false);
    private final AtomicInteger nodeStreamSeq = new AtomicInteger(2);
    private final Vertx vertx;

    private String boundTenantId;
    private final Promise<HelloMessage> helloPromise = Promise.promise();
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, PendingEvent> pendingEvents = new ConcurrentHashMap<>();
    private final Map<Integer, HttpExchange> pendingExchanges = new ConcurrentHashMap<>();

    private record PendingEvent(String runId, Inbound inbound) {}

    private record ActiveRun(int channelId, String runId, HostApis hostApis, LogSink logs, StateSink state,
                             Map<Integer, automately.core.files.FileWriter> writers,
                             Map<String, Registration> registrations,
                             Promise<ExecutionResult> completion) {
        ActiveRun(int channelId, String runId, HostApis hostApis, LogSink logs, StateSink state, Promise<ExecutionResult> completion) {
            this(channelId, runId, hostApis, logs, state, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), completion);
        }
    }

    private final NodeRpcAuthorizer authorizer;

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input) {
        this(workerId, output, input, new CreditManager(), NodeRpcAuthorizer.ALLOW_ALL, null);
    }

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input, CreditManager creditManager) {
        this(workerId, output, input, creditManager, NodeRpcAuthorizer.ALLOW_ALL, null);
    }

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input, CreditManager creditManager, NodeRpcAuthorizer authorizer) {
        this(workerId, output, input, creditManager, authorizer, null);
    }

    public NodeWorkerChannel(String workerId, WriteStream<Buffer> output, ReadStream<Buffer> input, CreditManager creditManager,
                             NodeRpcAuthorizer authorizer, Vertx vertx) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.output = output;
        this.input = input;
        this.creditManager = creditManager;
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.vertx = vertx;
        this.streamMultiplexer = new StreamMultiplexer(this::send, creditManager);
        this.streamMultiplexer.setOpenHandler(this::onOpenStream);

        FrameCodec.FrameParser parser = FrameCodec.parser(this::onFrame, this::onError);
        // Register the error and end handlers before handler(): the read pump starts with handler(), and an EOF that
        // arrives immediately (a closed pipe) must still reach them (D-132).
        input.exceptionHandler(this::onError);
        input.endHandler(v -> onError(new IOException("Worker process connection closed unexpectedly (EOF)")));
        input.handler(parser);
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
     * Dispatches a script execution run to the worker, carrying the full execution context (packages, env, ctx and the
     * long-running flag) so the out-of-process run matches an embedded one (D-128).
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

        JsonObject context = execution.context() == null ? new JsonObject() : execution.context().toJson();
        JsonObject packages = execution.packages() == null ? new JsonObject() : execution.packages().toJson();
        JsonObject env = new JsonObject();
        execution.env().forEach(env::put);

        RunMessage runMsg = new RunMessage(channelId, execution.jobId(), execution.script(), execution.input(), context,
                execution.timeout().toMillis(), execution.longRunning(), packages, env);
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
            case REPLY -> onReply(frame);
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

        NodeRpcDispatcher.dispatch(call.callId(), run.hostApis(), call.method(), call.params(), call.binary(), this, run.runId())
                .onSuccess(result -> send(result.toFrame()))
                .onFailure(err -> {
                    String code = err instanceof HostException he ? he.code() : "INTERNAL_ERROR";
                    String message = err.getMessage() != null ? err.getMessage() : "Internal node error";
                    send(new ErrorMessage(call.callId(), code, message).toFrame());
                })
                .onComplete(ar -> inFlightCalls.decrementAndGet());
    }

    /** A worker listener's answer to an event it was sent; completes the node-side delivery. */
    private void onReply(Frame frame) {
        ReplyMessage reply = ReplyMessage.fromFrame(frame);
        PendingEvent pending = pendingEvents.remove(reply.eventId());
        if (pending == null) {
            return;
        }
        Inbound inbound = pending.inbound();
        if (reply.ok()) {
            inbound.reply(reply.value());
        } else {
            inbound.fail(parseCode(reply.errorCode()), reply.errorMessage());
        }
    }

    private static int parseCode(String code) {
        if (code == null) {
            return 500;
        }
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return 500;
        }
    }

    private void onLog(Frame frame) {
        LogMessage log = LogMessage.fromFrame(frame);
        ActiveRun run = activeRuns.get(log.runId());
        if (run != null && run.logs() != null) {
            run.logs().log(log.level(), log.line());
        }
    }

    /**
     * State commits block (the store is synchronous); run them off the event loop so a slow write can't stall the
     * worker's frames (D-128).
     */
    private void onState(Frame frame) {
        StateMessage state = StateMessage.fromFrame(frame);
        ActiveRun run = activeRuns.get(state.runId());
        if (run == null || run.state() == null) {
            return;
        }
        StateSink sink = run.state();
        Runnable commit;
        if ("step".equalsIgnoreCase(state.type())) {
            commit = () -> sink.completeStep(state.stepName(), state.value());
        } else {
            commit = () -> sink.saveCheckpoint(state.value());
        }
        Vertx executor = resolveVertx();
        if (executor != null) {
            executor.executeBlocking(() -> {
                commit.run();
                return null;
            });
        } else {
            commit.run();
        }
    }

    private Vertx resolveVertx() {
        if (vertx != null) {
            return vertx;
        }
        Context ctx = Vertx.currentContext();
        return ctx == null ? null : ctx.owner();
    }

    private void onResultRun(Frame frame) {
        ResultRunMessage result = ResultRunMessage.fromFrame(frame);
        ActiveRun run = activeRuns.remove(result.runId());
        if (run != null) {
            cleanupRun(run);
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

    private void cleanupRun(ActiveRun run) {
        for (var writer : run.writers().values()) {
            writer.abort();
        }
        run.writers().clear();
        for (Registration registration : run.registrations().values()) {
            try {
                registration.cancel();
            } catch (RuntimeException e) {
                LOG.debug("Worker {}: cancelling a registration failed: {}", workerId, e.getMessage());
            }
        }
        run.registrations().clear();
        // Release any queue delivery or bus request still waiting on this run.
        pendingEvents.entrySet().removeIf(entry -> {
            if (!entry.getValue().runId().equals(run.runId())) {
                return false;
            }
            try {
                entry.getValue().inbound().fail(503, "the run ended before the listener answered");
            } catch (RuntimeException ignored) {
                // best effort
            }
            return true;
        });
    }

    // ---------------------------------------------------------------------------------------
    // Streams opened by the worker
    // ---------------------------------------------------------------------------------------

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

        switch (type) {
            case "files.create" -> onFilesCreate(run, streamId, meta);
            case "files.open" -> onFilesOpen(run, streamId, meta);
            case "fetch.request" -> onFetchRequest(run, streamId, meta);
            case "socket.connect" -> onSocketConnect(run, streamId, meta);
            case "http.response" -> onHttpResponse(run, streamId, meta);
            default -> {
                LOG.warn("Worker {}: unhandled stream type {}", workerId, type);
                streamMultiplexer.resetStream(streamId, "UNSUPPORTED_STREAM_TYPE", 400);
            }
        }
    }

    private void onFilesCreate(ActiveRun run, int streamId, JsonObject meta) {
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
            inStream.endHandler(v -> writer.finish().onComplete(arFinish -> {
                run.writers().remove(streamId);
                if (arFinish.succeeded()) {
                    send(new ResultMessage(streamId, arFinish.result().toViewJson()).toFrame());
                } else {
                    send(new ErrorMessage(streamId, "FILE_FINISH_FAILED", arFinish.cause().getMessage()).toFrame());
                }
            }));
            inStream.exceptionHandler(err -> {
                run.writers().remove(streamId);
                writer.abort();
            });
        });
    }

    private void onFilesOpen(ActiveRun run, int streamId, JsonObject meta) {
        String path = meta.getString("path");
        run.hostApis().files().open(path).onComplete(ar -> {
            if (ar.failed()) {
                streamMultiplexer.resetStream(streamId, ar.cause().getMessage(), 500);
                return;
            }
            pipeResponse(run, streamId, "files.data", ar.result());
        });
    }

    private void onFetchRequest(ActiveRun run, int streamId, JsonObject meta) {
        JsonObject request = meta.getJsonObject("request", new JsonObject());
        var inStream = streamMultiplexer.createInputStream(streamId);
        run.hostApis().fetch().open(request).onComplete(arReq -> {
            if (arReq.failed()) {
                pipeError(run, streamId, "fetch.response", arReq.cause());
                return;
            }
            OutgoingRequest outgoing = arReq.result();
            inStream.handler(outgoing::write);
            inStream.endHandler(v -> outgoing.end());
            inStream.exceptionHandler(err -> outgoing.abort());
            outgoing.response().onComplete(arResp -> {
                if (arResp.failed()) {
                    pipeError(run, streamId, "fetch.response", arResp.cause());
                    return;
                }
                pipeResponse(run, streamId, "fetch.response", arResp.result());
            });
        });
    }

    private void onSocketConnect(ActiveRun run, int streamId, JsonObject meta) {
        JsonObject request = meta.getJsonObject("request", new JsonObject());
        var inStream = streamMultiplexer.createInputStream(streamId);
        run.hostApis().sockets().connect(request).onComplete(arSocket -> {
            if (arSocket.failed()) {
                streamMultiplexer.resetStream(streamId, arSocket.cause().getMessage(), 502);
                return;
            }
            OutgoingSocket socket = arSocket.result();
            inStream.handler(socket::write);
            inStream.endHandler(v -> socket.end());
            inStream.exceptionHandler(err -> socket.destroy());

            int dataStreamId = nodeStreamSeq.getAndAdd(2);
            JsonObject dataMeta = new JsonObject()
                    .put("runId", run.runId())
                    .put("requestStreamId", streamId)
                    .put("info", socket.info());
            streamMultiplexer.openStream(dataStreamId, "socket.data", dataMeta).onComplete(arOpen -> {
                if (arOpen.failed()) {
                    socket.destroy();
                    return;
                }
                WriteStream<Buffer> out = streamMultiplexer.createOutputStream(dataStreamId);
                socket.data().handler(
                        chunk -> out.write(chunk),
                        () -> out.end(),
                        err -> streamMultiplexer.resetStream(dataStreamId, String.valueOf(err.getMessage()), 502));
                socket.data().resume();
            });
        });
    }

    /** Streams a {@link FetchedResponse}'s meta and body to a newly opened response stream. */
    private void pipeResponse(ActiveRun run, int requestStreamId, String responseType, FetchedResponse response) {
        int dataStreamId = nodeStreamSeq.getAndAdd(2);
        JsonObject meta = new JsonObject()
                .put("runId", run.runId())
                .put("requestStreamId", requestStreamId)
                .put("meta", response.meta());
        streamMultiplexer.openStream(dataStreamId, responseType, meta).onComplete(arOpen -> {
            if (arOpen.failed()) {
                return;
            }
            WriteStream<Buffer> out = streamMultiplexer.createOutputStream(dataStreamId);
            response.body().handler(
                    chunk -> out.write(chunk),
                    () -> out.end(),
                    err -> streamMultiplexer.resetStream(dataStreamId, String.valueOf(err.getMessage()), 502));
            response.body().resume();
        });
    }

    /**
     * Signals a request's failure back to the worker over the response stream: a fetch whose request body already
     * ended has no stream left to reset, so the failure travels as an error response instead (D-128).
     */
    private void pipeError(ActiveRun run, int requestStreamId, String responseType, Throwable error) {
        int dataStreamId = nodeStreamSeq.getAndAdd(2);
        JsonObject meta = new JsonObject()
                .put("runId", run.runId())
                .put("requestStreamId", requestStreamId)
                .put("error", error != null && error.getMessage() != null ? error.getMessage() : "the request failed");
        streamMultiplexer.openStream(dataStreamId, responseType, meta).onComplete(arOpen -> {
            if (arOpen.succeeded()) {
                streamMultiplexer.resetStream(dataStreamId, "error", 502);
            }
        });
    }

    private void onHttpResponse(ActiveRun run, int streamId, JsonObject meta) {
        int requestStreamId = meta.getInteger("requestStreamId", -1);
        HttpExchange exchange = pendingExchanges.remove(requestStreamId);
        if (exchange == null) {
            streamMultiplexer.resetStream(streamId, "UNKNOWN_EXCHANGE", 404);
            return;
        }
        String statusMessage = meta.getString("statusMessage");
        var rawHeaders = meta.getJsonArray("rawHeaders");
        if (rawHeaders != null) {
            exchange.respond(meta.getInteger("status", 200), statusMessage, rawHeaders);
        } else if (meta.containsKey("error")) {
            exchange.fail(meta.getInteger("status", 500), meta.getString("error"));
            return;
        } else {
            exchange.respond(meta.getInteger("status", 200), meta.getJsonObject("headers", new JsonObject()));
        }
        var inStream = streamMultiplexer.createInputStream(streamId);
        inStream.handler(exchange::write);
        inStream.endHandler(v -> exchange.end());
        inStream.exceptionHandler(err -> exchange.fail(500, String.valueOf(err.getMessage())));
    }

    // ---------------------------------------------------------------------------------------
    // Host APIs needing the channel: serving, listeners and file responses
    // ---------------------------------------------------------------------------------------

    /** {@code serve.listen}: registers the run's HTTP server, forwarding each exchange to the worker. */
    public Future<Void> serveListen(String runId, JsonObject params) {
        ActiveRun run = activeRun(runId);
        Integer port = params.getInteger("port");
        Consumer<HttpExchange> proxy = exchange -> serveExchange(run, exchange);
        String key = "serve:" + (port == null ? "main" : port);
        if (run.registrations().containsKey(key)) {
            return Future.failedFuture(new HostException("Error", "already_serving", "ctx.serve was already called in this run"));
        }
        Future<Registration> registration = port == null
                ? run.hostApis().http().serve(proxy)
                : run.hostApis().http().listen(port, proxy);
        return registration.map(reg -> {
            run.registrations().put(key, reg);
            return (Void) null;
        });
    }

    /** {@code serve.unlisten}: stops forwarding a run's HTTP server. */
    public Future<Void> serveUnlisten(String runId, JsonObject params) {
        ActiveRun run = activeRun(runId);
        Integer port = params.getInteger("port");
        Registration registration = run.registrations().remove("serve:" + (port == null ? "main" : port));
        return registration == null ? Future.succeededFuture() : registration.cancel();
    }

    /** {@code bus.subscribe} / {@code bus.handle} / {@code queue.consume} / {@code jobs.events}. */
    public Future<Void> registerListener(String runId, String method, JsonObject params) {
        ActiveRun run = activeRun(runId);
        String topic = params.getString("topic");
        if (topic == null) {
            topic = params.getString("queue");
        }
        if (topic == null) {
            topic = params.getString("id");
        }
        String key = method + ":" + topic;
        final String topicName = topic;
        Consumer<Inbound> forward = inbound -> sendEvent(run, method, topicName, inbound);
        Future<Registration> registration = switch (method) {
            case "bus.subscribe" -> run.hostApis().bus().subscribe(topicName, forward);
            case "bus.handle" -> run.hostApis().bus().handle(topicName, forward);
            case "queue.consume" -> run.hostApis().queue().consume(topicName, params.getInteger("concurrency", 1), forward);
            case "jobs.events" -> run.hostApis().jobs().events(topicName, forward);
            default -> Future.failedFuture(new HostException("Error", "INVALID_METHOD", "Not a listener method: " + method));
        };
        return registration.map(reg -> {
            Registration previous = run.registrations().put(key, reg);
            if (previous != null) {
                previous.cancel();
            }
            return (Void) null;
        });
    }

    /** {@code bus.unsubscribe} / {@code bus.unhandle} / {@code queue.unconsume} / {@code jobs.unevents}. */
    public Future<Void> unregisterListener(String runId, String method, JsonObject params) {
        ActiveRun run = activeRun(runId);
        String topic = params.getString("topic");
        if (topic == null) {
            topic = params.getString("queue");
        }
        if (topic == null) {
            topic = params.getString("id");
        }
        Registration registration = run.registrations().remove(method + ":" + topic);
        return registration == null ? Future.succeededFuture() : registration.cancel();
    }

    /** {@code files.respond}: answers an exchange a serve handler holds with a file. */
    public Future<Void> filesRespond(String runId, JsonObject params) {
        ActiveRun run = activeRun(runId);
        int exchangeId = params.getInteger("exchangeId", -1);
        HttpExchange exchange = pendingExchanges.get(exchangeId);
        if (exchange == null) {
            return Future.failedFuture(new HostException("Error", "not_found", "No exchange with id " + exchangeId));
        }
        return run.hostApis().files().respond(exchange, params.getString("path"), params.getJsonObject("headers", new JsonObject()));
    }

    private ActiveRun activeRun(String runId) {
        ActiveRun run = activeRuns.get(runId);
        if (run == null) {
            throw new HostException("Error", "INVALID_RUN", "No active execution with id: " + runId);
        }
        return run;
    }

    private void sendEvent(ActiveRun run, String type, String topic, Inbound inbound) {
        String eventId = "ev-" + run.channelId() + "-" + Long.toString(System.nanoTime(), 36);
        JsonObject payload = inbound.payload() instanceof JsonObject json ? json : new JsonObject().put("value", inbound.payload());
        boolean expectsReply = inbound.expectsReply();
        if (expectsReply) {
            pendingEvents.put(eventId, new PendingEvent(run.runId(), inbound));
        }
        send(new EventMessage(run.channelId(), eventId, type, topic, payload, expectsReply).toFrame())
                .onFailure(err -> {
                    pendingEvents.remove(eventId);
                    if (expectsReply) {
                        inbound.fail(503, "the worker connection is gone");
                    }
                });
    }

    /** Forwards one ingress exchange to the worker, as a request stream, and awaits its response stream. */
    private void serveExchange(ActiveRun run, HttpExchange exchange) {
        int streamId = nodeStreamSeq.getAndAdd(2);
        boolean upgrade = exchange.request().getBoolean("upgrade", false);
        JsonObject meta = new JsonObject()
                .put("runId", run.runId())
                .put("request", exchange.request())
                .put("upgrade", upgrade);
        String type = upgrade ? "http.request.upgrade" : "http.request";
        // Register the exchange before opening the stream: the worker may answer as soon as it sees the OPEN, and the
        // response stream must find this exchange.
        if (!upgrade) {
            pendingExchanges.put(streamId, exchange);
        }
        streamMultiplexer.openStream(streamId, type, meta).onComplete(arOpen -> {
            if (arOpen.failed()) {
                pendingExchanges.remove(streamId);
                exchange.fail(500, "The serving worker is gone");
                return;
            }
            if (upgrade) {
                OutgoingSocket socket = exchange.upgrade();
                if (socket == null) {
                    exchange.fail(500, "The connection can't be upgraded");
                    return;
                }
                WriteStream<Buffer> out = streamMultiplexer.createOutputStream(streamId);
                var inStream = streamMultiplexer.createInputStream(streamId);
                inStream.handler(socket::write);
                inStream.endHandler(v -> socket.end());
                inStream.exceptionHandler(err -> socket.destroy());
                socket.data().handler(
                        chunk -> out.write(chunk),
                        () -> out.end(),
                        err -> streamMultiplexer.resetStream(streamId, String.valueOf(err.getMessage()), 502));
                socket.data().resume();
                return;
            }

            WriteStream<Buffer> out = streamMultiplexer.createOutputStream(streamId);
            exchange.body().handler(
                    chunk -> out.write(chunk),
                    () -> out.end(),
                    err -> out.end());
            exchange.body().resume();
        });
    }

    private void onGoodbye(Frame frame) {
        LOG.info("Worker {}: received GOODBYE", workerId);
    }

    private void onError(Throwable err) {
        if (!broken.compareAndSet(false, true)) {
            return;
        }
        LOG.error("Worker {}: protocol error", workerId, err);
        String message = err != null && err.getMessage() != null ? err.getMessage() : "connection closed";
        for (ActiveRun run : activeRuns.values()) {
            cleanupRun(run);
            run.completion().tryComplete(ExecutionResult.failed("Worker process died: " + message));
        }
        activeRuns.clear();
    }
}
