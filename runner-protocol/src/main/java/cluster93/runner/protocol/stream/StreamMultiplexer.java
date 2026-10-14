package cluster93.runner.protocol.stream;

import cluster93.runner.protocol.flow.CreditManager;
import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameCodec;
import cluster93.runner.protocol.frame.FrameType;
import cluster93.runner.protocol.message.CreditMessage;
import cluster93.runner.protocol.message.DataStreamMessage;
import cluster93.runner.protocol.message.EndStreamMessage;
import cluster93.runner.protocol.message.OpenStreamMessage;
import cluster93.runner.protocol.message.ResetStreamMessage;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Multiplexes bidirectional streaming channels (HTTP bodies, file blobs, TCP sockets) over a framed IPC connection
 * with HTTP/2-style credit-based flow control (D-127, worker-node-protocol.md §4, §5).
 */
public class StreamMultiplexer {

    private static final Logger LOG = LoggerFactory.getLogger(StreamMultiplexer.class);

    public static final int CHUNK_SIZE = 64 * 1024; // 64 KiB chunk size for frame segmentation

    private final Function<Frame, Future<Void>> frameSender;
    private final CreditManager creditManager;
    private final Map<Integer, InputStreamHandler> inStreams = new ConcurrentHashMap<>();
    private final Map<Integer, OutputStreamHandler> outStreams = new ConcurrentHashMap<>();

    private Handler<OpenStreamMessage> openHandler;

    public StreamMultiplexer(Function<Frame, Future<Void>> frameSender) {
        this(frameSender, new CreditManager());
    }

    public StreamMultiplexer(Function<Frame, Future<Void>> frameSender, CreditManager creditManager) {
        this.frameSender = Objects.requireNonNull(frameSender, "frameSender");
        this.creditManager = Objects.requireNonNull(creditManager, "creditManager");
    }

    public void setOpenHandler(Handler<OpenStreamMessage> handler) {
        this.openHandler = handler;
    }

    public CreditManager creditManager() {
        return creditManager;
    }

    /**
     * Sends an 0x30 OPEN frame to announce a new stream to the peer.
     *
     * @param streamId the unique stream identifier
     * @param streamType the stream category (e.g. "file", "socket", "http")
     * @param metadata arbitrary stream metadata
     * @return Future completed when the frame is flushed to the transport
     */
    public Future<Void> openStream(int streamId, String streamType, JsonObject metadata) {
        creditManager.openStream(streamId);
        OpenStreamMessage msg = new OpenStreamMessage(streamId, streamType, metadata);
        return frameSender.apply(msg.toFrame());
    }

    /**
     * Creates an outgoing {@link WriteStream} that transmits bytes as 0x31 DATA frames respecting flow control.
     *
     * @param streamId the target stream identifier
     * @return a write stream accepting buffers
     */
    public WriteStream<Buffer> createOutputStream(int streamId) {
        creditManager.openStream(streamId);
        OutputStreamHandler handler = new OutputStreamHandler(streamId);
        outStreams.put(streamId, handler);
        return handler;
    }

    /**
     * Creates an incoming {@link ReadStream} receiving 0x31 DATA frames from the peer.
     *
     * @param streamId the source stream identifier
     * @return a read stream producing buffers
     */
    public ReadStream<Buffer> createInputStream(int streamId) {
        creditManager.openStream(streamId);
        InputStreamHandler handler = new InputStreamHandler(streamId);
        inStreams.put(streamId, handler);
        return handler;
    }

    /**
     * Sends a 0x33 RESET frame to abruptly abort a stream.
     *
     * @param streamId the target stream
     * @param reason failure description
     * @param errorCode application error code
     * @return Future completed when frame is sent
     */
    public Future<Void> resetStream(int streamId, String reason, int errorCode) {
        cleanupStream(streamId);
        ResetStreamMessage msg = new ResetStreamMessage(streamId, reason, errorCode);
        return frameSender.apply(msg.toFrame());
    }

    /**
     * Handles an incoming frame.
     *
     * @param frame the received binary frame
     * @return true if the frame was handled by this multiplexer, false if it belongs to another handler
     */
    public boolean handleFrame(Frame frame) {
        switch (frame.type()) {
            case OPEN -> {
                OpenStreamMessage open = OpenStreamMessage.fromFrame(frame);
                creditManager.openStream(open.streamId());
                if (openHandler != null) {
                    openHandler.handle(open);
                }
                return true;
            }
            case DATA -> {
                int streamId = frame.channelId();
                InputStreamHandler in = inStreams.get(streamId);
                if (in != null) {
                    in.onData(frame.binary(), frame.isEndStream());
                } else {
                    LOG.warn("Received DATA frame for unknown or closed stream: {}", streamId);
                }
                return true;
            }
            case END -> {
                int streamId = frame.channelId();
                InputStreamHandler in = inStreams.remove(streamId);
                if (in != null) {
                    in.onEnd();
                }
                cleanupStream(streamId);
                return true;
            }
            case RESET -> {
                int streamId = frame.channelId();
                ResetStreamMessage reset = ResetStreamMessage.fromFrame(frame);
                InputStreamHandler in = inStreams.remove(streamId);
                if (in != null) {
                    in.onError(new StreamResetException(reset.reason(), reset.errorCode()));
                }
                OutputStreamHandler out = outStreams.remove(streamId);
                if (out != null) {
                    out.onError(new StreamResetException(reset.reason(), reset.errorCode()));
                }
                cleanupStream(streamId);
                return true;
            }
            case CREDIT -> {
                CreditMessage credit = CreditMessage.fromFrame(frame);
                if (credit.streamId() == 0) {
                    creditManager.addConnectionCredits(credit.bytesToAdd());
                    // Connection credits replenished: wake up all waiting output streams
                    for (OutputStreamHandler out : outStreams.values()) {
                        out.tryFlush();
                    }
                } else {
                    creditManager.addStreamCredits(credit.streamId(), credit.bytesToAdd());
                    OutputStreamHandler out = outStreams.get(credit.streamId());
                    if (out != null) {
                        out.tryFlush();
                    }
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void cleanupStream(int streamId) {
        creditManager.closeStream(streamId);
        inStreams.remove(streamId);
        outStreams.remove(streamId);
    }

    // ---------------------------------------------------------------------------------------------
    // Incoming ReadStream Handler
    // ---------------------------------------------------------------------------------------------

    private class InputStreamHandler implements ReadStream<Buffer> {
        private final int streamId;
        // The parser's context: delivery must stay on it. The script's ack (another thread) resumes the stream, and
        // draining there would race the parser and reorder chunks (a service upload came out shuffled, D-132).
        private final io.vertx.core.Context context = io.vertx.core.Vertx.currentContext();
        private volatile Handler<Buffer> dataHandler;
        private volatile Handler<Void> endHandler;
        private volatile Handler<Throwable> exceptionHandler;
        private volatile boolean paused = false;
        private final Queue<Buffer> pendingQueue = new ArrayDeque<>();
        private boolean ended = false;
        private int unackedBytes = 0;

        InputStreamHandler(int streamId) {
            this.streamId = streamId;
        }

        void onData(Buffer data, boolean isEnd) {
            if (data != null && data.length() > 0) {
                // Always queue, then drain here: the order a chunk arrived in is the order it is delivered in, even if
                // the script's ack has just resumed the stream from another thread.
                pendingQueue.add(data);
                if (!paused && dataHandler != null) {
                    drainPending();
                }
            }
            if (isEnd) {
                onEnd();
            }
        }

        private void deliverData(Buffer data) {
            unackedBytes += data.length();
            if (dataHandler != null) {
                dataHandler.handle(data);
            }
            // Replenish credit to peer once 16 KiB or more has been consumed. Both the stream's window and the shared
            // connection window are granted, or a long-lived stream would exhaust the 4 MiB connection window and
            // deadlock every other stream (D-127).
            if (unackedBytes >= 16 * 1024) {
                int toAdd = unackedBytes;
                unackedBytes = 0;
                frameSender.apply(new CreditMessage(streamId, toAdd).toFrame());
                frameSender.apply(new CreditMessage(0, toAdd).toFrame());
            }
        }

        void onEnd() {
            ended = true;
            if (unackedBytes > 0) {
                int toAdd = unackedBytes;
                unackedBytes = 0;
                frameSender.apply(new CreditMessage(streamId, toAdd).toFrame());
                frameSender.apply(new CreditMessage(0, toAdd).toFrame());
            }
            drainPending();
        }

        void onError(Throwable cause) {
            if (exceptionHandler != null) {
                exceptionHandler.handle(cause);
            }
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.dataHandler = handler;
            if (handler != null && !paused) {
                drainPending();
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            this.paused = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            this.paused = false;
            // Drain on the parser's context so a chunk is never delivered out of order; fall back to a direct drain when
            // there is no Vert.x context (the standalone multiplexer tests) or it is shutting down.
            if (context != null) {
                try {
                    context.runOnContext(v -> drainPending());
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    drainPending();
                }
            } else {
                drainPending();
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return resume();
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            this.endHandler = handler;
            if (ended && pendingQueue.isEmpty() && handler != null) {
                handler.handle(null);
            }
            return this;
        }

        private void drainPending() {
            while (!paused && !pendingQueue.isEmpty() && dataHandler != null) {
                Buffer chunk = pendingQueue.poll();
                deliverData(chunk);
            }
            if (ended && pendingQueue.isEmpty() && endHandler != null) {
                endHandler.handle(null);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Outgoing WriteStream Handler
    // ---------------------------------------------------------------------------------------------

    private class OutputStreamHandler implements WriteStream<Buffer> {
        private final int streamId;
        private int writeQueueMaxSize = CreditManager.DEFAULT_STREAM_INITIAL_WINDOW;
        private Handler<Void> drainHandler;
        private Handler<Throwable> exceptionHandler;
        private final java.util.Deque<BufferChunk> bufferQueue = new ArrayDeque<>();
        private boolean closed = false;

        private record BufferChunk(Buffer data, Promise<Void> promise) {}

        OutputStreamHandler(int streamId) {
            this.streamId = streamId;
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            if (closed) {
                return Future.failedFuture(new IllegalStateException("Stream " + streamId + " is already closed"));
            }
            if (data == null || data.length() == 0) {
                return Future.succeededFuture();
            }

            Promise<Void> promise = Promise.promise();
            bufferQueue.add(new BufferChunk(data, promise));
            tryFlush();
            return promise.future();
        }

        @Override
        public Future<Void> end() {
            if (closed) {
                return Future.succeededFuture();
            }
            closed = true;
            Promise<Void> promise = Promise.promise();
            // Attach end marker chunk
            bufferQueue.add(new BufferChunk(Buffer.buffer(), promise));
            tryFlush();
            return promise.future();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            this.writeQueueMaxSize = Math.max(1024, maxSize);
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            int buffered = 0;
            for (BufferChunk chunk : bufferQueue) {
                buffered += chunk.data().length();
            }
            return buffered >= writeQueueMaxSize;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            this.drainHandler = handler;
            return this;
        }

        synchronized void tryFlush() {
            while (!bufferQueue.isEmpty()) {
                BufferChunk chunk = bufferQueue.peek();
                if (chunk.data().length() == 0) {
                    // Marker for stream end
                    bufferQueue.poll();
                    frameSender.apply(new EndStreamMessage(streamId).toFrame())
                            .onComplete(ar -> {
                                cleanupStream(streamId);
                                if (ar.succeeded()) chunk.promise().tryComplete();
                                else chunk.promise().tryFail(ar.cause());
                            });
                    continue;
                }

                int available = Math.min(creditManager.streamCredits(streamId), creditManager.connectionCredits());
                if (available <= 0) {
                    // Window exhausted; must wait for CREDIT frame
                    break;
                }

                int toSend = Math.min(available, Math.min(CHUNK_SIZE, chunk.data().length()));
                if (creditManager.tryConsume(streamId, toSend)) {
                    bufferQueue.poll();
                    Buffer slice;
                    boolean isLastPiece = (toSend == chunk.data().length());
                    if (isLastPiece) {
                        slice = chunk.data();
                    } else {
                        slice = chunk.data().slice(0, toSend);
                        Buffer remainder = chunk.data().slice(toSend, chunk.data().length());
                        bufferQueue.addFirst(new BufferChunk(remainder, chunk.promise()));
                    }

                    Frame frame = Frame.data(streamId, slice, false);
                    frameSender.apply(frame)
                            .onComplete(ar -> {
                                if (isLastPiece) {
                                    if (ar.succeeded()) chunk.promise().tryComplete();
                                    else chunk.promise().tryFail(ar.cause());
                                } else if (ar.failed()) {
                                    chunk.promise().tryFail(ar.cause());
                                }
                            });
                } else {
                    break;
                }
            }

            if (!writeQueueFull() && drainHandler != null) {
                drainHandler.handle(null);
            }
        }

        void onError(Throwable cause) {
            closed = true;
            while (!bufferQueue.isEmpty()) {
                BufferChunk chunk = bufferQueue.poll();
                chunk.promise().tryFail(cause);
            }
            if (exceptionHandler != null) {
                exceptionHandler.handle(cause);
            }
        }
    }

    /**
     * Exception thrown when an active stream is terminated via 0x33 RESET.
     */
    public static class StreamResetException extends RuntimeException {
        private final int errorCode;

        public StreamResetException(String reason, int errorCode) {
            super("Stream reset (code " + errorCode + "): " + reason);
            this.errorCode = errorCode;
        }

        public int errorCode() {
            return errorCode;
        }
    }
}
