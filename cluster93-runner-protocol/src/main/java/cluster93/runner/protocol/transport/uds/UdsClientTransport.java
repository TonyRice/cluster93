package cluster93.runner.protocol.transport.uds;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import java.util.Objects;

/**
 * Client-side Unix Domain Socket (UDS) transport for worker processes connecting to the node (D-127, §4).
 */
public class UdsClientTransport {

    private final Vertx vertx;
    private final NetClient client;

    public UdsClientTransport(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.client = vertx.createNetClient(new NetClientOptions());
    }

    /**
     * Connects to a listening Unix Domain Socket node.
     *
     * @param socketPath filesystem path to the domain socket (e.g. "/tmp/cluster93/worker.sock")
     * @return Future completed with the connected NetSocket
     */
    public Future<NetSocket> connect(String socketPath) {
        Objects.requireNonNull(socketPath, "socketPath");
        SocketAddress address = SocketAddress.domainSocketAddress(socketPath);
        return client.connect(address);
    }

    /**
     * Closes the underlying NetClient.
     */
    public Future<Void> close() {
        return client.close();
    }
}
