package cluster93.core.host;

import cluster93.core.runtime.OutgoingSocket;
import cluster93.core.runtime.api.SocketApi;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code net} and {@code tls} sockets through the node's {@link SocketClient}, and {@code http2} sessions through its
 * {@link Http2Client}, as the job's tenant (D-116, D-117). A session counts as one of the script's sockets.
 */
final class NodeSocketApi implements SocketApi {

    private final SocketClient client;
    private final Http2Client http2;
    private final String tenant;
    /** A place among the script's concurrent requests, for each HTTP/2 stream (D-117). */
    private final java.util.function.Supplier<Runnable> requestSlot;
    /** The script's open sockets, held to the cluster policy's per-script limit. */
    private final AtomicInteger open = new AtomicInteger();

    NodeSocketApi(SocketClient client, Http2Client http2, String tenant, java.util.function.Supplier<Runnable> requestSlot) {
        this.client = client;
        this.http2 = http2;
        this.tenant = tenant;
        this.requestSlot = requestSlot;
    }

    @Override
    public Future<OutgoingSocket> connect(JsonObject request) {
        return client.connect(tenant, request, open);
    }

    @Override
    public Future<cluster93.core.runtime.OutgoingHttp2Session> http2(JsonObject request) {
        return http2.connect(tenant, request, open, requestSlot);
    }

    @Override
    public JsonArray rootCertificates() {
        return SocketClient.rootCertificates();
    }
}
