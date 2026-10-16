package cluster93.runner.protocol.transport.uds;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import java.time.Duration;
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
     * Connects, retrying a <b>bounded</b> number of times with backoff until it gives up.
     *
     * <p>This is what makes "the node listens first, the worker connects second" safe with no start ordering: a
     * deployment starts the node and its worker containers together, and a worker that loses the race must wait for
     * the socket rather than die (D-166, work order §2.3). Bounded so it never spins hot, and failing visibly so the
     * supervisor's restart and the node's log both show it.
     *
     * @param socketPath      the node's worker socket
     * @param attempts        how many connects to try in total, at least 1
     * @param initialBackoff  how long to wait after the first failure
     * @param maxBackoff      the cap the wait doubles up to
     * @return the connected socket, or a failure naming the path once every attempt is spent
     */
    public Future<NetSocket> connectRetrying(String socketPath, int attempts, Duration initialBackoff, Duration maxBackoff) {
        Objects.requireNonNull(socketPath, "socketPath");
        int tries = Math.max(1, attempts);
        Duration cap = maxBackoff == null || maxBackoff.isNegative() ? Duration.ZERO : maxBackoff;
        Duration first = initialBackoff == null || initialBackoff.isNegative() ? Duration.ZERO : initialBackoff;
        Promise<NetSocket> result = Promise.promise();
        attempt(socketPath, tries, first, cap, first, result);
        return result.future();
    }

    private void attempt(String socketPath, int attemptsLeft, Duration initial, Duration cap, Duration wait,
                         Promise<NetSocket> result) {
        connect(socketPath).onComplete(ar -> {
            if (ar.succeeded()) {
                result.tryComplete(ar.result());
                return;
            }
            if (attemptsLeft <= 1) {
                result.tryFail(new IllegalStateException("Could not connect to the node's worker socket " + socketPath
                        + " after " + attemptsLeft + " attempt(s); is a node running with C93_WORKER_SOCKET set? Last: "
                        + ar.cause().getMessage(), ar.cause()));
                return;
            }
            long delay = Math.max(0L, wait.toMillis());
            vertx.setTimer(Math.max(1L, delay), id ->
                    attempt(socketPath, attemptsLeft - 1, initial, cap, nextBackoff(wait, cap), result));
        });
    }

    /** Doubles the backoff, capped. */
    private static Duration nextBackoff(Duration wait, Duration cap) {
        Duration doubled = wait.multipliedBy(2);
        return cap.isZero() || doubled.compareTo(cap) < 0 ? doubled : cap;
    }

    /**
     * Closes the underlying NetClient.
     */
    public Future<Void> close() {
        return client.close();
    }
}
