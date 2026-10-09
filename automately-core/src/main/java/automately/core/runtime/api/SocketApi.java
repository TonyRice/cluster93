package automately.core.runtime.api;

import automately.core.runtime.HostException;
import automately.core.runtime.OutgoingSocket;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** Outbound TCP and TLS sockets for {@code net} and {@code tls} (D-116), through the egress gate and its limits. */
public interface SocketApi {

    /** No sockets: for hosts without network access. */
    SocketApi UNAVAILABLE = request -> Future.failedFuture(new HostException("Error", "unavailable", "Sockets aren't available on this host"));

    /**
     * {@code {host, port, tls?: {servername, ca, cert, key, rejectUnauthorized, checkHostname, ALPNProtocols, minVersion,
     * maxVersion}, noDelay, keepAlive, timeoutMs}}. Completes once connected (and, with TLS, after the handshake).
     */
    Future<OutgoingSocket> connect(JsonObject request);

    /**
     * An HTTP/2 session (D-117): {@code {authority: "https://host:port", tls?: {...as connect's}}}. It counts as a
     * socket. Completes once connected and, with TLS, once the chain is judged.
     */
    default Future<automately.core.runtime.OutgoingHttp2Session> http2(JsonObject request) {
        return Future.failedFuture(new HostException("Error", "unavailable", "HTTP/2 isn't available on this host"));
    }

    /** The certificates the host trusts, as PEM, for {@code tls.rootCertificates}. */
    default JsonArray rootCertificates() {
        return new JsonArray();
    }
}
