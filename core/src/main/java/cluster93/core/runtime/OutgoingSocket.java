package cluster93.core.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * A TCP connection a script opened ({@code net}, {@code tls}; D-116), already through the egress gate. Its data arrives
 * on {@link #data()}, paused until read; writes complete once the connection has taken them.
 */
public interface OutgoingSocket {

    /** Addresses, and TLS details if it's encrypted, as {@link cluster93.core.host.ConnectionInfo} describes them. */
    JsonObject info();

    /** What the peer sends, paused; it ends when the peer closes. */
    BodyStream data();

    Future<Void> write(Buffer chunk);

    /** Stops sending (a half-close on plain TCP; TLS closes), once everything written has gone. */
    Future<Void> end();

    /**
     * Starts TLS on the open connection (STARTTLS, Postgres's SSLRequest): the same options as connecting with TLS.
     * Completes with {@link #info()} once the handshake is done.
     */
    Future<JsonObject> upgradeTls(JsonObject tls);

    void setNoDelay(boolean noDelay);

    void setKeepAlive(boolean keepAlive);

    /** Closes the connection at once. Safe to call more than once. */
    void destroy();
}
