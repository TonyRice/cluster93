package automately.core.host;

import automately.core.runtime.HostException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListenerDecorator;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Node's {@code http2} reports and Vert.x doesn't expose, read from the Netty connection beneath a Vert.x HTTP/2
 * client connection (D-118): each HEADERS frame's real flags, request trailers, and the {@code state} of the session and
 * of each stream. Reached through Vert.x's internal connection (the codec implementation, its default); if it isn't
 * there, {@link #of} answers null and callers fall back.
 */
final class NettyHttp2 {

    // Node's (nghttp2's) frame flags.
    static final int END_STREAM = 0x1;
    static final int END_HEADERS = 0x4;
    static final int PADDED = 0x8;
    static final int PRIORITY = 0x20;

    private final ChannelHandlerContext ctx;
    private final Http2ConnectionHandler handler;
    /** Each stream's HEADERS frames' flags, in the order they arrived: the response's, then the trailers'. */
    private final Map<Integer, Queue<Integer>> headerFlags = new ConcurrentHashMap<>();
    /**
     * Each stream's state as its last frame left it. Node reads nghttp2's state while it handles the frame; here the
     * script's event comes later, when Netty may have closed the stream and let it go.
     */
    private final Map<Integer, JsonObject> lastState = new ConcurrentHashMap<>();

    private NettyHttp2(ChannelHandlerContext ctx, Http2ConnectionHandler handler) {
        this.ctx = ctx;
        this.handler = handler;
    }

    /** The Netty side of {@code connection}, with its HEADERS frames recorded from now on; null if it can't be reached. */
    static NettyHttp2 of(io.vertx.core.http.HttpClientConnection connection) {
        Object inner = connection instanceof io.vertx.core.http.impl.UnpooledHttpClientConnection unpooled ? unpooled.unwrap() : connection;
        if (!(inner instanceof io.vertx.core.net.impl.ConnectionBase base)) {
            return null;
        }
        ChannelHandlerContext ctx = base.channelHandlerContext();
        Http2ConnectionHandler handler = ctx.pipeline().get(Http2ConnectionHandler.class);
        if (handler == null) {
            return null;
        }
        NettyHttp2 netty = new NettyHttp2(ctx, handler);
        netty.recordHeaders();
        return netty;
    }

    private void recordHeaders() {
        var decoder = handler.decoder();
        decoder.frameListener(new Http2FrameListenerDecorator(decoder.frameListener()) {
            @Override
            public void onHeadersRead(ChannelHandlerContext c, int streamId, Http2Headers headers, int padding, boolean endOfStream)
                    throws Http2Exception {
                record(streamId, END_HEADERS | (endOfStream ? END_STREAM : 0) | (padding > 0 ? PADDED : 0));
                super.onHeadersRead(c, streamId, headers, padding, endOfStream);
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext c, int streamId, Http2Headers headers, int streamDependency, short weight,
                                      boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
                record(streamId, END_HEADERS | PRIORITY | (endOfStream ? END_STREAM : 0) | (padding > 0 ? PADDED : 0));
                super.onHeadersRead(c, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
            }

            @Override
            public int onDataRead(ChannelHandlerContext c, int streamId, io.netty.buffer.ByteBuf data, int padding, boolean endOfStream)
                    throws Http2Exception {
                snapshot(streamId);
                return super.onDataRead(c, streamId, data, padding, endOfStream);
            }
        });
    }

    private void record(int streamId, int flags) {
        headerFlags.computeIfAbsent(streamId, id -> new ArrayDeque<>()).add(flags);
        snapshot(streamId);
    }

    /** Netty calls the listener before it closes a stream the frame ended, so this is the state Node shows for the frame. */
    private void snapshot(int streamId) {
        JsonObject state = liveState(streamId);
        if (state != null) {
            lastState.put(streamId, state);
        }
    }

    /** The next HEADERS frame's flags for the stream, in arrival order; {@code fallback} if none was recorded. */
    int nextHeaderFlags(int streamId, int fallback) {
        Queue<Integer> flags = headerFlags.get(streamId);
        Integer next = flags == null ? null : flags.poll();
        return next == null ? fallback : next;
    }

    void forget(int streamId) {
        headerFlags.remove(streamId);
        lastState.remove(streamId);
    }

    /**
     * Ends the request with a HEADERS frame of trailers, as Node's sendTrailers. Written through Vert.x's own HTTP/2
     * stream, so Vert.x knows the request ended: a frame written past it leaves the response never ending. Vert.x's
     * client has no public way to send request trailers; run this on the connection's context.
     */
    static Future<Void> writeTrailers(io.vertx.core.http.HttpClientRequest request, JsonObject trailers) {
        // Vert.x's HTTP/2 headers: its own map over Netty's.
        io.vertx.core.MultiMap headers = new io.vertx.core.http.impl.headers.HttpHeaders(new io.netty.handler.codec.http2.DefaultHttp2Headers());
        trailers.forEach(entry -> {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (entry.getValue() instanceof io.vertx.core.json.JsonArray list) {
                list.forEach(value -> headers.add(name, String.valueOf(value)));
            } else {
                headers.add(name, String.valueOf(entry.getValue()));
            }
        });
        try {
            Object stream = field(request, "stream");
            // The connection pool wraps streams to gather statistics.
            while (stream.getClass().getSimpleName().equals("StatisticsGatheringHttpClientStream")) {
                stream = field(stream, "delegate");
            }
            var write = Class.forName("io.vertx.core.http.impl.http2.DefaultHttp2Stream")
                    .getDeclaredMethod("writeHeaders", io.vertx.core.MultiMap.class, boolean.class);
            write.setAccessible(true);
            @SuppressWarnings("unchecked")
            Future<Void> sent = (Future<Void>) write.invoke(stream, headers, true);
            setField(request, "trailersSent", true);
            return sent;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Future.failedFuture(new HostException("Error", "ERR_HTTP2_TRAILERS_NOT_READY", "Trailers can't be sent on this connection"));
        }
    }

    /** Node's {@code session.state}, from Netty's connection. */
    JsonObject sessionState() {
        Http2Connection connection = handler.connection();
        Http2Stream root = connection.connectionStream();
        Http2LocalFlowController local = connection.local().flowController();
        Http2RemoteFlowController remote = connection.remote().flowController();
        int lastLocal = connection.local().lastStreamCreated();
        int window = local.windowSize(root);
        int initial = local.initialWindowSize(root);
        var outbound = ctx.channel().unsafe().outboundBuffer();
        return new JsonObject()
                // nghttp2's meanings: the window as configured, what arrived and isn't acknowledged yet, and what's left.
                .put("effectiveLocalWindowSize", initial)
                .put("effectiveRecvDataLength", Math.max(0, initial - window))
                .put("nextStreamID", lastLocal == 0 ? 1 : lastLocal + 2)
                .put("localWindowSize", window)
                .put("lastProcStreamID", connection.remote().lastStreamCreated())
                .put("remoteWindowSize", remote.windowSize(root))
                .put("outboundQueueSize", outbound == null ? 0 : outbound.size())
                .put("deflateDynamicTableSize", hpackSize(handler.encoder().frameWriter(), "headersEncoder", "hpackEncoder"))
                .put("inflateDynamicTableSize", hpackSize(frameReader(), "headersDecoder", "hpackDecoder"));
    }

    /**
     * Node's {@code stream.state}: live while Netty has the stream, then as its last frame left it, and {} once
     * forgotten, as Node's. Weight is fixed: Node 26 sends no priorities.
     */
    JsonObject streamState(int streamId) {
        JsonObject live = liveState(streamId);
        if (live != null) {
            return live;
        }
        JsonObject last = lastState.get(streamId);
        return last == null ? new JsonObject() : last.copy();
    }

    private JsonObject liveState(int streamId) {
        Http2Stream stream = handler.connection().stream(streamId);
        if (stream == null) {
            return null;
        }
        Http2Stream.State state = stream.state();
        boolean localClosed = state == Http2Stream.State.HALF_CLOSED_LOCAL || state == Http2Stream.State.CLOSED;
        boolean remoteClosed = state == Http2Stream.State.HALF_CLOSED_REMOTE || state == Http2Stream.State.CLOSED;
        return new JsonObject()
                .put("state", nghttp2State(state))
                .put("weight", 16)
                .put("sumDependencyWeight", 0)
                .put("localClose", localClosed ? 1 : 0)
                .put("remoteClose", remoteClosed ? 1 : 0)
                .put("localWindowSize", handler.connection().local().flowController().windowSize(stream));
    }

    /** nghttp2's numbers for stream states, as Node's {@code constants.NGHTTP2_STREAM_STATE_*}. */
    static int nghttp2State(Http2Stream.State state) {
        return switch (state) {
            case IDLE -> 1;
            case OPEN -> 2;
            case RESERVED_LOCAL -> 3;
            case RESERVED_REMOTE -> 4;
            case HALF_CLOSED_LOCAL -> 5;
            case HALF_CLOSED_REMOTE -> 6;
            case CLOSED -> 7;
        };
    }

    private Object frameReader() {
        try {
            Object decoder = handler.decoder();
            // Decorating decoders wrap the default one, which holds the frame reader.
            while (decoder instanceof io.netty.handler.codec.http2.DecoratingHttp2ConnectionDecoder) {
                decoder = field(decoder, "delegate");
            }
            return field(decoder, "frameReader");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A HPACK table's size in RFC 7541's measure, as nghttp2 reports it. Netty keeps it behind package-private fields; if
     * a Netty version moves them, the answer is 0.
     */
    private static long hpackSize(Object frameCodec, String codecField, String hpackField) {
        try {
            // A logging reader or writer wraps the default one.
            while (frameCodec instanceof io.netty.handler.codec.http2.Http2InboundFrameLogger
                    || frameCodec instanceof io.netty.handler.codec.http2.Http2OutboundFrameLogger) {
                frameCodec = field(frameCodec, frameCodec instanceof io.netty.handler.codec.http2.Http2InboundFrameLogger ? "reader" : "writer");
            }
            Object codec = field(frameCodec, codecField);
            Object hpack = field(codec, hpackField);
            var size = hpack.getClass().getDeclaredMethod("size");
            size.setAccessible(true);
            return ((Number) size.invoke(hpack)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(owner, value);
                return;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
