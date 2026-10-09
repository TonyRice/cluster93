package automately.runtime.javet;

import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingSocket;
import automately.core.runtime.api.SocketApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;
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
    @V8Function(name = "connect")
    public V8ValuePromise connect(V8Value request) throws JavetException {
        return bridge.promise(() -> sockets.connect(bridge.values().options(request)), socket -> new JsonObject()
                .put("id", bridge.addSocket(socket)).put("info", socket.info()).put("streamId", bridge.openStream(socket.data())));
    }

    /** Settles once the connection has taken the bytes. */
    @V8Function(name = "write")
    public V8ValuePromise write(int id, V8Value data) throws JavetException {
        OutgoingSocket socket = bridge.socket(id);
        byte[] bytes = HostBridge.bytes(data);
        return bridge.promise(() -> socket == null ? closed() : socket.write(Buffer.buffer(bytes)).map(v -> JsValues.UNDEFINED));
    }

    /** Stops sending once everything written has gone. */
    @V8Function(name = "end")
    public V8ValuePromise end(int id) throws JavetException {
        OutgoingSocket socket = bridge.socket(id);
        return bridge.promise(() -> socket == null ? closed() : socket.end().map(v -> JsValues.UNDEFINED));
    }

    /** Starts TLS on the open connection; resolves with its new info. */
    @V8Function(name = "upgrade")
    public V8ValuePromise upgrade(int id, V8Value tls) throws JavetException {
        OutgoingSocket socket = bridge.socket(id);
        JsonObject options = bridge.values().options(tls);
        return bridge.promise(() -> socket == null ? closed() : socket.upgradeTls(options));
    }

    @V8Function(name = "setNoDelay")
    public void setNoDelay(int id, boolean noDelay) {
        OutgoingSocket socket = bridge.socket(id);
        if (socket != null) {
            socket.setNoDelay(noDelay);
        }
    }

    @V8Function(name = "setKeepAlive")
    public void setKeepAlive(int id, boolean keepAlive) {
        OutgoingSocket socket = bridge.socket(id);
        if (socket != null) {
            socket.setKeepAlive(keepAlive);
        }
    }

    @V8Function(name = "destroy")
    public void destroy(int id) {
        OutgoingSocket socket = bridge.removeSocket(id);
        if (socket != null) {
            socket.destroy();
        }
    }

    /** The trusted certificates as a JSON list of PEM, for {@code tls.rootCertificates}. */
    @V8Function(name = "rootCertificates")
    public String rootCertificates() {
        return sockets.rootCertificates().encode();
    }

    private static <T> Future<T> closed() {
        return Future.failedFuture(new HostException("Error", "EPIPE", "This socket has been ended"));
    }
}
