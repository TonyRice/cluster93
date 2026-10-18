package cluster93.core.host;

import cluster93.core.host.egress.Egress;
import cluster93.core.host.egress.EgressLimits;
import cluster93.core.host.egress.EgressProtocol;
import cluster93.core.runtime.BodyStream;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.OutgoingSocket;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.ConnectOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Scripts' TCP and TLS sockets on one node (D-116): through the egress gate as {@code tcp}, within the cluster policy's
 * limits (sockets per script, per tenant on this node, and new connections a minute per tenant on this node), to the
 * exact address the gate checked. No idle timeout, as in Node: a socket lives until it's closed or its run ends.
 */
public final class SocketClient {

    static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._:\\-\\[\\]%]{1,253}$");
    private static final Set<String> NODE_HINTED = Set.of("DEPTH_ZERO_SELF_SIGNED_CERT", "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
            "UNABLE_TO_GET_ISSUER_CERT_LOCALLY", "UNABLE_TO_GET_ISSUER_CERT");
    private static final List<String> NODE_VERSIONS = List.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3");

    private final Vertx vertx;
    private final cluster93.core.host.egress.Gate egress;
    private final NetClient client;
    private final Map<String, AtomicInteger> openByTenant = new ConcurrentHashMap<>();
    private final Map<String, Bucket> rates = new ConcurrentHashMap<>();

    SocketClient(Vertx vertx, cluster93.core.host.egress.Gate egress) {
        this.vertx = vertx;
        this.egress = egress;
        // Keepalive finds dead peers, since there's no idle timeout; Nagle off, as Node's sockets default to since v18.
        this.client = vertx.createNetClient(new NetClientOptions().setTcpKeepAlive(true).setTcpNoDelay(true)
                .setConnectTimeout(CONNECT_TIMEOUT_MS));
    }

    /** A token bucket: {@code perMinute} connections, refilled evenly. */
    private static final class Bucket {
        private double tokens = -1;
        private long at;

        synchronized boolean take(int perMinute) {
            long now = System.nanoTime();
            tokens = tokens < 0 ? perMinute : Math.min(perMinute, tokens + (now - at) / 60e9 * perMinute);
            at = now;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }

    /**
     * Connects {@code tenant}'s script. {@code request} is {@link cluster93.core.runtime.api.SocketApi#connect}'s;
     * {@code executionOpen} counts the script's open sockets.
     */
    public Future<OutgoingSocket> connect(String tenant, JsonObject request, AtomicInteger executionOpen) {
        String host = request.getValue("host") instanceof String s ? s.trim() : "";
        Integer port = request.getValue("port") instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? n.intValue() : null;
        if (host.isEmpty() || !HOST.matcher(host).matches()) {
            return Future.failedFuture(new HostException("TypeError", "ERR_INVALID_ARG_VALUE", "Invalid host \"" + host + "\""));
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (port == null || port < 1 || port > 65535) {
            return Future.failedFuture(new HostException("RangeError", "ERR_SOCKET_BAD_PORT", "Port should be >= 1 and < 65536"));
        }
        JsonObject tls = request.getJsonObject("tls");
        ClientSSLOptions ssl;
        try {
            ssl = tls == null ? null : sslOptions(tls);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(new HostException("TypeError", "ERR_INVALID_ARG_VALUE", "Invalid TLS options: " + e.getMessage()));
        }
        String what = tls == null ? "net.connect" : "tls.connect";
        String target = host;
        int targetPort = port;
        return reserve(tenant, executionOpen, what, target, targetPort).compose(release -> {
            return egress.address(tenant, target, targetPort, EgressProtocol.TCP, what).compose(address -> {
                ConnectOptions options = new ConnectOptions()
                        .setRemoteAddress(SocketAddress.inetSocketAddress(targetPort, address.getHostAddress()))
                        .setHost(target)
                        .setPort(targetPort)
                        .setTimeout(CONNECT_TIMEOUT_MS);
                if (ssl != null) {
                    options.setSsl(true).setSslOptions(ssl);
                    String servername = servername(tls, target);
                    if (servername != null) {
                        options.setSniServerName(servername);
                    }
                }
                return client.connect(options).recover(err -> Future.failedFuture(networkError(err, what, address.getHostAddress(), targetPort)))
                        .compose(socket -> {
                            socket.pause();
                            return verified(socket, tls).map(info -> {
                                egress.accounting().opened(tenant, ssl != null);
                                return (OutgoingSocket) new Connection(socket, vertx.getOrCreateContext(), tenant == null ? "-" : tenant, target,
                                        release, info, egress.accounting());
                            });
                        });
            }).onFailure(err -> release.run());
        });
    }

    /**
     * A place for one more connection within the cluster policy's limits (D-116): one of the script's sockets, one of the
     * tenant's on this node, and a token from its connect rate. Completes with how to give the place back (once), or fails
     * with the limit it hit. Sockets and HTTP/2 sessions both count.
     */
    Future<Runnable> reserve(String tenant, AtomicInteger executionOpen, String what, String target, int targetPort) {
        return egress.cluster().compose(policy -> {
            EgressLimits limits = policy.limits();
            if (executionOpen.incrementAndGet() > limits.socketsPerExecution()) {
                executionOpen.decrementAndGet();
                return Future.failedFuture(HostException.limit("Too many open sockets (limit " + limits.socketsPerExecution() + " per script)"));
            }
            AtomicInteger tenantOpen = openByTenant.computeIfAbsent(tenant == null ? "-" : tenant, t -> new AtomicInteger());
            if (tenantOpen.incrementAndGet() > limits.socketsPerTenantPerNode()) {
                tenantOpen.decrementAndGet();
                executionOpen.decrementAndGet();
                return Future.failedFuture(HostException.limit("Too many open sockets for this tenant on this node (limit "
                        + limits.socketsPerTenantPerNode() + ")"));
            }
            AtomicBoolean released = new AtomicBoolean();
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    tenantOpen.decrementAndGet();
                    executionOpen.decrementAndGet();
                }
            };
            if (!rates.computeIfAbsent(tenant == null ? "-" : tenant, t -> new Bucket()).take(limits.connectsPerMinute())) {
                release.run();
                egress.accounting().denied(tenant, "connect_rate");
                return Future.failedFuture(new HostException("Error", "egress_denied", what + " to " + target + ":" + targetPort
                        + " is not allowed: this tenant opened more than " + limits.connectsPerMinute()
                        + " connections in the last minute on this node"));
            }
            return Future.succeededFuture(release);
        });
    }

    /** The name sent for SNI: the one asked for, or the host if it's a name (Node sends none for an address). */
    static String servername(JsonObject tls, String host) {
        String asked = tls.getString("servername");
        if (asked != null && !asked.isBlank()) {
            return asked;
        }
        return host.contains(":") || host.matches("[0-9.]+") ? null : host;
    }

    /** The script's {@code ca} certificates (PEM), or null for the JVM's trust store. */
    static List<String> ca(JsonObject tls) {
        Object ca = tls.getValue("ca");
        if (ca == null) {
            return null;
        }
        return (ca instanceof JsonArray list ? list.getList() : List.of(ca)).stream().map(String::valueOf).toList();
    }

    /**
     * The connection's description, or a failure with Node's verification error if the chain isn't trusted and the
     * script didn't opt out ({@code rejectUnauthorized: false}).
     */
    static Future<JsonObject> verified(NetSocket socket, JsonObject tls) {
        JsonObject info = cluster93.core.host.ConnectionInfo.of(socket, tls == null ? null : ca(tls));
        JsonObject described = info.getJsonObject("tls");
        if (tls == null || described == null || described.getBoolean("authorized", false) || !tls.getBoolean("rejectUnauthorized", true)) {
            return Future.succeededFuture(info);
        }
        socket.close();
        return Future.failedFuture(verifyFailure(described.getString("authorizationError")));
    }

    /** A refused chain as Node's error: OpenSSL's code and words. */
    static HostException verifyFailure(String code) {
        String message = cluster93.core.host.ConnectionInfo.VERIFY_MESSAGES.getOrDefault(code, "certificate verification failed");
        // Node 26 adds this hint when the chain doesn't reach a root it knows; the isolate keeps its words.
        if (NODE_HINTED.contains(code)) {
            message += "; if the root CA is installed locally, try running Node.js with --use-system-ca";
        }
        return new HostException("Error", code, message);
    }

    /** The JVM-side TLS options for a script's {@code tls.connect} options. */
    static ClientSSLOptions sslOptions(JsonObject tls) {
        // The handshake always completes; the chain is judged afterwards (ConnectionInfo), so a refusal has Node's code,
        // and nothing the script wrote has gone yet. The host name is checked by the script's tls.checkServerIdentity.
        JsonArray alpn = tls.getJsonArray("ALPNProtocols");
        List<String> protocols = alpn == null ? List.of() : alpn.stream().map(String::valueOf).toList();
        ClientSSLOptions options = new ClientSSLOptions()
                .setTrustOptions(trustAll(protocols))
                .setHostnameVerificationAlgorithm("")
                .setSslHandshakeTimeout(CONNECT_TIMEOUT_MS).setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS);
        String cert = tls.getString("cert");
        String key = tls.getString("key");
        if (cert != null || key != null) {
            if (cert == null || key == null) {
                throw new IllegalArgumentException("a client certificate needs both cert and key");
            }
            options.setKeyCertOptions(new PemKeyCertOptions().setCertValue(Buffer.buffer(cert)).setKeyValue(Buffer.buffer(key)));
        }
        if (!protocols.isEmpty()) {
            options.setUseAlpn(true).setApplicationLayerProtocols(protocols);
        }
        // Node's names; TLS 1.0 and 1.1 are below what the JVM allows, so a range starting there starts at 1.2.
        int from = NODE_VERSIONS.indexOf(tls.getString("minVersion", "TLSv1.2"));
        int to = NODE_VERSIONS.indexOf(tls.getString("maxVersion", "TLSv1.3"));
        if (from < 0 || to < 0 || from > to || to < 2) {
            throw new IllegalArgumentException("minVersion and maxVersion are TLSv1.2 or TLSv1.3 (TLS 1.0 and 1.1 aren't supported)");
        }
        options.setEnabledSecureTransportProtocols(new LinkedHashSet<>(NODE_VERSIONS.subList(Math.max(from, 2), to + 1)));
        return options;
    }

    /**
     * Trusts every chain (it's judged afterwards), with one instance per ALPN list. Vert.x keeps one TLS context per
     * client certificate and trust options, made with the ALPN list of the first connection that needed it; with its
     * shared trust-all, every later connection offered that first list (an h2 session got http/1.1). Bounded: past
     * {@link #TRUST_ALL_KEPT} lists it starts again, which only costs new contexts.
     */
    static io.vertx.core.net.TrustOptions trustAll(List<String> protocols) {
        if (TrustAll.BY_PROTOCOLS.size() > TRUST_ALL_KEPT) {
            TrustAll.BY_PROTOCOLS.clear();
        }
        return TrustAll.BY_PROTOCOLS.computeIfAbsent(protocols, p -> io.vertx.core.net.TrustOptions.wrap(new TrustAll()));
    }

    static final int TRUST_ALL_KEPT = 64;

    private static final class TrustAll extends io.netty.handler.ssl.util.SimpleTrustManagerFactory {
        static final Map<List<String>, io.vertx.core.net.TrustOptions> BY_PROTOCOLS = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        protected void engineInit(KeyStore keyStore) {
        }

        @Override
        protected void engineInit(javax.net.ssl.ManagerFactoryParameters parameters) {
        }

        @Override
        protected javax.net.ssl.TrustManager[] engineGetTrustManagers() {
            return io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE.getTrustManagers();
        }
    }

    /** The certificates the JVM trusts, as PEM: {@code tls.rootCertificates}. */
    public static JsonArray rootCertificates() {
        return Roots.PEM;
    }

    /** Read once, the first time a script asks. */
    private static final class Roots {
        static final JsonArray PEM = load();
    }

    private static JsonArray load() {
        JsonArray out = new JsonArray();
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager trust) {
                    for (X509Certificate cert : trust.getAcceptedIssuers()) {
                        out.add("-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                                + "\n-----END CERTIFICATE-----");
                    }
                }
            }
        } catch (Exception e) {
            // Without the trust store's list, the answer is empty, as it would be with no roots.
        }
        return out;
    }

    /** A connection failure as Node names it: the code, and Node's message with the address tried. */
    static HostException networkError(Throwable err, String what, String address, int port) {
        if (err instanceof HostException host) {
            return host;
        }
        // Vert.x wraps the JVM's reason ("Failed to create SSL connection"), so the whole chain is read.
        Throwable cause = err;
        StringBuilder chain = new StringBuilder();
        boolean handshake = false;
        for (Throwable t = err; t != null; t = t.getCause()) {
            chain.append(' ').append(t.getMessage());
            handshake |= t instanceof SSLHandshakeException;
            cause = t;
        }
        String message = chain.toString().toLowerCase(Locale.ROOT);
        String at = (address.contains(":") ? "[" + address + "]" : address) + ":" + port;
        if (message.contains("no_application_protocol")) {
            // The server takes none of the ALPN protocols offered (an HTTP/2 client at an HTTP/1-only server).
            return new HostException("Error", "ERR_SSL_TLSV1_ALERT_NO_APPLICATION_PROTOCOL", "tlsv1 alert no application protocol");
        }
        if (handshake || message.contains("handshake") || message.contains("certificate")) {
            String code = message.contains("expired") ? "CERT_HAS_EXPIRED"
                    : message.contains("subject alternative") || message.contains("no name matching") || message.contains("hostname")
                    ? "ERR_TLS_CERT_ALTNAME_INVALID"
                    : message.contains("pkix") || message.contains("certification path") ? "UNABLE_TO_GET_ISSUER_CERT_LOCALLY"
                    : "ECONNRESET";
            return new HostException("Error", code, what + " to " + at + ": the TLS handshake failed: " + cause.getMessage());
        }
        if (message.contains("refused")) {
            return new HostException("Error", "ECONNREFUSED", "connect ECONNREFUSED " + at);
        }
        if (message.contains("timed out") || message.contains("timeout") || err instanceof io.netty.channel.ConnectTimeoutException) {
            return new HostException("Error", "ETIMEDOUT", "connect ETIMEDOUT " + at);
        }
        if (message.contains("unreachable")) {
            return new HostException("Error", "EHOSTUNREACH", "connect EHOSTUNREACH " + at);
        }
        return new HostException("Error", "ECONNRESET", what + " to " + at + " failed: " + cause.getMessage());
    }

    /** One script's connection. */
    private static final class Connection implements OutgoingSocket {

        private final NetSocket socket;
        private final Context context;
        private final String tenant;
        private final String host;
        private final Runnable release;
        private volatile JsonObject info;
        private volatile Future<Void> lastWrite = Future.succeededFuture();
        private final BodyStream data;
        /** Where this connection's bytes and upgrades are counted: the node's metrics, or the worker's own tallies. */
        private final cluster93.core.host.egress.AccountingSink accounting;

        Connection(NetSocket socket, Context context, String tenant, String host, Runnable release, JsonObject info,
                   cluster93.core.host.egress.AccountingSink accounting) {
            this.socket = socket;
            this.context = context;
            this.tenant = tenant;
            this.host = host;
            this.release = release;
            this.info = info;
            this.accounting = accounting;
            socket.closeHandler(v -> release.run());
            BodyStream raw = new VertxBodyStream(socket, context, socket::close);
            Vertx vertx = context.owner();
            this.data = new BodyStream() {
                @Override
                public void handler(Consumer<Buffer> chunk, Runnable end, Consumer<Throwable> error) {
                    raw.handler(buffer -> {
                        accounting.bytes(tenant, "in", buffer.length());
                        chunk.accept(buffer);
                    }, end, error);
                }

                @Override
                public void pause() {
                    raw.pause();
                }

                @Override
                public void resume() {
                    raw.resume();
                }

                @Override
                public void cancel() {
                    raw.cancel();
                }
            };
        }

        @Override
        public JsonObject info() {
            return info;
        }

        @Override
        public BodyStream data() {
            return data;
        }

        @Override
        public Future<Void> write(Buffer chunk) {
            accounting.bytes(tenant, "out", chunk.length());
            Future<Void> written = socket.write(chunk).recover(err -> Future.failedFuture(
                    new HostException("Error", "EPIPE", "This socket has been ended by the other party")));
            lastWrite = written;
            return written;
        }

        @Override
        public Future<Void> end() {
            return lastWrite.otherwiseEmpty().compose(v -> {
                // Plain TCP stops sending and keeps reading, as Node's end(); a TLS connection closes, close_notify and all.
                if (!socket.isSsl() && socket instanceof io.vertx.core.internal.net.NetSocketInternal internal
                        && internal.channelHandlerContext().channel() instanceof io.netty.channel.socket.DuplexChannel duplex) {
                    Promise<Void> shut = Promise.promise();
                    duplex.shutdownOutput().addListener(done -> {
                        if (done.isSuccess()) {
                            shut.complete();
                        } else {
                            shut.fail(done.cause());
                        }
                    });
                    return shut.future();
                }
                return socket.end();
            }).recover(err -> err instanceof java.nio.channels.ClosedChannelException
                    // As Node's: ending a connection the peer has already closed is done, not an error (ENOTCONN).
                    ? Future.succeededFuture() : Future.failedFuture(err));
        }

        @Override
        public Future<JsonObject> upgradeTls(JsonObject tls) {
            ClientSSLOptions ssl;
            try {
                ssl = sslOptions(tls);
            } catch (IllegalArgumentException e) {
                return Future.failedFuture(new HostException("TypeError", "ERR_INVALID_ARG_VALUE", "Invalid TLS options: " + e.getMessage()));
            }
            String servername = servername(tls, host);
            return socket.upgradeToSsl(ssl, servername, null).recover(err -> Future.failedFuture(networkError(err, "tls.connect",
                    socket.remoteAddress() == null ? host : socket.remoteAddress().hostAddress(),
                    socket.remoteAddress() == null ? 0 : socket.remoteAddress().port())))
                    .compose(v -> verified(socket, tls)).map(described -> {
                        info = described;
                        accounting.opened(tenant, true);
                        return described;
                    });
        }

        @Override
        public void setNoDelay(boolean noDelay) {
            option(io.netty.channel.ChannelOption.TCP_NODELAY, noDelay);
        }

        @Override
        public void setKeepAlive(boolean keepAlive) {
            option(io.netty.channel.ChannelOption.SO_KEEPALIVE, keepAlive);
        }

        private <T> void option(io.netty.channel.ChannelOption<T> option, T value) {
            if (socket instanceof io.vertx.core.internal.net.NetSocketInternal internal) {
                internal.channelHandlerContext().channel().config().setOption(option, value);
            }
        }

        @Override
        public void destroy() {
            context.runOnContext(v -> socket.close());
            release.run();
        }
    }

    /** For tests: the sockets a tenant has open on this node. */
    int open(String tenant) {
        AtomicInteger count = openByTenant.get(tenant);
        return count == null ? 0 : count.get();
    }

    /** For tests: the versions a script's options enable. */
    static Set<String> versions(JsonObject tls) {
        return sslOptions(tls).getEnabledSecureTransportProtocols();
    }
}
