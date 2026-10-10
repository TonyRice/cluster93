package automately.runtime.js;

import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingSocket;
import automately.core.runtime.api.SocketApi;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/** {@code net} and {@code tls} sockets (D-116); js/node/net.js and tls.js build Node's classes around them. */
public final class SocketBinding {

    private final HostBridge bridge;
    private final SocketApi sockets;

    SocketBinding(HostBridge bridge, SocketApi sockets) {
        this.bridge = bridge;
        this.sockets = sockets;
    }

    /** Resolves once connected with the socket's {@code id}, its {@code info}, and the {@code streamId} its data arrives on. */
    @HostFunction(name = "connect")
    public JsHandle connect(JsArg request) throws JsEngineException {
        return bridge.promise(() -> sockets.connect(request.options()), socket -> new JsonObject()
                .put("id", bridge.addSocket(socket)).put("info", socket.info()).put("streamId", bridge.openStream(socket.data())));
    }

    /** Settles once the connection has taken the bytes. */
    @HostFunction(name = "write")
    public JsHandle write(int id, JsArg data) throws JsEngineException {
        OutgoingSocket socket = bridge.socket(id);
        byte[] bytes = data.bytes();
        return bridge.promise(() -> socket == null ? closed() : socket.write(Buffer.buffer(bytes)).map(v -> Js.UNDEFINED));
    }

    /** Stops sending once everything written has gone. */
    @HostFunction(name = "end")
    public JsHandle end(int id) throws JsEngineException {
        OutgoingSocket socket = bridge.socket(id);
        return bridge.promise(() -> socket == null ? closed() : socket.end().map(v -> Js.UNDEFINED));
    }

    /** Starts TLS on the open connection; resolves with its new info. */
    @HostFunction(name = "upgrade")
    public JsHandle upgrade(int id, JsArg tls) throws JsEngineException {
        OutgoingSocket socket = bridge.socket(id);
        JsonObject options = tls.options();
        return bridge.promise(() -> socket == null ? closed() : socket.upgradeTls(options));
    }

    @HostFunction(name = "setNoDelay")
    public void setNoDelay(int id, boolean noDelay) {
        OutgoingSocket socket = bridge.socket(id);
        if (socket != null) {
            socket.setNoDelay(noDelay);
        }
    }

    @HostFunction(name = "setKeepAlive")
    public void setKeepAlive(int id, boolean keepAlive) {
        OutgoingSocket socket = bridge.socket(id);
        if (socket != null) {
            socket.setKeepAlive(keepAlive);
        }
    }

    @HostFunction(name = "destroy")
    public void destroy(int id) {
        OutgoingSocket socket = bridge.removeSocket(id);
        if (socket != null) {
            socket.destroy();
        }
    }

    /** The trusted certificates as a JSON list of PEM, for {@code tls.rootCertificates}. */
    @HostFunction(name = "rootCertificates")
    public String rootCertificates() {
        return sockets.rootCertificates().encode();
    }

    private static <T> Future<T> closed() {
        return Future.failedFuture(new HostException("Error", "EPIPE", "This socket has been ended"));
    }
}
