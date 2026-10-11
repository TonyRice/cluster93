package automately.runner.protocol.client;

import automately.core.runtime.HostException;
import automately.runner.protocol.flow.CreditManager;
import automately.runner.protocol.frame.Frame;
import automately.runner.protocol.frame.FrameCodec;
import automately.runner.protocol.frame.FrameType;
import automately.runner.protocol.message.CallMessage;
import automately.runner.protocol.message.ErrorMessage;
import automately.runner.protocol.message.ResultMessage;
import automately.runner.protocol.stream.StreamMultiplexer;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker-side protocol channel managing communication with the Automately node (worker-node-protocol.md §4).
 */
public class WorkerClientConnection {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerClientConnection.class);

    private final WriteStream<Buffer> output;
    private final ReadStream<Buffer> input;
    private final CreditManager creditManager;
    private final StreamMultiplexer streamMultiplexer;
    private final AtomicInteger callIdSequence = new AtomicInteger(1);
    private final Map<Integer, Promise<ResultMessage>> pendingCalls = new ConcurrentHashMap<>();

    private Handler<Frame> frameHandler = frame -> {};
    private Handler<Throwable> errorHandler = err -> LOG.error("Protocol error", err);

    public WorkerClientConnection(WriteStream<Buffer> output, ReadStream<Buffer> input) {
        this(output, input, new CreditManager());
    }

    public WorkerClientConnection(WriteStream<Buffer> output, ReadStream<Buffer> input, CreditManager creditManager) {
        this.output = output;
        this.input = input;
        this.creditManager = creditManager;
        this.streamMultiplexer = new StreamMultiplexer(this::send, creditManager);

        FrameCodec.FrameParser parser = FrameCodec.parser(this::onFrame, this::onError);
        input.handler(parser);
        input.exceptionHandler(this::onError);
    }

    public void setFrameHandler(Handler<Frame> frameHandler) {
        this.frameHandler = frameHandler != null ? frameHandler : frame -> {};
    }

    public void setErrorHandler(Handler<Throwable> errorHandler) {
        this.errorHandler = errorHandler != null ? errorHandler : err -> LOG.error("Protocol error", err);
    }

    public CreditManager creditManager() {
        return creditManager;
    }

    public StreamMultiplexer streamMultiplexer() {
        return streamMultiplexer;
    }

    public int nextCallId() {
        return callIdSequence.getAndIncrement();
    }

    /**
     * Sends an encoded frame to the node.
     */
    public synchronized Future<Void> send(Frame frame) {
        Buffer encoded = FrameCodec.encode(frame);
        return output.write(encoded);
    }

    /**
     * Executes a host API RPC call and awaits the response.
     */
    public Future<ResultMessage> call(CallMessage call) {
        Promise<ResultMessage> promise = Promise.promise();
        pendingCalls.put(call.callId(), promise);
        send(call.toFrame()).onFailure(promise::fail);
        return promise.future();
    }

    private void onFrame(Frame frame) {
        if (streamMultiplexer.handleFrame(frame)) {
            return;
        }

        if (frame.type() == FrameType.RESULT) {
            ResultMessage result = ResultMessage.fromFrame(frame);
            Promise<ResultMessage> promise = pendingCalls.remove(result.callId());
            if (promise != null) {
                promise.complete(result);
            }
            return;
        }

        if (frame.type() == FrameType.ERROR) {
            ErrorMessage error = ErrorMessage.fromFrame(frame);
            Promise<ResultMessage> promise = pendingCalls.remove(error.callId());
            if (promise != null) {
                promise.fail(new HostException("Error", error.code(), error.message()));
            }
            return;
        }

        if (frame.type() == FrameType.PING) {
            send(Frame.pong());
            return;
        }

        // Delegate application frames (WELCOME, BIND, RUN, STOP, CANCEL, EVENT, etc.)
        try {
            frameHandler.handle(frame);
        } catch (Exception e) {
            onError(e);
        }
    }

    private void onError(Throwable err) {
        errorHandler.handle(err);
        // Fail any pending RPCs
        for (Promise<ResultMessage> promise : pendingCalls.values()) {
            promise.tryFail(err);
        }
        pendingCalls.clear();
    }
}
