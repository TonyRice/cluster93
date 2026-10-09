package automately.core.host;

import automately.core.runtime.OutgoingSocket;
import automately.core.runtime.api.SocketApi;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.atomic.AtomicInteger;

/** {@code net} and {@code tls} sockets through the node's {@link SocketClient}, as the job's tenant (D-116). */
final class NodeSocketApi implements SocketApi {

    private final SocketClient client;
    private final String tenant;
    /** The script's open sockets, held to the cluster policy's per-script limit. */
    private final AtomicInteger open = new AtomicInteger();

    NodeSocketApi(SocketClient client, String tenant) {
        this.client = client;
        this.tenant = tenant;
    }

    @Override
    public Future<OutgoingSocket> connect(JsonObject request) {
        return client.connect(tenant, request, open);
    }

    @Override
    public JsonArray rootCertificates() {
        return SocketClient.rootCertificates();
    }
}
