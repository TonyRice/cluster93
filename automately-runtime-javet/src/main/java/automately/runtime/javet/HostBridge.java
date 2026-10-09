package automately.runtime.javet;

import automately.core.files.FileWriter;
import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.Inbound;
import automately.core.runtime.api.HostApis;
import automately.core.runtime.api.Registration;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueString;
import com.caoccao.javet.values.reference.V8ValueError;
import com.caoccao.javet.values.reference.V8ValuePromise;
import com.caoccao.javet.values.reference.V8ValueTypedArray;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the API bindings ({@link KvBinding}, {@link BusBinding}, …) share for one execution (D-060): a JavaScript promise
 * per host call, created here and settled on the isolate's event loop when the Java future completes; listeners and the
 * inbound events they receive; streams with backpressure; HTTP exchanges and uploads in progress. Its own
 * {@code @V8Function}s are the plumbing the scripts' wrappers use (acknowledging chunks, cancelling streams, answering
 * requests). Unless noted, methods run on the isolate's thread.
 */
public final class HostBridge {

    static final int MAX_CALLS_IN_FLIGHT = 256;
    static final int MAX_LISTENERS = 100;

    /** Chunks handed to the script but not yet consumed; the source pauses at the high mark and resumes at the low. */
    static final int HIGH_WATER = 8;
    static final int LOW_WATER = 2;

    /** Runs on the isolate's thread once a call succeeded; returns what the promise resolves with. */
    @FunctionalInterface
    interface OnIsolate<T> {
        Object apply(T result) throws JavetException;
    }

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

    private final HostApis apis;
    private final EventLoop loop;
    private final JsValues values;
    private final Map<Integer, Registration> registrations = new HashMap<>();
    private final Map<Integer, Inbound> awaitingReply = new ConcurrentHashMap<>();
    private final Map<Integer, StreamState> streams = new HashMap<>();
    private final Map<Integer, HttpExchange> exchanges = new ConcurrentHashMap<>();
    private final Map<Integer, FileWriter> writers = new ConcurrentHashMap<>();
    private final Map<Integer, automately.core.runtime.OutgoingRequest> outgoing = new ConcurrentHashMap<>();
    private final Map<Integer, automately.core.runtime.OutgoingSocket> sockets = new ConcurrentHashMap<>();
    private final Map<Integer, automately.core.runtime.OutgoingHttp2Session> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, automately.core.runtime.OutgoingHttp2Stream> http2Streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextReplyId = new AtomicInteger(1);
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    HostBridge(HostApis apis, EventLoop loop, JsValues values) {
        this.apis = apis;
        this.loop = loop;
        this.values = values;
    }

    JsValues values() {
        return values;
    }

    // --- promises ---

    /** A promise for {@code call}, resolved with its result converted to JavaScript. */
    <T> V8ValuePromise promise(Supplier<Future<T>> call) throws JavetException {
        return promise(call, result -> result);
    }

    /**
     * A promise for {@code call}. The call starts now, on the isolate's thread, so it may read its JavaScript arguments;
     * a validation exception rejects the promise. On success, {@code then} turns the result into the resolved value on
     * the isolate's thread. At most {@value #MAX_CALLS_IN_FLIGHT} calls are in flight.
     */
    <T> V8ValuePromise promise(Supplier<Future<T>> call, OnIsolate<T> then) throws JavetException {
        V8ValuePromise resolver = values.runtime().createV8ValuePromise();
        loop.callStarted();
        Future<T> result = loop.callsInFlight() > MAX_CALLS_IN_FLIGHT
                ? Future.failedFuture(HostException.limit("Too many host calls in flight (limit " + MAX_CALLS_IN_FLIGHT + ")"))
                : start(call);
        result.onComplete(ar -> loop.post(js -> {
            loop.callSettled();
            try (resolver) {
                if (ar.succeeded()) {
                    Object value;
                    try {
                        value = then.apply(ar.result());
                    } catch (RuntimeException e) {
                        reject(resolver, e);
                        return;
                    }
                    try (V8Value resolved = values.toJs(value)) {
                        resolver.resolve(resolved);
                    }
                } else {
                    reject(resolver, ar.cause());
                }
            }
        }));
        return resolver.getPromise();
    }

    private void reject(V8ValuePromise resolver, Throwable cause) throws JavetException {
        try (V8ValueError error = values.error(cause)) {
            resolver.reject(error);
        }
    }

    private static <T> Future<T> start(Supplier<Future<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    // --- listeners (bus subscriptions and handlers, HTTP serving) ---

    /**
     * Registers listener {@code listenerId} (the wrapper's id for its handler) through {@code register}; the promise
     * resolves once events can arrive. At most {@value #MAX_LISTENERS} at once.
     */
    V8ValuePromise listen(int listenerId, Function<Consumer<Inbound>, Future<Registration>> register) throws JavetException {
        return promise(() -> registrations.size() >= MAX_LISTENERS
                        ? Future.failedFuture(HostException.limit("Too many listeners (limit " + MAX_LISTENERS + ")"))
                        : register.apply(inbound -> deliver(listenerId, inbound)),
                registration -> {
                    registrations.put(listenerId, registration);
                    loop.listenerAdded();
                    return null;
                });
    }

    /** Serving HTTP: each request arrives at listener {@code listenerId} as an exchange with a streaming body. */
    V8ValuePromise serve(int listenerId) throws JavetException {
        return promise(() -> apis.http().serve(exchange -> onExchange(listenerId, exchange)),
                registration -> {
                    registrations.put(listenerId, registration);
                    loop.listenerAdded();
                    return null;
                });
    }

    /** A Node server listening on {@code port} (D-123): its requests arrive at listener {@code listenerId}, as serve's. */
    V8ValuePromise listen(int listenerId, int port) throws JavetException {
        return promise(() -> apis.http().listen(port, exchange -> onExchange(listenerId, exchange)),
                registration -> {
                    registrations.put(listenerId, registration);
                    loop.listenerAdded();
                    return null;
                });
    }

    /** Any thread. */
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

    /** Any thread. */
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

    @V8Function(name = "unlisten")
    public void unlisten(int listenerId) {
        Registration registration = registrations.remove(listenerId);
        if (registration != null) {
            registration.cancel();
            loop.listenerRemoved();
        }
    }

    /** A handler's answer to request {@code replyId}: a JSON value, or {@code {status, message}} if it failed. */
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

    // --- streams (request bodies, fetch and file bodies) ---

    /** Starts delivering a stream's chunks to the script; returns its id. */
    int openStream(BodyStream stream) {
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

    private void deliverChunk(ScriptEntryPoints js, int id, byte[] bytes) throws JavetException {
        if (!streams.containsKey(id)) {
            return;
        }
        js.chunk(id, bytes);
    }

    private void endStream(ScriptEntryPoints js, int id, String error) throws JavetException {
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

    /** The script stopped reading (broke out of a loop, or never read a body). */
    @V8Function(name = "cancelStream")
    public void cancelStream(int id) {
        StreamState state = streams.remove(id);
        if (state != null) {
            loop.streamClosed();
            state.stream.cancel();
        }
    }

    // --- HTTP exchanges ---

    /** The exchange, or null once it's finished. */
    HttpExchange exchange(int exchangeId) {
        return exchanges.get(exchangeId);
    }

    /** Takes the exchange out (it's being finished); null if it already was. */
    HttpExchange finish(int exchangeId) {
        return exchanges.remove(exchangeId);
    }

    // --- uploads ---

    int addWriter(FileWriter writer) {
        int id = nextId.getAndIncrement();
        writers.put(id, writer);
        return id;
    }

    FileWriter writer(int id) {
        return writers.get(id);
    }

    FileWriter removeWriter(int id) {
        return writers.remove(id);
    }

    // --- sockets (net, tls; D-116) ---

    int addSocket(automately.core.runtime.OutgoingSocket socket) {
        int id = nextId.getAndIncrement();
        if (closed.get()) {
            socket.destroy();
        } else {
            sockets.put(id, socket);
        }
        return id;
    }

    automately.core.runtime.OutgoingSocket socket(int id) {
        return sockets.get(id);
    }

    automately.core.runtime.OutgoingSocket removeSocket(int id) {
        return sockets.remove(id);
    }

    // --- HTTP/2 sessions and streams (D-117) ---

    int addSession(automately.core.runtime.OutgoingHttp2Session session) {
        int id = nextId.getAndIncrement();
        if (closed.get()) {
            session.destroy();
        } else {
            sessions.put(id, session);
        }
        return id;
    }

    automately.core.runtime.OutgoingHttp2Session session(int id) {
        return sessions.get(id);
    }

    automately.core.runtime.OutgoingHttp2Session removeSession(int id) {
        return sessions.remove(id);
    }

    int addHttp2Stream(automately.core.runtime.OutgoingHttp2Stream stream) {
        int id = nextId.getAndIncrement();
        http2Streams.put(id, stream);
        return id;
    }

    automately.core.runtime.OutgoingHttp2Stream http2Stream(int id) {
        return http2Streams.get(id);
    }

    automately.core.runtime.OutgoingHttp2Stream removeHttp2Stream(int id) {
        return http2Streams.remove(id);
    }

    // --- outgoing HTTP requests (node:http, D-109) ---

    int addOutgoing(automately.core.runtime.OutgoingRequest request) {
        int id = nextId.getAndIncrement();
        if (closed.get()) {
            request.abort();
        } else {
            outgoing.put(id, request);
        }
        return id;
    }

    automately.core.runtime.OutgoingRequest outgoing(int id) {
        return outgoing.get(id);
    }

    automately.core.runtime.OutgoingRequest removeOutgoing(int id) {
        return outgoing.remove(id);
    }

    /**
     * Bytes from a typed array, or a string as UTF-8. Javet's {@code toBytes()} copies the array's whole buffer, not the
     * view: a {@code Buffer} slice, or one whose buffer was allocated bigger than it, came out with the wrong bytes. So
     * the view's own bytes are read, from its offset for its length (D-103).
     */
    static byte[] bytes(V8Value data) throws JavetException {
        if (data instanceof V8ValueTypedArray array) {
            int offset = array.getByteOffset();
            int length = array.getByteLength();
            try (com.caoccao.javet.values.reference.V8ValueArrayBuffer buffer = array.getBuffer()) {
                byte[] bytes = new byte[length];
                buffer.getByteBuffer().get(offset, bytes, 0, length);
                return bytes;
            }
        }
        if (data instanceof V8ValueString string) {
            return string.getValue().getBytes(StandardCharsets.UTF_8);
        }
        return String.valueOf(data).getBytes(StandardCharsets.UTF_8);
    }

    /** Ends delivery: stops listeners and answers anything the script never answered. */
    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        registrations.values().forEach(Registration::cancel);
        registrations.clear();
        streams.values().forEach(state -> state.stream.cancel());
        streams.clear();
        exchanges.values().forEach(exchange -> exchange.fail(503, "The script ended before it answered"));
        exchanges.clear();
        writers.values().forEach(FileWriter::abort);
        writers.clear();
        outgoing.values().forEach(automately.core.runtime.OutgoingRequest::abort);
        outgoing.clear();
        // A run's sockets close with it, whatever it left open (D-116).
        sockets.values().forEach(automately.core.runtime.OutgoingSocket::destroy);
        sockets.clear();
        http2Streams.clear();
        sessions.values().forEach(automately.core.runtime.OutgoingHttp2Session::destroy);
        sessions.clear();
        awaitingReply.values().forEach(inbound -> inbound.fail(503, "The script ended before it answered"));
        awaitingReply.clear();
        apis.close();
    }
}
