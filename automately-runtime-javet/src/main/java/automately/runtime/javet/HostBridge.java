package automately.runtime.javet;

import automately.core.runtime.BodyStream;
import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.HostServices;
import automately.core.runtime.Inbound;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueString;
import com.caoccao.javet.values.reference.V8ValueTypedArray;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges the prelude's host calls and listeners to {@link HostServices}. Calls start on the isolate's
 * thread and complete on Vert.x; their results come back as tasks on the event loop. Inbound events
 * (bus messages, HTTP requests) arrive on Vert.x threads and are posted the same way; an answer from the
 * script goes back through {@link #reply}.
 */
public final class HostBridge {

    static final int MAX_CALLS_IN_FLIGHT = 256;
    static final int MAX_LISTENERS = 100;

    private final HostServices host;
    private final EventLoop loop;
    /** Isolate thread only. */
    private final Map<Integer, HostServices.Registration> registrations = new HashMap<>();
    private final Map<Integer, Inbound> awaitingReply = new ConcurrentHashMap<>();
    private final AtomicInteger nextReplyId = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Chunks handed to the script but not yet consumed; the source pauses at the high mark and resumes at the low. */
    static final int HIGH_WATER = 8;
    static final int LOW_WATER = 2;

    /**
     * Counted when a chunk **arrives** (on the source's thread), not when the isolate gets to it: otherwise a
     * stalled script would let chunks pile up in the event loop's task queue without bound.
     */
    private static final class StreamState {
        final BodyStream stream;
        int unconsumed;
        boolean paused;

        StreamState(BodyStream stream) {
            this.stream = stream;
        }

        synchronized void arrived() {
            unconsumed++;
            if (!paused && unconsumed >= HIGH_WATER) {
                paused = true;
                stream.pause();
            }
        }

        synchronized void consumed() {
            unconsumed--;
            if (paused && unconsumed <= LOW_WATER) {
                paused = false;
                stream.resume();
            }
        }
    }

    /** Isolate thread only. */
    private final Map<Integer, StreamState> streams = new HashMap<>();
    private final Map<Integer, HttpExchange> exchanges = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    HostBridge(HostServices host, EventLoop loop) {
        this.host = host;
        this.loop = loop;
    }

    @V8Function(name = "call")
    public void call(String operation, String argsJson, int callId) {
        loop.callStarted();
        if (loop.callsInFlight() > MAX_CALLS_IN_FLIGHT) {
            settleLater(callId, Future.failedFuture(HostException.limit("Too many host calls in flight (limit " + MAX_CALLS_IN_FLIGHT + ")")));
            return;
        }
        settleLater(callId, start(() -> host.call(operation, new JsonObject(argsJson))));
    }

    @V8Function(name = "listen")
    public void listen(String kind, String argsJson, int listenerId, int callId) {
        loop.callStarted();
        if (registrations.size() >= MAX_LISTENERS) {
            settleLater(callId, Future.failedFuture(HostException.limit("Too many listeners (limit " + MAX_LISTENERS + ")")));
            return;
        }
        Future<HostServices.Registration> registered = start(() -> host.listen(kind, new JsonObject(argsJson), inbound -> deliver(listenerId, inbound)));
        registered.onComplete(ar -> loop.post(js -> {
            loop.callSettled();
            if (ar.succeeded()) {
                registrations.put(listenerId, ar.result());
                loop.listenerAdded();
                js.settle(callId, true, "null");
            } else {
                js.settle(callId, false, errorJson(ar.cause()));
            }
        }));
    }

    // --- streams (request bodies, fetch bodies) ---

    /** Starts delivering a stream's chunks to the script. Isolate thread only. */
    private int openStream(BodyStream stream) {
        int id = nextId.getAndIncrement();
        StreamState state = new StreamState(stream);
        streams.put(id, state);
        loop.streamOpened();
        stream.handler(
                chunk -> {
                    state.arrived();
                    loop.post(js -> deliverChunk(js, id, chunk.getBytes()));
                },
                () -> loop.post(js -> endStream(js, id, null)),
                err -> loop.post(js -> endStream(js, id, String.valueOf(err.getMessage()))));
        stream.resume();
        return id;
    }

    private void deliverChunk(ScriptEntryPoints js, int id, byte[] bytes) throws com.caoccao.javet.exceptions.JavetException {
        if (!streams.containsKey(id)) {
            return;
        }
        js.chunk(id, bytes);
    }

    private void endStream(ScriptEntryPoints js, int id, String error) throws com.caoccao.javet.exceptions.JavetException {
        if (streams.remove(id) == null) {
            return;
        }
        loop.streamClosed();
        if (error == null) {
            js.chunk(id, null);
        } else {
            js.streamError(id, error);
        }
    }

    /** The script consumed a chunk. */
    @V8Function(name = "ack")
    public void ack(int id) {
        StreamState state = streams.get(id);
        if (state != null) {
            state.consumed();
        }
    }

    /** The script stopped reading (broke out of a loop, or never read a fetch body). */
    @V8Function(name = "cancelStream")
    public void cancelStream(int id) {
        StreamState state = streams.remove(id);
        if (state != null) {
            loop.streamClosed();
            state.stream.cancel();
        }
    }

    // --- serving HTTP ---

    @V8Function(name = "serve")
    public void serve(int listenerId, int callId) {
        loop.callStarted();
        Future<HostServices.Registration> registered = start(() -> host.serve(exchange -> onExchange(listenerId, exchange)));
        registered.onComplete(ar -> loop.post(js -> {
            loop.callSettled();
            if (ar.succeeded()) {
                registrations.put(listenerId, ar.result());
                loop.listenerAdded();
                js.settle(callId, true, "null");
            } else {
                js.settle(callId, false, errorJson(ar.cause()));
            }
        }));
    }

    private void onExchange(int listenerId, HttpExchange exchange) {
        if (closed.get()) {
            exchange.fail(503, "The service is no longer running");
            return;
        }
        int exchangeId = nextId.getAndIncrement();
        exchanges.put(exchangeId, exchange);
        String request = exchange.request().encode();
        loop.post(js -> js.request(listenerId, exchangeId, request, openStream(exchange.body())));
    }

    @V8Function(name = "respond")
    public void respond(int exchangeId, int status, String headersJson) {
        HttpExchange exchange = exchanges.get(exchangeId);
        if (exchange != null) {
            exchange.respond(status, new JsonObject(headersJson));
        }
    }

    /** Writes a response chunk; the script's promise settles when the client has taken it. */
    @V8Function(name = "write")
    public void write(int exchangeId, V8Value data, int callId) throws com.caoccao.javet.exceptions.JavetException {
        loop.callStarted();
        HttpExchange exchange = exchanges.get(exchangeId);
        if (exchange == null) {
            settleLater(callId, Future.failedFuture(new HostException("Error", "closed", "The response is already finished")));
            return;
        }
        settleLater(callId, exchange.write(Buffer.buffer(bytes(data))).map(v -> (Object) null)
                .recover(err -> Future.failedFuture(new HostException("Error", "client_disconnected", err.getMessage()))));
    }

    @V8Function(name = "end")
    public void end(int exchangeId) {
        HttpExchange exchange = exchanges.remove(exchangeId);
        if (exchange != null) {
            exchange.end();
        }
    }

    @V8Function(name = "fail")
    public void fail(int exchangeId, int status, String message) {
        HttpExchange exchange = exchanges.remove(exchangeId);
        if (exchange != null) {
            exchange.fail(status, message);
        }
    }

    static byte[] bytes(V8Value data) throws com.caoccao.javet.exceptions.JavetException {
        if (data instanceof V8ValueTypedArray array) {
            return array.toBytes();
        }
        if (data instanceof V8ValueString string) {
            return string.getValue().getBytes(StandardCharsets.UTF_8);
        }
        return String.valueOf(data).getBytes(StandardCharsets.UTF_8);
    }

    // --- fetch ---

    @V8Function(name = "fetch")
    public void fetch(String argsJson, int callId) {
        loop.callStarted();
        Future<FetchedResponse> response = start(() -> host.fetch(new JsonObject(argsJson)));
        response.onComplete(ar -> loop.post(js -> {
            loop.callSettled();
            if (ar.succeeded()) {
                int streamId = openStream(ar.result().body());
                js.settle(callId, true, ar.result().meta().copy().put("streamId", streamId).encode());
            } else {
                js.settle(callId, false, errorJson(ar.cause()));
            }
        }));
    }

    @V8Function(name = "unlisten")
    public void unlisten(int listenerId) {
        HostServices.Registration registration = registrations.remove(listenerId);
        if (registration != null) {
            registration.cancel();
            loop.listenerRemoved();
        }
    }

    @V8Function(name = "reply")
    public void reply(int replyId, boolean ok, String json) {
        Inbound inbound = awaitingReply.remove(replyId);
        if (inbound == null) {
            return;
        }
        if (ok) {
            inbound.reply(Json.decodeValue(json));
        } else {
            JsonObject error = new JsonObject(json);
            inbound.fail(error.getInteger("status", 500), error.getString("message", "The script failed"));
        }
    }

    /** Ends delivery: stops listeners and answers anything the script never answered. */
    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        registrations.values().forEach(HostServices.Registration::cancel);
        registrations.clear();
        streams.values().forEach(state -> state.stream.cancel());
        streams.clear();
        exchanges.values().forEach(exchange -> exchange.fail(503, "The script ended before it answered"));
        exchanges.clear();
        awaitingReply.values().forEach(inbound -> inbound.fail(503, "The script ended before it answered"));
        awaitingReply.clear();
        host.close();
    }

    private void deliver(int listenerId, Inbound inbound) {
        if (closed.get()) {
            if (inbound.expectsReply()) {
                inbound.fail(503, "The script is no longer running");
            }
            return;
        }
        int replyId = 0;
        if (inbound.expectsReply()) {
            replyId = nextReplyId.getAndIncrement();
            awaitingReply.put(replyId, inbound);
        }
        int answerId = replyId;
        String payload = Json.encode(inbound.payload());
        loop.post(js -> js.deliver(listenerId, payload, answerId));
    }

    private void settleLater(int callId, Future<?> result) {
        result.onComplete(ar -> loop.post(js -> {
            loop.callSettled();
            if (ar.succeeded()) {
                js.settle(callId, true, Json.encode(ar.result()));
            } else {
                js.settle(callId, false, errorJson(ar.cause()));
            }
        }));
    }

    private static <T> Future<T> start(java.util.function.Supplier<Future<T>> operation) {
        try {
            return operation.get();
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    static String errorJson(Throwable error) {
        if (error instanceof HostException host) {
            return new JsonObject().put("name", host.errorName()).put("code", host.code()).put("message", host.getMessage()).encode();
        }
        return new JsonObject().put("name", "Error").put("code", "host_error")
                .put("message", error.getMessage() == null ? error.toString() : error.getMessage()).encode();
    }
}
