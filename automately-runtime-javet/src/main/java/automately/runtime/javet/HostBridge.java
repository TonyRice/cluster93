package automately.runtime.javet;

import automately.core.runtime.HostException;
import automately.core.runtime.HostServices;
import automately.core.runtime.Inbound;
import com.caoccao.javet.annotations.V8Function;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

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
