package cluster93.core.host;

import cluster93.core.runtime.BodyStream;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.OutgoingSocket;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The connection of a request that asks to upgrade ({@code Connection: upgrade}), handed to a Node server's
 * {@code 'upgrade'} listener as a socket (D-125). The script writes its own answer, as on Node: a {@code 101} head
 * switches the connection to a byte tunnel (the ingress then tunnels too), and anything else is sent as an ordinary
 * response, its body what follows the head. What the client sends after the switch arrives on {@link #data()}.
 */
final class UpgradeSocket implements OutgoingSocket {

    /** A response head the script writes may be at most this long, as the ingress's limit for request heads. */
    static final int MAX_HEAD_BYTES = 64 * 1024;
    private static final byte[] END_OF_HEAD = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final Context context;
    private final JsonObject info;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    // All below on the context.
    private Buffer head = Buffer.buffer();
    private boolean answered;
    private NetSocket socket;
    private Future<Void> last = Future.succeededFuture();
    private BodyStream delegate;
    private Consumer<Buffer> onChunk;
    private Runnable onEnd;
    private Consumer<Throwable> onError;
    private boolean resumed;
    private boolean dataEnded;

    UpgradeSocket(HttpServerRequest request, Context context, JsonObject info, Runnable onClose) {
        this.request = request;
        this.response = request.response();
        this.context = context;
        this.info = info;
        this.onClose = onClose;
    }

    /** Whether a request asks to upgrade: as llhttp decides it, a {@code Connection} with the token and an {@code Upgrade}. */
    static boolean asks(HttpServerRequest request) {
        if (request.version() != io.vertx.core.http.HttpVersion.HTTP_1_1 || request.method() == io.vertx.core.http.HttpMethod.CONNECT
                || request.getHeader("Upgrade") == null) {
            return false;
        }
        for (String value : request.headers().getAll("Connection")) {
            for (String token : value.split(",")) {
                if (token.trim().equalsIgnoreCase("upgrade")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The connection closed, either way: once. */
    void closed() {
        if (closed.compareAndSet(false, true)) {
            onClose.run();
            endData();
        }
    }

    @Override
    public JsonObject info() {
        return info;
    }

    @Override
    public BodyStream data() {
        return new BodyStream() {
            @Override
            public void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
                context.runOnContext(v -> {
                    onChunk = chunk;
                    onEnd = end;
                    onError = error;
                    if (delegate != null) {
                        delegate.handler(chunk, end, error);
                    } else if (dataEnded) {
                        end.run();
                    }
                });
            }

            @Override
            public void pause() {
                context.runOnContext(v -> {
                    resumed = false;
                    if (delegate != null) {
                        delegate.pause();
                    }
                });
            }

            @Override
            public void resume() {
                context.runOnContext(v -> {
                    resumed = true;
                    if (delegate != null) {
                        delegate.resume();
                    }
                });
            }

            @Override
            public void cancel() {
                destroy();
            }
        };
    }

    private void endData() {
        context.runOnContext(v -> {
            if (delegate == null && !dataEnded) {
                dataEnded = true;
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
    }

    @Override
    public Future<Void> write(Buffer chunk) {
        return then(() -> {
            if (socket != null) {
                // Done once the bytes reached the socket: the script's next write waits, which is its backpressure.
                return socket.write(chunk).recover(err -> epipe());
            }
            if (answered) {
                if (response.closed() || response.ended()) {
                    return epipe();
                }
                return response.write(chunk).recover(err -> epipe());
            }
            head.appendBuffer(chunk);
            int at = indexOf(head, END_OF_HEAD);
            if (at < 0) {
                if (head.length() > MAX_HEAD_BYTES) {
                    destroy();
                    return epipe();
                }
                return Future.succeededFuture();
            }
            Buffer rest = head.getBuffer(at + END_OF_HEAD.length, head.length());
            String text = head.getString(0, at, "ISO-8859-1");
            head = null;
            answered = true;
            return answer(text, rest);
        });
    }

    /** The script's head: a 101 switches the connection; any other status goes as a response with the rest as its body. */
    private Future<Void> answer(String text, Buffer rest) {
        String[] lines = text.split("\r\n");
        java.util.regex.Matcher status = java.util.regex.Pattern.compile("^HTTP/1\\.[01] (\\d{3})(?: (.*))?$").matcher(lines[0]);
        if (!status.matches()) {
            // Not HTTP: as a client of Node would, the connection is simply closed.
            destroy();
            return Future.succeededFuture();
        }
        int code = Integer.parseInt(status.group(1));
        String message = status.group(2);
        boolean length = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (code == 101 || !ServerExchange.HOP_BY_HOP.contains(lower)) {
                response.headers().add(name, value);
            }
            length |= lower.equals("content-length");
        }
        if (code == 101) {
            // Vert.x sends the 101 with these headers and its standard reason phrase, then hands over the connection.
            return request.toNetSocket().compose(upgraded -> {
                attach(upgraded);
                return rest.length() == 0 ? Future.<Void>succeededFuture() : upgraded.write(rest);
            }).recover(err -> {
                destroy();
                return epipe();
            });
        }
        response.setStatusCode(code);
        if (message != null && !message.isEmpty()) {
            response.setStatusMessage(message);
        }
        if (!length) {
            response.setChunked(true);
        }
        return rest.length() == 0 ? response.writeHead() : response.write(rest);
    }

    private void attach(NetSocket upgraded) {
        socket = upgraded;
        upgraded.pause();
        upgraded.closeHandler(v -> closed());
        delegate = new VertxBodyStream(upgraded, context, upgraded::close);
        if (onChunk != null) {
            delegate.handler(onChunk, onEnd, onError);
        }
        if (resumed) {
            delegate.resume();
        }
    }

    @Override
    public Future<Void> end() {
        return then(() -> {
            if (socket != null) {
                return socket.end();
            }
            if (answered && !response.ended() && !response.closed()) {
                return response.end();
            }
            // Ended without an answer: the client sees the connection close, as from Node.
            destroy();
            return Future.succeededFuture();
        });
    }

    @Override
    public Future<JsonObject> upgradeTls(JsonObject tls) {
        return Future.failedFuture(new HostException("Error", "ERR_NOT_SUPPORTED", "The ingress already handles TLS for a service"));
    }

    @Override
    public void setNoDelay(boolean noDelay) {
    }

    @Override
    public void setKeepAlive(boolean keepAlive) {
    }

    @Override
    public void destroy() {
        context.runOnContext(v -> {
            if (socket != null) {
                socket.close();
            } else {
                request.connection().close();
            }
            closed();
        });
    }

    /** Runs each operation after the one before, on the context, so writes and the end keep their order. */
    private Future<Void> then(java.util.function.Supplier<Future<Void>> operation) {
        Promise<Void> done = Promise.promise();
        context.runOnContext(v -> {
            Future<Void> next = last.transform(ignored -> operation.get());
            last = next.otherwiseEmpty();
            next.onComplete(done);
        });
        return done.future();
    }

    private static Future<Void> epipe() {
        return Future.failedFuture(new HostException("Error", "EPIPE", "This socket has been ended by the other party"));
    }

    private static int indexOf(Buffer buffer, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= buffer.length(); i++) {
            for (int j = 0; j < needle.length; j++) {
                if (buffer.getByte(i + j) != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
